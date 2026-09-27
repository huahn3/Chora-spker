package com.craftworks.music.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.Companion.getOrCreateClientUniqueId
import com.craftworks.music.data.datasource.navidrome.NavidromeNativeApi
import com.craftworks.music.player.ChoraMediaLibraryService
import com.craftworks.music.data.model.PlaybackSessionDto
import com.craftworks.music.data.model.PlaybackSessionsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback Handoff & Takeover (navidrome2all fork).
 * T2 polls sessions, T3 takes over, T4 listens for SSE pause.
 * Iron rule: local playback starts FIRST, takeover POST goes after.
 */
object PlaybackHandoffManager {
    private const val AMBIENT_POLL_INTERVAL_MS = 10_000L
    private const val AMBIENT_POLL_BACKOFF_MS = 60_000L

    private val nativeApi = NavidromeNativeApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _sessions = MutableStateFlow<List<PlaybackSessionDto>>(emptyList())
    val sessions: StateFlow<List<PlaybackSessionDto>> = _sessions.asStateFlow()

    /**
     * Newest session belonging to *another* device, `playing` preferred. The
     * mini player's output chip paints its ring with this and long-presses into
     * a takeover, so it must not depend on the bottom sheet being open.
     */
    private val _latestOtherSession = MutableStateFlow<PlaybackSessionDto?>(null)
    val latestOtherSession: StateFlow<PlaybackSessionDto?> = _latestOtherSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _takeoverInFlight = MutableStateFlow<String?>(null)
    val takeoverInFlight: StateFlow<String?> = _takeoverInFlight.asStateFlow()

    private var pollJob: Job? = null
    private var ambientPollJob: Job? = null
    private var sseJob: Job? = null
    private var sseRunning = false

    /** Result of the most recent [refreshSessions] call, for ambient backoff. */
    @Volatile
    private var lastRefreshOk = false

    private fun List<PlaybackSessionDto>.pickLatestOther(): PlaybackSessionDto? =
        asSequence()
            .filter { !it.isCurrentSession && it.songId.isNotBlank() }
            .sortedWith(
                compareByDescending<PlaybackSessionDto> { it.state == "playing" }
                    // `lastReport` is an ISO-8601 timestamp, so a plain string
                    // comparison is already chronological.
                    .thenByDescending { it.lastReport ?: "" }
            )
            .firstOrNull()

    fun myClientId(context: Context? = null): String? {
        return try {
            val ctx = context
                ?: com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.appContextRef
                ?: return nativeApi.myClientUniqueId()
            getOrCreateClientUniqueId(ctx)
        } catch (e: Exception) {
            nativeApi.myClientUniqueId()
        }
    }

    suspend fun refreshSessions(): List<PlaybackSessionDto> {
        if (!NavidromeManager.checkActiveServers()) {
            lastRefreshOk = false
            return emptyList()
        }
        return try {
            val resp: PlaybackSessionsResponse? = nativeApi.getPlaybackSessions()
            val list = resp?.sessions.orEmpty().map { it.withCoverArt() }
            _sessions.value = list
            _latestOtherSession.value = list.pickLatestOther()
            lastRefreshOk = true
            list
        } catch (e: Exception) {
            Log.w("HANDOFF", "refreshSessions failed: ${e.message}")
            lastRefreshOk = false
            emptyList()
        }
    }

    /**
     * Attach a signed cover-art URL to a session.
     *
     * The fork's session payload only carries ids; the Subsonic `getCoverArt.view`
     * endpoint requires the usual `u/t/s` credentials, exactly like the song/album
     * lists do in `providers/navidrome`. Older servers report no `coverArtId`, so
     * fall back to the album and then the song id — Navidrome resolves cover art
     * for any media id.
     */
    private suspend fun PlaybackSessionDto.withCoverArt(): PlaybackSessionDto {
        val assetId = coverArtId?.takeIf { it.isNotBlank() }
            ?: albumId?.takeIf { it.isNotBlank() }
            ?: songId.takeIf { it.isNotBlank() }
            ?: return copy(coverArtUrl = null)
        val url = try {
            val server = NavidromeManager.getCurrentServer() ?: return copy(coverArtId = assetId)
            val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
            val salt = NavidromeDataSource.generateSalt(8)
            val token = NavidromeDataSource.md5Hash(server.password + salt)
            "$baseUrl/rest/getCoverArt.view?&id=$assetId&u=${server.username}" +
                "&t=$token&s=$salt&v=1.16.1&c=Chora&size=100"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("HANDOFF", "coverArt sign failed: ${e.message}")
            null
        }
        return copy(coverArtId = assetId, coverArtUrl = url)
    }

