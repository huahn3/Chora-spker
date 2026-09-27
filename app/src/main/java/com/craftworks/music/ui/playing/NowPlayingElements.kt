@file:androidx.annotation.OptIn(UnstableApi::class)

package com.craftworks.music.ui.playing

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craftworks.music.data.model.PlaybackSessionDto
import com.craftworks.music.managers.JukeboxManager
import com.craftworks.music.managers.PlaybackHandoffManager
import com.craftworks.music.player.ChoraMediaLibraryService
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import com.craftworks.music.managers.settings.rememberLocalDataSettings
import kotlinx.coroutines.flow.firstOrNull
import androidx.media3.ui.compose.state.rememberNextButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import androidx.media3.ui.compose.state.rememberRepeatButtonState
import androidx.media3.ui.compose.state.rememberShuffleButtonState
import com.craftworks.music.R
import com.craftworks.music.data.repository.LyricsState
import com.craftworks.music.formatMilliseconds
import com.craftworks.music.providers.navidrome.downloadNavidromeSong
import com.craftworks.music.ui.elements.bounceClick
import com.craftworks.music.ui.elements.pressSlide
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun PlaybackProgressSlider(
    color: Color = MaterialTheme.colorScheme.onBackground,
    mediaController: MediaController? = null,
    metadata: MediaMetadata? = null
) {
    val metaDurationMs = remember(metadata) {
        val ms = metadata?.durationMs ?: 0L
        if (ms > 0L) ms
        else (metadata?.extras?.getLong("duration")?.takeIf { it > 0 }?.times(1000L)) ?: 0L
    }

    var currentValue by remember { mutableLongStateOf(mediaController?.currentPosition?.takeIf { it > 0 } ?: 0L) }
    var currentDuration by remember(mediaController, metaDurationMs) {
        mutableLongStateOf(
            if ((mediaController?.duration ?: 0L) > 1000L) mediaController!!.duration
            else if (metaDurationMs > 1000L) metaDurationMs
            else 0L
        )
    }

    val effectiveDur = when {
        currentDuration > 1000L -> currentDuration
        metaDurationMs > 1000L -> metaDurationMs
        else -> 0L
    }

    val safeMax = if (effectiveDur > 0L) effectiveDur.toFloat() else 1f
    val safeValue = if (effectiveDur > 0L) currentValue.toFloat().coerceIn(0f, safeMax) else 0f

    val animatedValue by animateFloatAsState(
        targetValue = safeValue,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "Smooth Slider Update"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val focused = remember { mutableStateOf(false) }
    var isInteracting by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val localDataSettings = rememberLocalDataSettings()
    // Fast-load saved position if mediaController hasn't sought yet on cold start.
    // Guarded so it can only ever fire once per composition: it used to run on
    // every metadata change, so pausing on a new track snapped the slider to the
    // PREVIOUS session's saved position.
    val resumptionInjected = remember { mutableStateOf(false) }
    LaunchedEffect(mediaController, metadata) {
        if (metadata == null || resumptionInjected.value) return@LaunchedEffect
        resumptionInjected.value = true
        if (currentValue == 0L) {
            val resumption = localDataSettings
                .playbackResumptionPlaylistWithStartPosition.firstOrNull()
            if (resumption != null && resumption.startPositionMs > 0L && currentValue == 0L) {
                currentValue = resumption.startPositionMs
            }
        }
    }

    // isInteracting MUST be part of the key: without it the loop exited on the
    // first drag frame and never restarted, freezing the progress bar until the
    // next play/pause or track change.
    LaunchedEffect(mediaController, isPlaying, isInteracting) {
        if (mediaController != null && isPlaying) {
            while (isActive && !isInteracting) {
                currentValue = mediaController.currentPosition
                delay(1000L)
            }
        } else {
            if (mediaController != null && mediaController.currentPosition > 0L) {
                currentValue = mediaController.currentPosition
            }
        }
    }

    DisposableEffect(mediaController, metaDurationMs) {
        if (mediaController == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                Log.d("TAG", "MediaController isPlaying changed: $playing")
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                val dur = mediaController.duration
                if (dur > 1000L) {
                    currentDuration = dur
                } else if (metaDurationMs > 1000L) {
                    currentDuration = metaDurationMs
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val dur = mediaController.duration
                if (dur > 1000L) {
                    currentDuration = dur
                } else if (metaDurationMs > 1000L) {
                    currentDuration = metaDurationMs
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val dur = mediaController.duration
                val itemDur = mediaItem?.mediaMetadata?.durationMs ?: 0L
                if (dur > 1000L) {
                    currentDuration = dur
                } else if (itemDur > 1000L) {
                    currentDuration = itemDur
                } else if (metaDurationMs > 1000L) {
                    currentDuration = metaDurationMs
                }
                currentValue = mediaController.currentPosition.takeIf { it >= 0L } ?: 0L
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (reason != Player.DISCONTINUITY_REASON_SEEK)
                    currentValue = newPosition.positionMs
            }
        }

        mediaController.addListener(listener)

        isPlaying = mediaController.isPlaying
        if (mediaController.currentPosition > 0L) {
            currentValue = mediaController.currentPosition
        }
        val dur = mediaController.duration
        if (dur > 1000L) {
            currentDuration = dur
        } else if (metaDurationMs > 1000L) {
            currentDuration = metaDurationMs
        }

        onDispose {
            mediaController.removeListener(listener)
        }
    }

    Column(
        Modifier.focusable(false)
    ) {
        Slider(
            enabled = metadata?.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused.value = it.isFocused
                }
                .onKeyEvent { keyEvent ->
                    when {
                        keyEvent.key == Key.DirectionRight && keyEvent.type == KeyEventType.KeyDown -> {
                            currentValue = (currentValue + 5000)
                            mediaController?.seekTo(currentValue)
                            true
                        }

                        keyEvent.key == Key.DirectionLeft && keyEvent.type == KeyEventType.KeyDown -> {
                            currentValue = (currentValue - 5000)
                            mediaController?.seekTo(currentValue)
                            true
                        }

                        else -> false
                    }
                },
            value = if (effectiveDur > 0L) animatedValue.coerceIn(0f, safeMax) else 0f,
            onValueChange = {
                isInteracting = true
                currentValue = it.toLong()
            },
            onValueChangeFinished = {
                isInteracting = false
                mediaController?.seekTo(currentValue)
                // Resync now instead of waiting up to 1s for the restarted ticker
                // (onPositionDiscontinuity deliberately ignores SEEK).
                mediaController?.currentPosition
                    ?.takeIf { it >= 0L }
                    ?.let { currentValue = it }
            },
            valueRange = 0f..safeMax,
            colors = SliderDefaults.colors(
                activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.25f),
                thumbColor = color
            ),
            interactionSource = interactionSource,
        )

        // Time thingies
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(
                text = remember(currentValue) { formatMilliseconds(currentValue.toInt() / 1000) },
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Start,
                color = color.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(64.dp),
                maxLines = 1
            )
            Text(
                text = remember(effectiveDur) {
                    formatMilliseconds(effectiveDur.toInt() / 1000)
                },
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                color = color.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(64.dp),
                maxLines = 1
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun PreviousSongButton(
    player: Player,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ActionButtonIconSize
) {
    val state = rememberPreviousButtonState(player)
    // No press animation on the container: `moveClick` offset it 12 dp on
    // press, which moved the hit-test bounds with it and killed taps on the
    // outer edge. The effect now lives on the Icon (see pressSlide).
    IconButton(onClick = state::onClick, modifier = modifier, enabled = state.isEnabled) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.media3_notification_seek_to_previous),
            contentDescription = "Previous song",
            // NOT `modifier`: the caller sizes the TOUCH TARGET; the glyph gets
            // its own `iconSize`. Sharing one modifier meant the target was
            // pinned to the glyph size (Shuffle/Repeat were 24 dp wide).
            modifier = Modifier
                .size(iconSize)
                .bounceClick(state.isEnabled)
                .pressSlide(right = false, enabled = state.isEnabled),
            tint = if (state.isEnabled) color else color.copy(0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayPauseButton(
    player: Player,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ActionButtonIconSize
) {
    val state = rememberPlayPauseButtonState(player)
    val icon = if (state.showPlay) Icons.Rounded.PlayArrow else ImageVector.vectorResource(R.drawable.media3_notification_pause)
    val contentDescription =
        if (state.showPlay) "play"
        else "pause"

    IconButton(onClick = state::onClick, modifier = modifier, enabled = state.isEnabled) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .bounceClick(state.isEnabled),
            tint = if (state.isEnabled) color else color.copy(0.5f)
        )
    }
//    ToggleButton(
//        checked = state.showPlay,
//        onCheckedChange = { state.onClick() },
//        modifier = modifier,
//        enabled = state.isEnabled
//    ) {
//        Icon(icon,
//            contentDescription = contentDescription,
//            modifier = modifier
//        )
//    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun NextSongButton(
    player: Player,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ActionButtonIconSize
) {
    val state = rememberNextButtonState(player)
    // See PreviousSongButton: the press slide moved the hit target.
    IconButton(onClick = state::onClick, modifier = modifier, enabled = state.isEnabled) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.media3_notification_seek_to_next),
            contentDescription = "Next song",
            modifier = Modifier
                .size(iconSize)
                .bounceClick(state.isEnabled)
                .pressSlide(right = true, enabled = state.isEnabled),
            tint = if (state.isEnabled) color else color.copy(0.5f)
        )
    }
}

