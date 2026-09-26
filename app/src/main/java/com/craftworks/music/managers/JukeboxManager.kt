package com.craftworks.music.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.craftworks.music.data.datasource.navidrome.NavidromeNativeApi
import com.craftworks.music.data.model.JukeboxDevice
import com.craftworks.music.data.model.JukeboxStatusResponse
import com.craftworks.music.player.ChoraMediaLibraryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object JukeboxManager {
    private val nativeApi = NavidromeNativeApi()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val defaultBrowserDevice = JukeboxDevice("browser", "本机", "browser")

    private val _devices = MutableStateFlow<List<JukeboxDevice>>(listOf(defaultBrowserDevice))
    val devices: StateFlow<List<JukeboxDevice>> = _devices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow("browser")
    val selectedDeviceId: StateFlow<String> = _selectedDeviceId.asStateFlow()

    private val _selectedDevice = MutableStateFlow<JukeboxDevice?>(defaultBrowserDevice)
    val selectedDevice: StateFlow<JukeboxDevice?> = _selectedDevice.asStateFlow()

    private val _isRemoteActive = MutableStateFlow(false)
    val isRemoteActive: StateFlow<Boolean> = _isRemoteActive.asStateFlow()

    private val _status = MutableStateFlow<JukeboxStatusResponse?>(null)
    val status: StateFlow<JukeboxStatusResponse?> = _status.asStateFlow()

    private val _deviceVolume = MutableStateFlow(50)
    val deviceVolume: StateFlow<Int> = _deviceVolume.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 铁律 1: 3 秒防抢手锁定时间戳
    private var volumeLockTimestamp: Long = 0L
    private var volumeDebounceJob: Job? = null

    // 轮询 Job
    private var pollingJob: Job? = null

    fun refreshDevices() {
        if (!NavidromeManager.checkActiveServers()) return

        scope.launch {
            _isLoading.value = true
            try {
                val resp = nativeApi.getJukeboxDevices()
                if (resp != null) {
                    // Server may report a different casing/name for the local browser device; normalize it
                    val list = resp.devices.map {
                        if (it.id == "browser") it.copy(name = "本机", type = "browser") else it
                    }
                    val finalList = if (list.none { it.id == "browser" }) {
                        listOf(defaultBrowserDevice) + list
                    } else {
                        list
                    }
                    _devices.value = finalList
                    val selectedId = resp.selected.ifBlank { "browser" }
                    _selectedDeviceId.value = selectedId
                    _selectedDevice.value = finalList.find { it.id == selectedId } ?: defaultBrowserDevice
                    _isRemoteActive.value = (selectedId != "browser")

                    if (_isRemoteActive.value) {
                        startStatusPolling()
                    } else {
                        stopStatusPolling()
                    }
                }
            } catch (e: Exception) {
                Log.e("JUKEBOX", "Failed to refresh devices: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 切换输出设备
     * 遵守四大铁律：
     * - 铁律 2: 保持本地前台服务静音驱动，绝不 stop 本地播放器
     * - 铁律 4: 携带本地进度实现无缝流转续播
     */
    fun selectDevice(
        deviceId: String,
        currentSongId: String?,
        currentPositionMs: Long,
        context: Context? = null
    ) {
        scope.launch {
            _isLoading.value = true
            try {
                val success = nativeApi.selectJukeboxDevice(deviceId)
                if (!success) {
                    mainHandler.post {
                        context?.let { Toast.makeText(it, "切换输出设备失败", Toast.LENGTH_SHORT).show() }
                    }
                    return@launch
                }

                _selectedDeviceId.value = deviceId
                val targetDevice = _devices.value.find { it.id == deviceId } ?: JukeboxDevice(deviceId, deviceId, "unknown")
                _selectedDevice.value = targetDevice
                val isRemote = (deviceId != "browser")
                _isRemoteActive.value = isRemote

                val player = ChoraMediaLibraryService.getInstance()?.player

                if (isRemote) {
                    // 铁律 2: 本地播放器静音，保持 MediaSession 与通知栏存活
                    mainHandler.post {
                        player?.volume = 0f
                    }

                    // 铁律 4: 携带当前播放进度秒数无缝投播
                    val posSec = (currentPositionMs / 1000L).coerceAtLeast(0L)
                    if (!currentSongId.isNullOrBlank() && !currentSongId.startsWith("Local_")) {
                        nativeApi.playJukebox(currentSongId, posSec)
                    }

                    startStatusPolling()
                    mainHandler.post {
                        context?.let { Toast.makeText(it, "已切换至: ${targetDevice.name}", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    // 切回本机播放
                    stopStatusPolling()
                    mainHandler.post {
                        player?.volume = 1f
                        player?.play()
                        context?.let { Toast.makeText(it, "已切换为本机播放", Toast.LENGTH_SHORT).show() }
                    }
                }

                refreshDevices()
            } catch (e: Exception) {
                Log.e("JUKEBOX", "Error selecting device $deviceId: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 铁律 1: 0 音量容错与 3 秒防抢手
     */
    fun setVolume(newVolume: Int) {
        val clamped = newVolume.coerceIn(0, 100)
        _deviceVolume.value = clamped
        volumeLockTimestamp = System.currentTimeMillis()

        volumeDebounceJob?.cancel()
        volumeDebounceJob = scope.launch {
            delay(200) // 200ms 防抖
            nativeApi.controlJukebox("volume", clamped.toLong())
        }
    }

    /**
     * 远程播控指令
     * 铁律 3: 依据 deviceType 动态降级（小爱音箱不支持 seek）
     */
    fun control(action: String, value: Long? = null, context: Context? = null) {
        if (!_isRemoteActive.value) return

        if (action == "seek") {
            if (_selectedDevice.value?.type == "xiaomi") {
                Log.w("JUKEBOX", "Xiaomi speaker does not support seek (铁律 3)")
                mainHandler.post {
                    context?.let { Toast.makeText(it, "小爱音箱原生协议不支持拖动跳转", Toast.LENGTH_SHORT).show() }
                }
                return
            }
        }

        scope.launch {
            try {
                nativeApi.controlJukebox(action, value)
            } catch (e: Exception) {
                Log.e("JUKEBOX", "Control $action failed: ${e.message}")
            }
        }
    }

    fun playSong(songId: String, positionSec: Long = 0L) {
        if (!_isRemoteActive.value) return
        scope.launch {
            try {
                nativeApi.playJukebox(songId, positionSec)
            } catch (e: Exception) {
                Log.e("JUKEBOX", "Play song $songId on remote failed: ${e.message}")
            }
        }
    }

    private fun startStatusPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isRemoteActive.value) {
                try {
                    val st = nativeApi.getJukeboxStatus()
                    if (st != null) {
                        _status.value = st

                        // 铁律 1: 0 音量忽略与 3 秒锁定保护
                        val isLocked = System.currentTimeMillis() - volumeLockTimestamp < 3000L
                        if (st.volume > 0 && !isLocked) {
                            _deviceVolume.value = st.volume
                        } else if (st.volume == 0) {
                            Log.d("JUKEBOX", "Ignored 0 volume report from remote device (铁律 1)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("JUKEBOX", "Status poll error: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    private fun stopStatusPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _status.value = null
    }
}
