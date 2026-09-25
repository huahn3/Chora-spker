@file:OptIn(UnstableApi::class)

package com.craftworks.music.ui.playing

import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.craftworks.music.R
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.player.ChoraMediaLibraryService
import com.gigamole.composefadingedges.marqueeHorizontalFadingEdges
import kotlinx.coroutines.launch

@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
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

    val isRadio = metadata?.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    var showLyricsOptionsSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // UPPER SECTION: HorizontalPager (Cover vs Lyrics) - Swiping only affects this area
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            if (page == 0) {
                // PAGE 0: Album Artwork centered
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = metadata?.artworkUri.toString().replace("size=128", "size=500"),
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
                                .diskCachePolicy(CachePolicy.DISABLED)
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
                // PAGE 1: Dedicated Apple Music Lyrics
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header Bar: Easy Return Button (Chevron + "封面") & Settings Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Return to Cover button - solves "歌词界面很难滑回去" instantly
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(0)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.chevron_down),
                                contentDescription = "Back to Cover",
                                tint = iconTextColor,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { rotationZ = 90f }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "封面",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = iconTextColor
                            )
                        }

                        // Lyrics Settings Button ("可以在歌词界面给个按钮让我任意选择")
                        IconButton(
                            onClick = { showLyricsOptionsSheet = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.rounded_settings_24),
                                tint = iconTextColor.copy(alpha = 0.85f),
                                contentDescription = "Lyrics Settings",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Middle: Apple Music Synced Lyrics View
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LyricsView(
                            color = iconTextColor,
                            isLandscape = false,
                            mediaController = mediaController,
                            onRefreshLyrics = onRefreshLyrics
                        )
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
                        Crossfade(
                            targetState = metadata?.title.toString(),
                            animationSpec = tween(
                                durationMillis = 400,
                                easing = FastOutSlowInEasing
                            ),
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
            Crossfade(
                targetState = metadata?.artist.toString(),
                animationSpec = tween(
                    durationMillis = 400,
                    easing = FastOutSlowInEasing
                ),
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

            // Format, Bitrate, Source info
            if (showMoreInfo && !isRadio) {
                Text(
                    text = buildString {
                        append(metadata?.extras?.getString("format")?.uppercase() ?: "")
                        append(" · ")
                        append(metadata?.extras?.getLong("bitrate") ?: "")
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
                    PlaybackProgressSlider(iconTextColor, mediaController)
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

            // Bottom Action Bar: Download, Sleep Timer, Favorite Heart (Long press: Add to playlist), Queue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .requiredHeightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DownloadButton(
                    iconTextColor,
                    32.dp,
                    metadata,
                    !(metadata?.extras?.getString("navidromeID")?.startsWith("Local_") ?: true)
                )
                SleepTimerButton(iconTextColor, 32.dp, sleepTimerMinutes, onOpenSleepTimer)
                FavoriteHeartButton(
                    color = iconTextColor,
                    size = 32.dp,
                    isStarred = isStarred,
                    onClick = onToggleFavorite,
                    onLongClick = onAddToPlaylist
                )
                PlayQueueButton(iconTextColor, 32.dp, onToggleQueue)
            }
        }
    }

    // Lyrics Customization Sheet ("在歌词界面给个按钮让我任意选择")
    if (showLyricsOptionsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showLyricsOptionsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "歌词显示偏好设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 1. Alignment (Apple Music uses Left)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "排版对齐方式",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = lyricsAlignment == NowPlayingAlignment.LEFT,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setNowPlayingLyricsAlignment(NowPlayingAlignment.LEFT)
                                }
                            },
                            label = { Text("居左 (Apple Music)") }
                        )
                        FilterChip(
                            selected = lyricsAlignment == NowPlayingAlignment.CENTER,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setNowPlayingLyricsAlignment(NowPlayingAlignment.CENTER)
                                }
                            },
                            label = { Text("居中") }
                        )
                        FilterChip(
                            selected = lyricsAlignment == NowPlayingAlignment.RIGHT,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setNowPlayingLyricsAlignment(NowPlayingAlignment.RIGHT)
                                }
                            },
                            label = { Text("居右") }
                        )
                    }
                }

                // 2. Blur Effect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "歌词背景虚化 (Apple Music 景深)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "高亮当前唱词，将非活动行模糊虚化",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useLyricsBlur,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                settingsManager.setNowPlayingLyricsBlur(checked)
                            }
                        }
                    )
                }

                // 3. Scroll Animation Speed
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "歌词动画过渡速度",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = lyricsAnimSpeed == 1200,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setLyricsAnimationSpeed(1200)
                                }
                            },
                            label = { Text("柔和动效 (1.2s)") }
                        )
                        FilterChip(
                            selected = lyricsAnimSpeed == 600,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setLyricsAnimationSpeed(600)
                                }
                            },
                            label = { Text("标准 (0.6s)") }
                        )
                        FilterChip(
                            selected = lyricsAnimSpeed == 250,
                            onClick = {
                                coroutineScope.launch {
                                    settingsManager.setLyricsAnimationSpeed(250)
                                }
                            },
                            label = { Text("快速 (0.25s)") }
                        )
                    }
                }

                // 4. Refresh Lyrics
                Button(
                    onClick = {
                        onRefreshLyrics()
                        showLyricsOptionsSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text("重新检索 / 刷新歌词")
                }
            }
        }
    }
}