@Composable
@Preview
fun LyricsButton(
    color: Color = Color.Black,
    size: Dp = 64.dp,
    isActive: Boolean = false,
    onClick: () -> Unit = {}
){
    val lyrics by LyricsState.lyrics.collectAsStateWithLifecycle()
    val loading by LyricsState.loading.collectAsStateWithLifecycle()

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = // Disable bounce click if no lyrics are present
        if (lyrics.isNotEmpty() || loading)
            Modifier
                .bounceClick()
                .size(size + 12.dp)
        else
            Modifier
                .size(size + 12.dp),
        contentPadding = PaddingValues(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = color.copy(0.5f),
            disabledContentColor = color.copy(0.25f)
        ),
        enabled = lyrics.isNotEmpty() || loading
    ) {
        Crossfade(targetState = isActive && lyrics.isNotEmpty(), label = "Lyrics Icon Crossfade") { open ->
            when (open) {
                true -> Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.lyrics_active),
                    contentDescription = "Close Lyrics",
                    modifier = Modifier
                        .size(size)
                )

                false -> Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.lyrics_inactive),
                    contentDescription = "View Lyrics",
                    modifier = Modifier
                        .size(size)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * Shared geometry + tint for the five Now Playing action buttons.
 *
 * They used to disagree in three ways at once, which is why the row read as
 * "two bright buttons and three grey ones":
 *  - touch target: `size + 16.dp` (heart) vs `size + 12.dp` (rest) vs
 *    `width(size + 12.dp)` with intrinsic height (sleep timer)
 *  - tint: 0.8f (heart), 0.75f (output device), 0.5f (the rest), plus a
 *    hard-coded `0xFFFF3B5C` red for a favourited track and `colorScheme.primary`
 *    whenever a remote output was active
 *  - press feedback: only the heart lacked `bounceClick()`
 */