    fun startPolling(intervalMs: Long = 5000L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try { refreshSessions() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Keeps [latestOtherSession] fresh for the dock's output chip.
     *
     * The bottom sheet used to be the only poller, so the ring stayed blank
     * until you opened that sheet. 10s is cheap next to the SSE stream that
     * already runs for the whole process; on failure (stock Navidrome has no
     * `/api/playback/sessions`, server offline) it backs off to
     * [AMBIENT_POLL_BACKOFF_MS] instead of dying, so a transient outage heals
     * itself without needing the sheet to be opened again.
     */
    fun startAmbientPolling(intervalMs: Long = AMBIENT_POLL_INTERVAL_MS) {
        if (ambientPollJob?.isActive == true) return
        ambientPollJob = scope.launch {
            while (isActive) {
                // No server configured yet is not an error, just idle.
                if (NavidromeManager.checkActiveServers()) {
                    try {
                        refreshSessions()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("HANDOFF", "ambient poll failed: ${e.message}")
                    }
                }
                delay(if (lastRefreshOk) intervalMs else AMBIENT_POLL_BACKOFF_MS)
            }
        }
    }

    fun stopAmbientPolling() {
        ambientPollJob?.cancel()
        ambientPollJob = null
    }

    fun takeover(
        session: PlaybackSessionDto,
        context: Context,
        songRepository: com.craftworks.music.data.repository.SongRepository,
        forcePlay: Boolean = false,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        if (_takeoverInFlight.value == session.sessionId) return
        _takeoverInFlight.value = session.sessionId
        scope.launch {
            var ok = false
            var msg = ""
            try {
                val item = try {
                    songRepository.getSong(session.songId)
                } catch (e: Exception) {
                    Log.w("HANDOFF", "getSong failed: ${e.message}")
                    null
                }
                if (item == null) {
                    msg = "找不到歌曲 ${session.title}"
                    mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    onDone?.invoke(false, msg)
                    return@launch
                }
                val startMs = session.positionMs.coerceAtLeast(0L)
                val targetOutput = session.outputDevice.takeIf { it.isNotBlank() } ?: "browser"
                val isRemoteOutput = targetOutput != "browser" && targetOutput != "local"
                val playItem = if (isRemoteOutput) withHandoffStart(item, startMs) else item
                if (isRemoteOutput) {
                    try {
                        // Await the output switch: it flips `isRemoteActive` and mutes
                        // the local player. Starting audio before that would make
                        // `onMediaItemTransition` think output is still local, so the
                        // speaker would never receive the track (and the handoff
                        // position would be lost).
                        // The mute itself belongs to selectDeviceAwait (it posts to
                        // the main thread); touching `player.volume` from this IO
                        // coroutine would throw "Player is accessed on the wrong thread".
                        JukeboxManager.refreshDevices()
                        JukeboxManager.selectDeviceAwait(targetOutput, null, 0L, context)
                    } catch (e: Exception) {
                        Log.w("HANDOFF", "remote handoff failed: ${e.message}")
                    }
                }
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        doTakeoverSeek(session, playItem, context, forcePlay, startMs, isRemoteOutput)
                    }
                    ok = true
                } catch (e: Exception) {
                    msg = "接管播放失败"
                    val m2 = msg
                    mainHandler.post { android.widget.Toast.makeText(context, m2, android.widget.Toast.LENGTH_SHORT).show() }
                    onDone?.invoke(false, m2)
                    return@launch
                }
                val posSec = startMs / 1000
                msg = "已从 %02d:%02d 接管播放: %s".format(posSec / 60, posSec % 60, session.title)
                val m3 = msg
                mainHandler.post { android.widget.Toast.makeText(context, m3, android.widget.Toast.LENGTH_SHORT).show() }
                _sessions.value = _sessions.value.filterNot { it.sessionId == session.sessionId }
                _latestOtherSession.value = _sessions.value.pickLatestOther()
                try {
                    nativeApi.takeoverSession(session.sessionId, "pause", "Chora (手机端)", if (isRemoteOutput) targetOutput else "browser")
                } catch (e: Exception) {
                    android.util.Log.w("HANDOFF", "takeover POST failed: ${e.message}")
                }
                inheritBilingual(session)
                try { refreshSessions() } catch (e: Exception) { }
                onDone?.invoke(true, m3)
            } finally {
                _takeoverInFlight.value = null
            }
        }
    }

