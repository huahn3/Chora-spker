package com.craftworks.music.ui.elements.dialogs

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.craftworks.music.R
import com.craftworks.music.data.model.JukeboxDevice
import com.craftworks.music.data.model.PlaybackSessionDto
import com.craftworks.music.managers.JukeboxManager
import com.craftworks.music.managers.PlaybackHandoffManager
import com.craftworks.music.managers.NavidromeManager
import com.craftworks.music.data.datasource.navidrome.NavidromeNativeApi
import com.craftworks.music.data.datasource.navidrome.canTakeOverSessionOnServer
import com.craftworks.music.player.ChoraMediaLibraryService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JukeboxDeviceBottomSheet(
    mediaController: MediaController?,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val nativeApi = remember { NavidromeNativeApi() }

    val devices by JukeboxManager.devices.collectAsStateWithLifecycle()
    val selectedDeviceId by JukeboxManager.selectedDeviceId.collectAsStateWithLifecycle()
    val isRemoteActive by JukeboxManager.isRemoteActive.collectAsStateWithLifecycle()
    val selectedDevice by JukeboxManager.selectedDevice.collectAsStateWithLifecycle()
    val deviceVolume by JukeboxManager.deviceVolume.collectAsStateWithLifecycle()
    val isLoading by JukeboxManager.isLoading.collectAsStateWithLifecycle()

    // Other devices' live sessions (Playback Handoff). Our own row is filtered
    // out — taking over yourself is a no-op.
    val handoffSessions by PlaybackHandoffManager.sessions.collectAsStateWithLifecycle()
    // The fork only lets an admin take over another user's session. Offering a
    // 接管 button that the server will 403 on is worse than saying why.
    val myIdentity = remember { NavidromeManager.getCurrentServer()?.let { nativeApi.loginIdentity(it) } }
    val takeoverInFlight by PlaybackHandoffManager.takeoverInFlight.collectAsStateWithLifecycle()
    val otherSessions = handoffSessions.filter { !it.isCurrentSession }
        .sortedBy { it.sessionId }

    LaunchedEffect(Unit) {
        // checkActiveServers() + device fetch do disk/network work: keep it off the
        // main thread so the sheet enter animation doesn't stutter.
        withContext(Dispatchers.IO) {
            JukeboxManager.refreshDevices()
            PlaybackHandoffManager.refreshSessions()
        }
        // Live-ish list while the sheet is open; the 12s `playing` heartbeat
        // from each device keeps these rows fresh server-side.
        PlaybackHandoffManager.startPolling(intervalMs = 5_000L)
        // The dock chip reads the same list, so re-arm the ambient poller in
        // case it self-disabled on a server that was offline earlier.
        PlaybackHandoffManager.startAmbientPolling()
    }
    DisposableEffect(Unit) {
        onDispose { PlaybackHandoffManager.stopPolling() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        // heightIn(min): the sheet animates only after its content is measured; a
        // stable first-frame height means it never re-animates when refreshDevices()
        // lands and swaps the (already non-empty, cached) device list.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 340.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.rounded_speaker_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "音频输出设备",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "切换局域网外放或无损串流音箱",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Fixed 20dp slot so the spinner appearing/disappearing never
                // changes the measured sheet height mid-animation.
                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Device List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                devices.forEach { device ->
                    val isSelected = device.id == selectedDeviceId
                    DeviceRowItem(
                        device = device,
                        isSelected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                val currentItem = mediaController?.currentMediaItem
                                val songId = currentItem?.mediaMetadata?.extras?.getString("navidromeID")
                                    ?: ChoraMediaLibraryService.getInstance()?.player?.currentMediaItem?.mediaMetadata?.extras?.getString("navidromeID")
                                val currentPos = mediaController?.currentPosition
                                    ?: ChoraMediaLibraryService.getInstance()?.player?.currentPosition
                                    ?: 0L
                                JukeboxManager.selectDevice(device.id, songId, currentPos, context)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 其他设备正在播放 (Playback Handoff / Takeover)
            if (otherSessions.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "其他设备正在播放",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    otherSessions.forEach { session ->
                        HandoffSessionRow(
                            session = session,
                            isTakingOver = takeoverInFlight == session.sessionId,
                            canTakeOver = canTakeOverSessionOnServer(myIdentity, session.userId),
                            onClick = {
                                val service = ChoraMediaLibraryService.getInstance()
                                val repo = service?.songRepository
                                if (repo == null) {
                                    Toast.makeText(
                                        context,
                                        "播放器尚未就绪，无法接管",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@HandoffSessionRow
                                }
                                PlaybackHandoffManager.takeover(
                                    session = session,
                                    context = context,
                                    songRepository = repo,
                                    forcePlay = session.state == "playing"
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
            }

            // Remote Volume Slider Section (Visible when streaming to a remote device)
            AnimatedVisibility(
                visible = isRemoteActive,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "设备音量 · ${selectedDevice?.name ?: "远端设备"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$deviceVolume%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = deviceVolume.toFloat(),
                        onValueChange = {
                            JukeboxManager.setVolume(it.toInt())
                        },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedDevice?.type == "xiaomi") {
                        Text(
                            text = "小爱音箱原生协议不支持精确拖拽进度条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRowItem(
    device: JukeboxDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                      else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f),
        animationSpec = tween(220),
        label = "deviceRowContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                      else Color.Transparent,
        animationSpec = tween(220),
        label = "deviceRowBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
        animationSpec = tween(220),
        label = "deviceRowIconBg"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "deviceRowCheck"
    )

    val rowShape = RoundedCornerShape(18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(containerColor)
            .border(1.5.dp, borderColor, rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier.weight(1f)
        ) {
            val icon = when (device.type) {
                "browser" -> ImageVector.vectorResource(R.drawable.rounded_phone_24)
                "dlna" -> ImageVector.vectorResource(R.drawable.rounded_cast_24)
                else -> ImageVector.vectorResource(R.drawable.rounded_speaker_24)
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = device.name,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                          else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }

            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (device.type) {
                        "browser" -> "本机输出 · 锁屏与通知栏控制"
                        "mpd" -> "NAS / Linux 本机声卡输出"
                        "dlna" -> "DLNA / UPnP 智能音箱渲染器"
                        "xiaomi" -> "小米小爱音箱协议"
                        else -> "网络音频输出"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        AnimatedVisibility(
            visible = isSelected,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.5f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.5f)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .scale(checkScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * One row of "other devices are playing" in the output-device sheet.
 * Tapping it takes the session over (seek to `positionMs`, inherit the 6
 * dimensions, then let the server pause the victim).
 */
@Composable
private fun HandoffSessionRow(
    session: PlaybackSessionDto,
    isTakingOver: Boolean,
    canTakeOver: Boolean,
    onClick: () -> Unit
) {
    val rowShape = RoundedCornerShape(18.dp)
    val paused = session.state != "playing"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f))
            .border(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                rowShape
            )
            .clickable(enabled = !isTakingOver && canTakeOver, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier.weight(1f)
        ) {
            val rowContext = LocalContext.current
            val coverUrl = session.coverArtUrl
            // Keyed on the asset id, never on coverUrl: the signed URL carries a
            // fresh salt on every poll, so a URL cache key would re-download the
            // same picture every few seconds.
            val coverCacheKey = "handoff_cover_" + (session.coverArtId ?: session.songId)
            // A 404/401 or a self-signed TLS failure must not leave a blank disc
            // behind — fall back to the speaker glyph.
            var coverFailed by remember(coverCacheKey) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null && !coverFailed) {
                    AsyncImage(
                        model = ImageRequest.Builder(rowContext)
                            .data(coverUrl.toUri())
                            .memoryCacheKey(coverCacheKey)
                            .diskCacheKey(coverCacheKey)
                            .crossfade(true)
                            .build(),
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        onError = { coverFailed = true },
                        modifier = Modifier.matchParentSize()
                    )
                } else if (!isTakingOver) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.rounded_speaker_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                if (isTakingOver) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "${session.playerName?.takeIf { it.isNotBlank() } ?: session.username} · ${session.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                val totalSec = session.positionMs / 1000
                Text(
                    text = "%02d:%02d / %02d:%02d  ·  %s".format(
                        totalSec / 60, totalSec % 60,
                        session.duration / 60, session.duration % 60,
                        if (paused) "已暂停" else "正在播放"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                if (session.outputDevice != "browser" && session.outputDevice != "local") {
                    Text(
                        text = "输出: ${session.outputDevice}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Text(
            text = if (canTakeOver) "接管" else "仅管理员可接管",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (canTakeOver) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