private val ActionButtonBoxSize = 44.dp
private val ActionButtonIconSize = 24.dp
private val CHIP_ICON_SIZE = 21.dp
private const val ActionButtonAlpha = 0.62f
private const val ActionButtonDisabledAlpha = 0.28f

/**
 * Identical container for the five Now Playing action buttons.
 *
 * Sizing the `Icon` to the same dp value is not enough: the five glyphs are
 * different vectors whose ink covers very different fractions of their viewport
 * (`rounded_phone_24` fills only 58% of its width while
 * `round_favorite_border_24` fills ~87%), so an equal box still rendered a fat
 * heart next to a skinny phone. A shared circular plate plus a per-glyph optical
 * scale is what actually makes the row read as one set.
 */
@Composable
private fun ActionButtonSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val themeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val plate = when {
        !enabled -> Color.Transparent
        themeDark -> Color.White.copy(alpha = 0.07f)
        else -> Color.Black.copy(alpha = 0.05f)
    }

    Box(
        modifier = modifier
            .size(ActionButtonBoxSize)
            .clip(CircleShape)
            .background(plate)
            .bounceClick(enabled = enabled)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun FavoriteHeartButton(
    color: Color = Color.White,
    size: Dp = 32.dp,
    isStarred: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    ActionButtonSurface(
        onClick = {
            scope.launch {
                scale.animateTo(0.75f, tween(80))
                scale.animateTo(1.25f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                scale.animateTo(1f, tween(100))
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        }
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(
                if (isStarred) R.drawable.round_favorite_24
                else R.drawable.round_favorite_border_24
            ),
            contentDescription = if (isStarred) "Unfavorite" else "Favorite",
            tint = color.copy(alpha = ActionButtonAlpha),
            modifier = Modifier
                .size(ActionButtonIconSize)
                .graphicsLayer {
                    // The heart is the widest glyph of the five, so it is shrunk
                    // to match the others optically; the favourited state is
                    // carried by the filled-vs-outline glyph plus a whisper of
                    // scale, not by a different colour.
                    // measured 83% x 74% of its 24dp viewport: the widest glyph of
                    // the five, so it is the reference the others are matched to.
                    val base = 1f * if (isStarred) 1.05f else 1f
                    val pulse = scale.value
                    scaleX = base * pulse
                    scaleY = base * pulse
                }
        )
    }
}

@Composable
fun PlayQueueButton(
    color: Color = Color.Black,
    size: Dp = 64.dp,
    onClick: () -> Unit = {}
){
    ActionButtonSurface(onClick = onClick) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.rounded_queue_music_24),
            contentDescription = null,
            // Without an explicit tint this Icon inherited LocalContentColor and
            // rendered black while its four neighbours used `color`.
            tint = color.copy(alpha = ActionButtonAlpha),
            modifier = Modifier
                .size(ActionButtonIconSize)
                // 79% x 58%: wide and flat, so nudge up to match the others.
                .graphicsLayer {
                    val s = 1.06f
                    scaleX = s
                    scaleY = s
                }
        )
    }
}

