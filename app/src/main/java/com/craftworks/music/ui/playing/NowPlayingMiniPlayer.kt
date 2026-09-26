package com.craftworks.music.ui.playing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.craftworks.music.R
import com.craftworks.music.data.repository.LyricsState
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import com.craftworks.music.player.ChoraMediaLibraryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class)
@Stable
@Composable
fun NowPlayingMiniPlayer(
    metadata: MediaMetadata? = null,
    active: Boolean = true,
    dragX: Animatable<Float, AnimationVector1D>? = null,
    swipeDir: Int = 0,
    onClick: () -> Unit = { },
    onQueueClick: () -> Unit = { },
    onOutputDeviceClick: () -> Unit = { }
) {
    val service = ChoraMediaLibraryService.getInstance()
    val player = service?.player

    val metaDurationMs = remember(metadata) {
        val ms = metadata?.durationMs ?: 0L
        if (ms > 0L) ms
        else (metadata?.extras?.getLong("duration")?.takeIf { it > 0 }?.times(1000L)) ?: 0L
    }

    var isPlaying by remember { mutableStateOf(player?.isPlaying == true) }
    var currentPosition by remember { mutableLongStateOf(player?.currentPosition?.takeIf { it > 0 } ?: 0L) }
    var duration by remember(player?.duration, metaDurationMs) {
        mutableLongStateOf(
            if ((player?.duration ?: 0L) > 1000L) player!!.duration
            else if (metaDurationMs > 1000L) metaDurationMs
            else 0L
        )
    }

    var showControls by remember { mutableStateOf(true) }
    var lastActionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val context = LocalContext.current
    // Fast-load saved position if player hasn't sought yet on cold start
    LaunchedEffect(player, metadata) {
        if (currentPosition == 0L) {
            val resumption = LocalDataSettingsManager(context)
                .playbackResumptionPlaylistWithStartPosition.firstOrNull()
            if (resumption != null && resumption.startPositionMs > 0L && currentPosition == 0L) {
                currentPosition = resumption.startPositionMs
            }
        }
    }

    // Synchronize player state and events
    DisposableEffect(player, metaDurationMs) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                lastActionTime = System.currentTimeMillis()
            }
            override fun onPlaybackStateChanged(state: Int) {
                val pDuration = player.duration
                if (pDuration > 1000L) {
                    duration = pDuration
                } else if (metaDurationMs > 1000L) {
                    duration = metaDurationMs
                }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val pDuration = player.duration
                if (pDuration > 1000L) {
                    duration = pDuration
                } else if (metaDurationMs > 1000L) {
                    duration = metaDurationMs
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val pDuration = player.duration
                val itemMs = mediaItem?.mediaMetadata?.durationMs ?: 0L
                if (pDuration > 1000L) {
                    duration = pDuration
                } else if (itemMs > 1000L) {
                    duration = itemMs
                } else if (metaDurationMs > 1000L) {
                    duration = metaDurationMs
                }
                currentPosition = player.currentPosition.takeIf { it >= 0L } ?: 0L
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                currentPosition = newPosition.positionMs
                lastActionTime = System.currentTimeMillis()
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        if (player.currentPosition > 0L) {
            currentPosition = player.currentPosition
        }
        val pDuration = player.duration
        if (pDuration > 1000L) {
            duration = pDuration
        } else if (metaDurationMs > 1000L) {
            duration = metaDurationMs
        }
        onDispose {
            player.removeListener(listener)
        }
    }

    // Continuous ticker for progress & synced lyrics while playing.
    // Paused while the dock is slid off-screen (full player open) to stop
    // pointless 2 Hz recompositions behind the player.
    LaunchedEffect(isPlaying, active, player) {
        while (isActive && isPlaying && active && player != null) {
            currentPosition = player.currentPosition
            delay(500L)
        }
    }

    // Auto-hide control icon on cover: shows on start / song change / action, then hides after 3 seconds
    val songId = metadata?.extras?.getString("navidromeID")
    LaunchedEffect(songId, lastActionTime, isPlaying) {
        showControls = true
        delay(3000L)
        showControls = false
    }

    val lyrics by LyricsState.lyrics.collectAsStateWithLifecycle()

    // Calculate current and upcoming lyric
    val activeLyricIndex = remember(lyrics, currentPosition) {
        if (lyrics.isEmpty()) -1
        else lyrics.indexOfLast { it.startMs <= currentPosition }
    }
    val currentLyric = if (activeLyricIndex in lyrics.indices) lyrics[activeLyricIndex] else null
    val currentLyricText = currentLyric?.text?.firstOrNull { it.isNotBlank() }
    val nextLyric = lyrics.getOrNull(activeLyricIndex + 1)
    val nextLyricText = nextLyric?.text?.firstOrNull { it.isNotBlank() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable { onClick.invoke() }
            .padding(horizontal = 12.dp)
    ) {
        // Album Art with Circular Progress & Play/Pause overlay
        // Tap toggles playback, long-press opens the play queue
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        // Tapping cover toggles play/pause even when icon is hidden, resets 3s timer
                        lastActionTime = System.currentTimeMillis()
                        player?.let {
                            if (it.isPlaying) it.pause() else it.play()
                        }
                    },
                    onLongClick = { onQueueClick.invoke() }
                )
        ) {
            // Circular progress ring tracking playback
            val effectiveDuration = when {
                duration > 1000L -> duration
                metaDurationMs > 1000L -> metaDurationMs
                else -> 0L
            }
            val progress = if (effectiveDuration > 0L) {
                (currentPosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                strokeWidth = 2.5.dp
            )

            // Round album cover
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(metadata?.artworkUri)
                    .diskCacheKey(songId)
                    .crossfade(true)
                    .build(),
                contentDescription = "Album Cover",
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
            )

            // Play / Pause Overlay Icon (Visible for 3s on start/action, then fades out smoothly)
            val controlsAlpha by animateFloatAsState(
                targetValue = if (showControls) 1f else 0f,
                animationSpec = tween(300),
                label = "ControlsAlpha"
            )

            if (controlsAlpha > 0.01f) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .graphicsLayer { alpha = controlsAlpha }
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            ImageVector.vectorResource(R.drawable.media3_notification_pause)
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Middle Section: Scrolling lyrics during playback / Title & Artist when paused
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .weight(1f)
                // Horizontal song-swipe: ONLY this text block rides the finger
                // (damped) and fades as it's pulled; cover and buttons stay put.
                .graphicsLayer {
                    dragX?.let { d ->
                        translationX = d.value * 0.45f
                        alpha = (1f - kotlin.math.abs(d.value) / 320f).coerceIn(0.15f, 1f)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isPlaying && !currentLyricText.isNullOrBlank()) {
                // PLAYING WITH SYNCED LYRICS: Smoothly animated rolling lyrics
                AnimatedContent(
                    targetState = currentLyricText,
                    transitionSpec = {
                        (slideInVertically { height -> height / 2 } + fadeIn(tween(250)))
                            .togetherWith(slideOutVertically { height -> -height / 2 } + fadeOut(tween(250)))
                    },
                    label = "MiniPlayerLyric"
                ) { lyric ->
                    Text(
                        text = lyric,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee()
                    )
                }

                val subText = nextLyricText ?: metadata?.artist?.toString() ?: ""
                if (subText.isNotBlank()) {
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee()
                    )
                }
            } else {
                // PAUSED OR NO LYRICS AVAILABLE: Song Title & Artist Info.
                // On a swipe-change the new song's text slides in from the side
                // opposite the swipe direction and crossfades over the old one.
                val artistText = metadata?.artist?.toString() ?: ""
                val yearText = if (metadata?.recordingYear != 0 && metadata?.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION) {
                    " • " + metadata?.recordingYear.toString()
                } else ""
                AnimatedContent(
                    targetState = metadata?.title?.toString().orEmpty() to (artistText + yearText),
                    transitionSpec = {
                        val dir = if (swipeDir == 0) 1 else swipeDir
                        (slideInHorizontally(tween(300)) { w -> -w / 3 * dir } + fadeIn(tween(300)))
                            .togetherWith(
                                slideOutHorizontally(tween(200)) { w -> w / 3 * dir } + fadeOut(tween(200))
                            )
                    },
                    label = "MiniPlayerSongMeta"
                ) { (titleText, subText) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee()
                        )
                        if (subText.isNotBlank()) {
                            Text(
                                text = subText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee()
                            )
                        }
                    }
                }
            }

            // Audio rhythm waveform while playing
            if (isPlaying && active) {
                Spacer(modifier = Modifier.height(2.dp))
                MiniPlayerWaveform(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Right side: Output device (Jukebox) button — same 52dp footprint as
        // the album-art circle on the left, larger icon to match.
        OutputDeviceButton(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 26.dp,
            iconAlpha = 1f,
            chip = true,
            onClick = onOutputDeviceClick
        )
    }
}

// Five staggered bars bouncing in a loop, used as a playback rhythm indicator
@Composable
private fun MiniPlayerWaveform(
    color: Color,
    modifier: Modifier = Modifier
) {
    val baseHeights = listOf(4.dp, 9.dp, 6.dp, 10.dp, 4.dp)
    val delays = listOf(100, 300, 150, 400, 250)
    val transition = rememberInfiniteTransition(label = "waveform")

    Row(
        modifier = modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        baseHeights.forEachIndexed { index, baseHeight ->
            val scale by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 600,
                        delayMillis = delays[index],
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "waveBar$index"
            )

            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(baseHeight * 1.2f)
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}