    /**
     * Stamps the victim's position onto the item. `MusicService` reads it in
     * `onMediaItemTransition` to start an inherited Jukebox speaker at the right
     * second instead of 0:00.
     */
    private fun withHandoffStart(
        item: androidx.media3.common.MediaItem,
        startMs: Long
    ): androidx.media3.common.MediaItem {
        val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply {
            putLong("handoffStartMs", startMs)
        }
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private suspend fun doTakeoverSeek(session: PlaybackSessionDto, item: androidx.media3.common.MediaItem, context: Context, forcePlay: Boolean, startMs: Long, isRemoteOutput: Boolean) {
        val manager = try { com.craftworks.music.player.MediaControllerManager.getInstance(context) } catch (e: Exception) { null }
        val controller = manager?.controller?.value
        val player: androidx.media3.common.Player = controller
            ?: ChoraMediaLibraryService.getInstance()?.player
            ?: return
        val vol = (session.effectiveVolume / 100f).coerceIn(0f, 1f)
        val repeatMode = when (session.playMode) {
            "single" -> androidx.media3.common.Player.REPEAT_MODE_ONE
            "all" -> androidx.media3.common.Player.REPEAT_MODE_ALL
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        if (controller != null) {
            com.craftworks.music.player.SongHelper.play(listOf(item), 0, controller)
        } else {
            player.setMediaItem(item)
            player.prepare()
        }
        // Remote output: never touch local volume (the speaker is audible,
        // the local player must stay muted to avoid double audio).
        if (!isRemoteOutput) try { player.volume = vol } catch (e: Exception) { }
        try { player.repeatMode = repeatMode } catch (e: Exception) { }
        seekWhenPrepared(player, startMs)
        if (forcePlay || session.state == "playing") player.play()
    }

    /**
     * `seekTo` on an item that has not finished preparing is silently dropped
     * (`C.TIMEBAR_STATE_NO_SEEK`): right after the queue is set the window is
     * still `TIMEBAR_STATE_NOT_AVAILABLE`, so the plain call used to do nothing
     * and the track restarted at 0:00 instead of the handoff position. Retry
     * once the duration is known; the listener removes itself so it can't leak.
     */
    private fun seekWhenPrepared(player: androidx.media3.common.Player, startMs: Long) {
        if (startMs <= 0L) return
        trySeek(player, startMs)
        if (player.currentPosition >= startMs - 1000) return
        val expectedMediaId = player.currentMediaItem?.mediaId
        player.addListener(object : androidx.media3.common.Player.Listener {
            private fun retry() {
                trySeek(player, startMs)
                // Drop the listener once the position landed, or as soon as the
                // user/queue moved on to a different track.
                if (player.currentPosition >= startMs - 1000 ||
                    player.currentMediaItem?.mediaId != expectedMediaId
                ) {
                    player.removeListener(this)
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = retry()
            override fun onEvents(p: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) = retry()
        })
    }

    private fun trySeek(player: androidx.media3.common.Player, startMs: Long) {
        try { if (player.isCurrentMediaItemSeekable) player.seekTo(startMs) } catch (e: Exception) { }
    }

    private fun inheritBilingual(session: PlaybackSessionDto) {
        if (!session.bilingual) return
        scope.launch {
            try {
                val cached = nativeApi.getCachedLyricsTranslation(session.songId)
                if (cached != null && !cached.lines.isNullOrEmpty() && com.craftworks.music.data.repository.LyricsState.currentSongId == session.songId) {
                    val mapped = cached.lines.map { line ->
                        com.craftworks.music.data.model.Lyric(startMs = line.start, text = if (line.translation.isNotBlank()) listOf(line.original, line.translation) else listOf(line.original), endMs = line.end)
                    }
                    com.craftworks.music.data.repository.LyricsState.translatedLyrics = mapped
                    com.craftworks.music.data.repository.LyricsState.hasTranslation.value = true
                }
            } catch (e: Exception) { }
        }
    }

    fun startSseListener(context: Context) {
        if (sseRunning) return
        sseRunning = true
        sseJob?.cancel()
        sseJob = scope.launch {
            kotlinx.coroutines.delay(3000)
            while (isActive && sseRunning) {
                if (!NavidromeManager.checkActiveServers()) { kotlinx.coroutines.delay(5000); continue }
                try {
                    nativeApi.streamPlaybackHandoffEvents({ isActive && sseRunning && NavidromeManager.checkActiveServers() }) { ev -> handleHandoffEvent(ev, context) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                if (isActive && sseRunning) kotlinx.coroutines.delay(5000)
            }
        }
    }

    fun stopSseListener() { sseRunning = false; sseJob?.cancel(); sseJob = null }

    private suspend fun handleHandoffEvent(ev: com.craftworks.music.data.model.PlaybackHandoffEvent, context: Context) {
        val myId = try { myClientId(context) } catch (e: Exception) { null }
        if (myId.isNullOrBlank() || ev.targetSessionId != myId) return
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val ctl = try { com.craftworks.music.player.MediaControllerManager.getInstance(context).controller.value } catch (e: Exception) { null }
                    val p = ctl ?: ChoraMediaLibraryService.getInstance()?.player
                    try { p?.pause() } catch (e: Exception) { }
                } catch (e: Exception) { }
                val taker = ev.newPlayerName?.takeIf { it.isNotBlank() } ?: "其他设备"
                android.widget.Toast.makeText(context, "播放已被「$taker」接管，本地已暂停", android.widget.Toast.LENGTH_SHORT).show()
            }
            try { refreshSessions() } catch (e: Exception) { }
        } catch (e: Exception) { }
    }
}