@Composable
fun OutputDeviceButton(
    color: Color = Color.Black,
    size: Dp = 32.dp,
    enabled: Boolean = true,
    iconAlpha: Float = 0.75f,
    chip: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val isRemoteActive by JukeboxManager.isRemoteActive.collectAsStateWithLifecycle()
    val selectedDevice by JukeboxManager.selectedDevice.collectAsStateWithLifecycle()

    val icon = when (selectedDevice?.type) {
        "browser" -> ImageVector.vectorResource(R.drawable.rounded_phone_24)
        "dlna" -> ImageVector.vectorResource(R.drawable.rounded_cast_24)
        else -> ImageVector.vectorResource(R.drawable.rounded_speaker_24)
    }

    if (chip) {
        // The dock chip is its own thing (ringed disc + handoff progress) and is
        // not part of the action row.
        OutputDeviceChip(
            icon = icon,
            color = color,
            enabled = enabled,
            isRemoteActive = isRemoteActive,
            modifier = modifier,
            onClick = onClick
        )
        return
    }

    ActionButtonSurface(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = "Output Device",
            modifier = Modifier
                .size(ActionButtonIconSize)
                // The phone/speaker glyphs are the narrowest of the five (the
                // phone fills only ~58% of its viewport width), so they are
                // scaled up to reach the same optical weight.
                .graphicsLayer {
                    // phone = 58% x 92% of its viewport, so at 1.0 it renders a
                    // 12.7dp-wide sliver next to a 20dp heart. 1.35x brings the ink
                    // to ~17dp wide, matching the rest.
                    val s = 1.35f
                    scaleX = s
                    scaleY = s
                },
            tint = color.copy(alpha = ActionButtonAlpha)
        )
    }
}

/**
 * Dock output-device chip. Mirrors the album-art button's exact geometry — a
 * 48dp / 2.5dp progress ring wrapping a 42dp content disc, both inside the same
 * 52dp footprint — but stays deliberately quieter than the cover: a soft
 * top-lit face instead of a flat fill, and a 21dp glyph on the nav row's tint
 * scale. The cover stays the only saturated circle in the row.
 *
 * The ring is a status display for "audio is living somewhere else":
 * - our own stream pushed to a Jukebox speaker -> full ring
 * - another device is playing -> that session's latest position
 * - nobody -> empty track
 *
 * Tap opens the output-device sheet; long-press takes the other device's
 * session over (same path as the sheet's 接管 row).
 */
