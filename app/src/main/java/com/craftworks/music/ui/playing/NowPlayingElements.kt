@file:androidx.annotation.OptIn(UnstableApi::class)

package com.craftworks.music.ui.playing

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.craftworks.music.ui.elements.moveClick
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
    // Fast-load saved position if mediaController hasn't sought yet on cold start
    LaunchedEffect(mediaController, metadata) {
        if (currentValue == 0L) {
            val resumption = LocalDataSettingsManager(context)
                .playbackResumptionPlaylistWithStartPosition.firstOrNull()
            if (resumption != null && resumption.startPositionMs > 0L && currentValue == 0L) {
                currentValue = resumption.startPositionMs
            }
        }
    }

    LaunchedEffect(mediaController, isPlaying) {
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
internal fun PreviousSongButton(player: Player, color: Color, modifier: Modifier = Modifier) {
    val state = rememberPreviousButtonState(player)
    IconButton(onClick = state::onClick, modifier = modifier
        .bounceClick(state.isEnabled)
        .moveClick(false, state.isEnabled), enabled = state.isEnabled) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.media3_notification_seek_to_previous),
            contentDescription = "Previous song",
            modifier = modifier,
            tint = if (state.isEnabled) color else color.copy(0.5f)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayPauseButton(player: Player, color: Color, modifier: Modifier = Modifier) {
    val state = rememberPlayPauseButtonState(player)
    val icon = if (state.showPlay) Icons.Rounded.PlayArrow else ImageVector.vectorResource(R.drawable.media3_notification_pause)
    val contentDescription =
        if (state.showPlay) "play"
        else "pause"

    IconButton(onClick = state::onClick, modifier = modifier.bounceClick(state.isEnabled), enabled = state.isEnabled) {
        Icon(icon, contentDescription = contentDescription, modifier = modifier, tint = if (state.isEnabled) color else color.copy(0.5f))
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
internal fun NextSongButton(player: Player, color: Color, modifier: Modifier = Modifier) {
    val state = rememberNextButtonState(player)
    IconButton(onClick = state::onClick, modifier = modifier
        .bounceClick(state.isEnabled)
        .moveClick(true, state.isEnabled), enabled = state.isEnabled) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.media3_notification_seek_to_next),
            contentDescription = "Next song",
            modifier = modifier,
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

    Box(
        modifier = Modifier
            .size(size + 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
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
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(
                if (isStarred) R.drawable.round_favorite_24
                else R.drawable.round_favorite_border_24
            ),
            contentDescription = if (isStarred) "Unfavorite" else "Favorite",
            tint = if (isStarred) Color(0xFFFF3B5C) else color.copy(alpha = 0.8f),
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
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
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(6.dp),
        modifier = Modifier.bounceClick().size(size + 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = color.copy(0.5f),
            disabledContentColor = color.copy(0.25f)
        )
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.rounded_queue_music_24),
            contentDescription = null,
            modifier = Modifier
                .size(size)
        )
    }
}


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadButton(color: Color, size: Dp, metadata: MediaMetadata?, enabled: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Button(
        onClick = {
            coroutineScope.launch {
                metadata?.let {
                    downloadNavidromeSong(context, it)
                }
            }
        },
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = if (enabled)
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
            contentColor = color.copy(alpha = 0.5f),
            disabledContentColor = color.copy(alpha = 0.25f)
        )
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.rounded_download_24),
            contentDescription = "Download Song",
            modifier = Modifier
                .size(size)
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
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .bounceClick()
                .width(size + 12.dp),
            contentPadding = PaddingValues(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                contentColor = color.copy(alpha = 0.5f),
                disabledContentColor = color.copy(alpha = 0.25f)
            )
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.rounded_timer_24),
                contentDescription = "Sleep timer",
                modifier = Modifier
                    .size(size)
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ShuffleButton(player: Player, color: Color, modifier: Modifier = Modifier) {
    val state = rememberShuffleButtonState(player)
    IconButton(
        onClick = state::onClick,
        modifier = modifier.bounceClick(),
        enabled = state.isEnabled
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.round_shuffle_28),
            contentDescription = "Shuffle",
            modifier = modifier,
            tint = color.copy(if (state.shuffleOn) 1f else 0.5f)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun RepeatButton(player: Player, color: Color, modifier: Modifier = Modifier) {
    val state = rememberRepeatButtonState(player)
    val icon = repeatModeIcon(state.repeatModeState)
    IconButton(
        onClick = state::onClick,
        modifier = modifier.bounceClick(),
        enabled = state.isEnabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Repeat",
            modifier = modifier,
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