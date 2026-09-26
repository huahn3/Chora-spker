@file:OptIn(UnstableApi::class)

package com.craftworks.music.ui.playing

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.PagerDefaults
import com.craftworks.music.managers.settings.PageTransitionStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.Wallpapers
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.craftworks.music.R
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.player.ChoraMediaLibraryService
import com.gigamole.composefadingedges.marqueeHorizontalFadingEdges
import kotlinx.coroutines.launch

@kotlin.OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Preview(
    showSystemUi = true, device = "id:pixel_9a",
    wallpaper = Wallpapers.BLUE_DOMINATED_EXAMPLE, showBackground = true
)
@Composable
fun NowPlayingPortrait(
    mediaController: MediaController? = null,
    metadata: MediaMetadata? = null,
    iconColor: Color = Color.White,
    isStarred: Boolean = false,
    sleepTimerMinutes: Int = 10,
    onToggleFavorite: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onToggleQueue: () -> Unit = {},
    onToggleDetails: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onOpenJukebox: () -> Unit = {},
    onToggleTranslation: () -> Unit = {},
    onForceRetranslate: () -> Unit = {},
    onRefreshLyrics: () -> Unit = {}
) {
    val iconTextColor by animateColorAsState(
        targetValue = iconColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow
        ),
        label = "Animated text color"
    )

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { AppearanceSettingsManager(context) }
    val showMoreInfo by settingsManager.showMoreInfoFlow.collectAsStateWithLifecycle(true)
    val titleAlignment by settingsManager.nowPlayingTitleAlignment.collectAsStateWithLifecycle(
        NowPlayingAlignment.LEFT
    )
    val lyricsAlignment by settingsManager.nowPlayingLyricsAlignment.collectAsStateWithLifecycle(
        NowPlayingAlignment.LEFT
    )
    val useLyricsBlur by settingsManager.nowPlayingLyricsBlurFlow.collectAsStateWithLifecycle(true)
    val lyricsAnimSpeed by settingsManager.lyricsAnimationSpeedFlow.collectAsStateWithLifecycle(1200)

    val pageTransitionStyle by settingsManager.pageTransitionStyleFlow.collectAsStateWithLifecycle(
        PageTransitionStyle.ELEGANT_SPRING
    )

    val snapAnimationSpec: AnimationSpec<Float> = remember(pageTransitionStyle) {
        when (pageTransitionStyle) {
            PageTransitionStyle.ELEGANT_SPRING -> tween(
                durationMillis = 280,
                easing = FastOutSlowInEasing
            )
            PageTransitionStyle.CUBIC_BEZIER -> tween(
                durationMillis = 320,
                easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
            )
            PageTransitionStyle.SNAPPY -> tween(
                durationMillis = 200,
                easing = FastOutSlowInEasing
            )
            PageTransitionStyle.GENTLE -> tween(
                durationMillis = 380,
                easing = FastOutSlowInEasing
            )
        }
    }

    val density = LocalDensity.current
    // Swipe-to-change-track on the title block: same follow-the-finger text
    // logic as the dock, but the slide-in direction is mirrored because the
    // title here is left-aligned instead of centered.
    val metaDragX = remember { Animatable(0f) }
    var metaSwipeDir by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    var isFlipping by remember { mutableStateOf(false) }
    val flipRotation = remember { Animatable(0f) }

    fun flipToPage(targetPage: Int) {
        if (isFlipping) return
        isFlipping = true
        coroutineScope.launch {
            val startAngle = if (targetPage == 1) 0f else 180f
            val midAngle = 90f
            val endAngle = if (targetPage == 1) 180f else 0f

            flipRotation.snapTo(startAngle)
            flipRotation.animateTo(
                targetValue = midAngle,
                animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
            )
            pagerState.scrollToPage(targetPage)
            flipRotation.animateTo(
                targetValue = endAngle,
                animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing)
            )
            isFlipping = false
        }
    }

    val isRadio = metadata?.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = snapAnimationSpec,
        snapPositionalThreshold = 0.15f
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // UPPER SECTION: 3D Flip on Click / Smooth Slide on Swipe (Separated & Natural)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    if (isFlipping) {
                        rotationY = flipRotation.value
                        cameraDistance = 18f * density.density
                    }
                }
        ) {
            CompositionLocalProvider(
                androidx.compose.foundation.LocalOverscrollConfiguration provides null,
                androidx.compose.foundation.LocalOverscrollFactory provides null
            ) {
                HorizontalPager(
                    state = pagerState,
                    flingBehavior = flingBehavior,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (isFlipping && page == 1) {
                                rotationY = 180f
                            }
                        }
                ) {
                    if (page == 0) {
                        // PAGE 0: Album Artwork centered - Full original resolution without compression
                        val fullResCoverUri = remember(metadata?.artworkUri) {
                            metadata?.artworkUri?.toString()?.replace(Regex("&size=\\d+"), "") ?: ""
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp, vertical = 8.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    flipToPage(1)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = fullResCoverUri,
                                animationSpec = tween(
                                    durationMillis = 400,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "Crossfade between albums"
                            ) { artworkUri ->
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(artworkUri)
                                        .placeholderMemoryCacheKey(metadata?.artworkUri.toString())
                                        .build(),
                                    contentDescription = "Album Cover Art",
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clip(RoundedCornerShape(24.dp))
                                )
                            }
                        }
                    } else {
                        // PAGE 1: Dedicated Full-screen Lyrics (Return button & Settings removed as requested)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            LyricsView(
                                color = iconTextColor,
                                isLandscape = false,
                                mediaController = mediaController,
                                onRefreshLyrics = onRefreshLyrics,
                                onToggleView = {
                                    flipToPage(0)
                                },
                                onToggleTranslation = onToggleTranslation,
                                onForceRetranslate = onForceRetranslate
                            )
                        }
                    }
                }
            }
        }
    }

        // LOWER SECTION: FIXED / PINNED CONTROLS - NEVER MOVES WHEN SWIPING
        // Raised higher up with padding ("向上拉高一点")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp), // Elevated comfortably
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = metaDragX.value * 0.45f
                        alpha = (1f - kotlin.math.abs(metaDragX.value) / 320f).coerceIn(0.25f, 1f)
                    }
                    .pointerInput(mediaController) {
                        var cumDx = 0f
                        var velX = 0f
                        var lastT = 0L
                        fun settle() {
                            coroutineScope.launch {
                                val threshold = size.width * 0.18f
                                when {
                                    cumDx < -threshold || (cumDx < 0f && velX < -700f) -> {
                                        metaSwipeDir = -1
                                        mediaController?.seekToNext()
                                    }
                                    cumDx > threshold || (cumDx > 0f && velX > 700f) -> {
                                        metaSwipeDir = 1
                                        mediaController?.seekToPrevious()
                                    }
                                }
                                metaDragX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)
                                )
                            }
                        }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                cumDx = 0f
                                velX = 0f
                                lastT = 0L
                            },
                            onDragEnd = { settle() },
                            onDragCancel = { settle() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val now = change.uptimeMillis
                                val dt = (now - lastT).coerceAtLeast(1L)
                                lastT = now
                                cumDx += dragAmount
                                velX = velX * 0.6f + (dragAmount / dt * 1000f) * 0.4f
                                val maxShift = with(density) { 140.dp.toPx() }
                                coroutineScope.launch {
                                    metaDragX.snapTo(cumDx.coerceIn(-maxShift, maxShift))
                                }
                            }
                        )
                    }
            ) {
            // Song Title & Details Button
            CompositionLocalProvider(
                LocalLayoutDirection provides
                        if (titleAlignment == NowPlayingAlignment.RIGHT) LayoutDirection.Rtl
                        else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        AnimatedContent(
                            targetState = metadata?.title.toString(),
                            transitionSpec = {
                                // Mirrored vs the dock: the new title enters from
                                // the side the finger swiped in from.
                                val dir = if (metaSwipeDir == 0) 1 else metaSwipeDir
                                (slideInHorizontally(tween(320)) { w -> w / 3 * dir } + fadeIn(tween(320)))
                                    .togetherWith(
                                        slideOutHorizontally(tween(220)) { w -> -w / 3 * dir } + fadeOut(tween(220))
                                    )
                            },
                            label = "Animated Song Title",
                            modifier = Modifier.weight(1f)
                        ) { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMediumEmphasized,
                                fontWeight = FontWeight.Bold,
                                color = iconTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Visible,
                                softWrap = false,
                                textAlign = when (titleAlignment) {
                                    NowPlayingAlignment.LEFT -> TextAlign.Start
                                    NowPlayingAlignment.CENTER -> TextAlign.Center
                                    NowPlayingAlignment.RIGHT -> TextAlign.End
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .marqueeHorizontalFadingEdges(
                                        marqueeProvider = { Modifier.basicMarquee() }
                                    )
                            )
                        }

                        if (!isRadio) {
                            IconButton(onClick = onToggleDetails) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    tint = iconTextColor.copy(alpha = 0.8f),
                                    contentDescription = "Details"
                                )
                            }
                        }
                    }
                }
            }

            // Artist Info
            AnimatedContent(
                targetState = metadata?.artist.toString(),
                transitionSpec = {
                    val dir = if (metaSwipeDir == 0) 1 else metaSwipeDir
                    (slideInHorizontally(tween(320)) { w -> w / 3 * dir } + fadeIn(tween(320)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { w -> -w / 3 * dir } + fadeOut(tween(220))
                        )
                },
                label = "Animated Artist"
            ) { artistInfo ->
                Text(
                    text = artistInfo,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = iconTextColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = when (titleAlignment) {
                        NowPlayingAlignment.LEFT -> TextAlign.Start
                        NowPlayingAlignment.CENTER -> TextAlign.Center
                        NowPlayingAlignment.RIGHT -> TextAlign.End
                    },
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .marqueeHorizontalFadingEdges(
                            marqueeProvider = { Modifier.basicMarquee() }
                        )
                )
            }
            }

            // Format, Bitrate, Source info
            if (showMoreInfo && !isRadio) {
                Text(
                    text = buildString {
                        append(metadata?.extras?.getString("format")?.uppercase() ?: "")
                        append(" · ")
                        append((metadata?.extras?.get("bitrate") as? Number)?.toString() ?: "")
                        append(" · ")
                        append(
                            if (metadata?.extras?.getString("navidromeID")?.startsWith("Local_") == true)
                                stringResource(R.string.Source_Local)
                            else stringResource(R.string.Source_Navidrome)
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = iconTextColor.copy(alpha = 0.45f),
                    maxLines = 1,
                    textAlign = when (titleAlignment) {
                        NowPlayingAlignment.LEFT -> TextAlign.Start
                        NowPlayingAlignment.CENTER -> TextAlign.Center
                        NowPlayingAlignment.RIGHT -> TextAlign.End
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Progress Slider
            if (!isRadio) {
                Box(Modifier.fillMaxWidth()) {
                    PlaybackProgressSlider(iconTextColor, mediaController, metadata)
                }
            }

            // Playback Controls (Shuffle, Prev, Play/Pause, Next, Repeat)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 80.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChoraMediaLibraryService.getInstance()?.player?.let { player ->
                    ShuffleButton(player, iconTextColor, Modifier.size(24.dp))
                    PreviousSongButton(player, iconTextColor, Modifier.size(40.dp))
                    PlayPauseButton(player, iconTextColor, Modifier.size(80.dp))
                    NextSongButton(player, iconTextColor, Modifier.size(40.dp))
                    RepeatButton(player, iconTextColor, Modifier.size(24.dp))
                }
            }

            // Bottom Action Bar, left → right: queue, favourite, output device,
            // download, sleep timer. (Was download → sleep → output → favourite →
            // queue, which put the two "identity" actions at opposite ends.)
            //
            // Tight centred group, not SpaceEvenly across the full width: 5 × 44dp
            // spread over ~360dp left ~35dp between each pair, so the row read as
            // five loose dots rather than one toolbar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayQueueButton(iconTextColor, 32.dp, onToggleQueue)
                FavoriteHeartButton(
                    color = iconTextColor,
                    size = 32.dp,
                    isStarred = isStarred,
                    onClick = onToggleFavorite,
                    onLongClick = onAddToPlaylist
                )
                OutputDeviceButton(color = iconTextColor, size = 32.dp, onClick = onOpenJukebox)
                DownloadButton(
                    iconTextColor,
                    32.dp,
                    metadata,
                    !(metadata?.extras?.getString("navidromeID")?.startsWith("Local_") ?: true)
                )
                SleepTimerButton(iconTextColor, 32.dp, sleepTimerMinutes, onOpenSleepTimer)
            }
        }
    }
}