@Composable
private fun OutputDeviceChip(
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    isRemoteActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val otherSession by PlaybackHandoffManager.latestOtherSession.collectAsStateWithLifecycle()
    val takeoverInFlight by PlaybackHandoffManager.takeoverInFlight.collectAsStateWithLifecycle()
    val handoffProgress = rememberHandoffProgress(otherSession)

    val isTakingOver = takeoverInFlight != null
    // Either signal means "not playing out of this phone".
    val isRemote = isRemoteActive || otherSession != null

    // Colour/metrics borrowed from `DockNavRow` on purpose (21dp glyph, 0.65
    // onSurfaceVariant idle tint, primary when live) so the chip reads as part of
    // the dock instead of a third floating element between two hero circles.
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
    val ringTrack = if (isRemote) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    // A top-lit button face. `surfaceContainerHighest` is theme-relative, so the
    // same two alphas read as a soft raised disc in dark and a soft tinted one in
    // light — no flat blob that fights the album art.
    val faceBrush = if (isRemote) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.42f),
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.10f)
            )
        )
    }
    val iconTint = if (isRemote) MaterialTheme.colorScheme.primary
    else color.copy(alpha = 0.65f)

    val onLongClick: () -> Unit = {
        val session = otherSession
        when {
            session == null -> Toast.makeText(context, "其他设备当前没有播放", Toast.LENGTH_SHORT).show()
            isTakingOver -> Unit
            else -> {
                val repo = ChoraMediaLibraryService.getInstance()?.songRepository
                if (repo == null) {
                    Toast.makeText(context, "播放器尚未就绪，无法接管", Toast.LENGTH_SHORT).show()
                } else {
                    PlaybackHandoffManager.takeover(
                        session = session,
                        context = context,
                        songRepository = repo,
                        forcePlay = session.state == "playing"
                    )
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .bounceClick(enabled = enabled)
            .size(52.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClickLabel = "接管其他设备播放",
                onLongClick = onLongClick
            )
    ) {
        // Literally the same indicator the cover uses, so both rings in the dock
        // share geometry, stroke and sweep direction. Another device's progress
        // wins over our own Jukebox state — it's the newer signal.
        CircularProgressIndicator(
            progress = {
                when {
                    otherSession != null -> handoffProgress
                    isRemoteActive -> 1f
                    else -> 0f
                }
            },
            modifier = Modifier.size(48.dp),
            color = ringColor,
            trackColor = ringTrack,
            strokeWidth = 2.5.dp
        )

        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(faceBrush)
        )

        // When another device is playing, paint its cover into the same 42dp disc
        // the cover button uses. The two circles then read as one matched pair
        // ("art + ring") instead of a photo next to an empty socket, and the chip
        // doubles as a preview of what a long-press would take over.
        val coverUrl = otherSession?.coverArtUrl
        // Keyed on the asset id, never the URL: the signed URL carries a fresh
        // salt on every poll, so a URL cache key would re-download every 10s.
        val artCacheKey = otherSession?.let { "chip_handoff_cover_" + (it.coverArtId ?: it.songId) }
        var artFailed by remember(artCacheKey) { mutableStateOf(false) }
        val chipArtRequest = remember(coverUrl, artCacheKey) {
            coverUrl?.let {
                ImageRequest.Builder(context)
                    .data(it.toUri())
                    .memoryCacheKey(artCacheKey)
                    .diskCacheKey(artCacheKey)
                    .crossfade(true)
                    .build()
            }
        }
        if (chipArtRequest != null && !artFailed) {
            AsyncImage(
                model = chipArtRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { artFailed = true },
                modifier = Modifier.size(42.dp).clip(CircleShape)
            )
            // Byte-for-byte the cover button's play/pause scrim, so the glyph
            // reads with the same contrast over arbitrary artwork.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        if (isTakingOver) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = if (coverUrl != null && !artFailed) Color.White
                else MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = "Output Device",
                modifier = Modifier
                    .size(CHIP_ICON_SIZE)
                    // The phone/speaker glyphs are the narrowest of the three, so
                    // widen them slightly to reach optical parity with the glyphs
                    // in the nav row below (which are plain 21dp).
                    .graphicsLayer { scaleX = 1.15f },
                tint = if (coverUrl != null && !artFailed) Color.White else iconTint
            )
        }
    }
}

/**
 * Turns a remote session's polled `positionMs` into a 0..1 fraction that keeps
 * creeping forward between polls. The ambient poll is 10s, so without this the
 * ring would sit frozen and read as "stalled" rather than "playing over there".
 * Re-anchors whenever the server reports a new position.
 */
@Composable
private fun rememberHandoffProgress(session: PlaybackSessionDto?): Float {
    val durationMs = (session?.duration ?: 0) * 1000L
    if (session == null || durationMs <= 0L) return 0f

    val sessionId = session.sessionId
    var basePosition by remember(sessionId) { mutableLongStateOf(session.positionMs) }
    var baseElapsed by remember(sessionId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var progress by remember(sessionId) { mutableFloatStateOf(0f) }

    LaunchedEffect(sessionId, session.positionMs) {
        basePosition = session.positionMs
        baseElapsed = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(sessionId, durationMs, session.state) {
        val playing = session.state == "playing"
        // A paused remote session can never advance, so writing `progress` twice
        // a second just invalidated the chip for nothing. Idle instead.
        if (!playing) {
            progress = (basePosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            return@LaunchedEffect
        }
        while (true) {
            val position = basePosition + (SystemClock.elapsedRealtime() - baseElapsed)
            progress = (position.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            delay(500L)
        }
    }
    return progress
}


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadButton(color: Color, size: Dp, metadata: MediaMetadata?, enabled: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    ActionButtonSurface(
        onClick = {
            coroutineScope.launch {
                metadata?.let {
                    downloadNavidromeSong(context, it)
                }
            }
        },
        enabled = enabled
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.rounded_download_24),
            contentDescription = "Download Song",
            modifier = Modifier
                .size(ActionButtonIconSize)
                // The download arrow covers less of its 960-unit viewport than
                // the others do of their 24-unit ones; nudge it up to match.
                .graphicsLayer {
                    // 67% x 67% coverage; 1.12x takes the ink to ~21dp.
                    val s = 1.12f
                    scaleX = s
                    scaleY = s
                },
            tint = color.copy(alpha = if (enabled) ActionButtonAlpha else ActionButtonDisabledAlpha)
        )
    }
}

@Composable
fun SleepTimerButton(
    color: Color,
    size: Dp,
    sleepTimerMinutes: Int,
    onClick: () -> Unit = {}
) {
    BadgedBox(
        badge = {
            if (sleepTimerMinutes > 0) {
                val formattedMinutes: String = if (sleepTimerMinutes > 60)
                    "${sleepTimerMinutes / 60}h"
                else
                    "${sleepTimerMinutes}m"

                Badge {
                    Text(formattedMinutes)
                }
            }
        }
    ) {
        ActionButtonSurface(onClick = onClick) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.rounded_timer_24),
                contentDescription = "Sleep timer",
                modifier = Modifier.size(ActionButtonIconSize),
                tint = color.copy(alpha = ActionButtonAlpha)
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ShuffleButton(
    player: Player,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ActionButtonIconSize
) {
    val state = rememberShuffleButtonState(player)
    IconButton(
        onClick = state::onClick,
        modifier = modifier,
        enabled = state.isEnabled
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.round_shuffle_28),
            contentDescription = "Shuffle",
            modifier = Modifier
                .size(iconSize)
                .bounceClick(state.isEnabled),
            tint = color.copy(if (state.shuffleOn) 1f else 0.5f)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun RepeatButton(
    player: Player,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ActionButtonIconSize
) {
    val state = rememberRepeatButtonState(player)
    val icon = repeatModeIcon(state.repeatModeState)
    IconButton(
        onClick = state::onClick,
        modifier = modifier,
        enabled = state.isEnabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Repeat",
            modifier = Modifier
                .size(iconSize)
                .bounceClick(state.isEnabled),
            tint = color.copy(if (state.repeatModeState == Player.REPEAT_MODE_OFF) 0.5f else 1f)
        )
    }
}
@Composable
private fun repeatModeIcon(repeatMode: @Player.RepeatMode Int): ImageVector {
    return when (repeatMode) {
        Player.REPEAT_MODE_OFF -> ImageVector.vectorResource(R.drawable.rounded_repeat_24)
        Player.REPEAT_MODE_ONE -> ImageVector.vectorResource(R.drawable.rounded_repeat1_24)
        else -> ImageVector.vectorResource(R.drawable.rounded_repeat_24)
    }
}