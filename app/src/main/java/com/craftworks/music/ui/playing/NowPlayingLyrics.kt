package com.craftworks.music.ui.playing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.repository.LyricsState
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.gigamole.composefadingedges.FadingEdgesGravity
import com.gigamole.composefadingedges.content.FadingEdgesContentType
import com.gigamole.composefadingedges.content.scrollconfig.FadingEdgesScrollConfig
import com.gigamole.composefadingedges.verticalFadingEdges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsView(
    color: Color,
    isLandscape: Boolean = false,
    mediaController: MediaController?,
    paddingValues: PaddingValues = PaddingValues(),
    onRefreshLyrics: () -> Unit = {},
    onToggleView: () -> Unit = {},
) {
    val lyrics by LyricsState.lyrics.collectAsStateWithLifecycle()
    val loading by LyricsState.loading.collectAsStateWithLifecycle()
    var isRefreshing by remember { mutableStateOf(false) }

    val appearanceSettingsManager = AppearanceSettingsManager(LocalContext.current)

    val useBlur by appearanceSettingsManager.nowPlayingLyricsBlurFlow.collectAsState(
        true
    )
    val lyricsAnimationSpeed by appearanceSettingsManager.lyricsAnimationSpeedFlow.collectAsState(
        100
    )
    val lyricsAlignment by appearanceSettingsManager.nowPlayingLyricsAlignment.collectAsStateWithLifecycle(
        NowPlayingAlignment.CENTER
    )
    val lyricsAutoscroll by appearanceSettingsManager.lyricsAutoScroll.collectAsStateWithLifecycle(
        true
    )
    val lyricsRecenter by appearanceSettingsManager.lyricsRecenterAfterScroll.collectAsStateWithLifecycle(
        true
    )

    // State holding the current position
    var currentPosition by remember {
        mutableIntStateOf(mediaController?.currentPosition?.toInt() ?: 0)
    }
    val currentLyricIndex = remember { mutableIntStateOf(-1) }

    val state = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val visibleItemsInfo by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo } }

    var scrollOffset = dpToPx(128)
    val interludeHeight = dpToPx(48)

    var userScrolled by remember { mutableStateOf(false) }
    val isDragged by state.interactionSource.collectIsDraggedAsState()

    var isSeekIndicatorVisible by remember { mutableStateOf(false) }
    var hideSeekIndicatorJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            hideSeekIndicatorJob?.cancel()
            if (lyrics.size > 1) {
                isSeekIndicatorVisible = true
                userScrolled = true
            }
        } else if (isSeekIndicatorVisible) {
            hideSeekIndicatorJob?.cancel()
            hideSeekIndicatorJob = coroutineScope.launch {
                delay(3500)
                isSeekIndicatorVisible = false
                if (lyricsRecenter) {
                    userScrolled = false
                }
            }
        }
    }

    val centeredLyricInfo = remember(state, lyrics) {
        derivedStateOf {
            if (lyrics.isEmpty()) null
            else {
                val layoutInfo = state.layoutInfo
                val viewportCenterY = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val closestItem = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    val itemCenter = item.offset + item.size / 2
                    abs(itemCenter - viewportCenterY)
                }
                closestItem?.let { item ->
                    lyrics.getOrNull(item.index)?.let { lyric ->
                        Pair(item.index, lyric)
                    }
                }
            }
        }
    }

    // Update current position only each lyrics change.
    LaunchedEffect(mediaController, lyrics) {
        var trackingJob: Job = Job()
        val scope = CoroutineScope(Dispatchers.Main)

        if (mediaController?.isPlaying == true) {
            trackingJob = scope.launch {
                var position = mediaController.currentPosition.toInt()
                currentPosition = position

                while (isActive) {
                    position = mediaController.currentPosition.toInt()
                    currentPosition = position
                    delay(getNextUpdateDelay(position, lyrics).milliseconds)
                }
            }
        }

        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) {
                    if (trackingJob.isActive) return

                    trackingJob = scope.launch {
                        var position = mediaController.currentPosition.toInt()
                        currentPosition = position

                        while (isActive) {
                            position = mediaController.currentPosition.toInt()
                            currentPosition = position
                            delay(getNextUpdateDelay(position, lyrics).milliseconds)
                        }
                    }
                } else trackingJob.cancel()
            }
        })
    }

    // Lyric index updates and scrolling
    LaunchedEffect(currentPosition, lyrics) {
        //if (mediaController?.isPlaying == true) {
        val newCurrentLyricIndex =
            lyrics.indexOfFirst { it.startMs > (currentPosition) }
                .takeIf { it >= 0 } ?: lyrics.size

        val targetIndex = (newCurrentLyricIndex - 1).coerceAtLeast(-1)

        if (targetIndex != currentLyricIndex.intValue) {
            currentLyricIndex.intValue = targetIndex

            if (lyricsRecenter || !(!lyricsRecenter && userScrolled)) {
                coroutineScope.launch {
                    val targetItemAfter =
                        state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }

                    if (targetItemAfter != null) {
                        var finalScrollDelta = targetItemAfter.offset - scrollOffset

                        if (lyrics[(targetIndex - 1).coerceAtLeast(0)].text[0] == "")
                            finalScrollDelta -= interludeHeight

                        state.animateScrollBy(
                            value = finalScrollDelta.toFloat(),
                            animationSpec = tween(lyricsAnimationSpeed, 0, FastOutSlowInEasing)
                        )
                    } else
                        state.animateScrollToItem(
                            index = targetIndex.coerceAtLeast(0),
                            scrollOffset = -scrollOffset
                        )
                }
            }
        }
        //}
    }

    // Plain lyrics scrolling
    var plainLyricsViewportHeightPx by remember { mutableFloatStateOf(0f) }
    var plainLyricsItemHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(mediaController, lyrics, plainLyricsItemHeightPx, plainLyricsViewportHeightPx) {
        if (lyrics.size == 1 && lyricsAutoscroll) {
            val updateIntervalMs = 500L

            while (isActive) {
                if (mediaController?.isPlaying == true) {
                    val totalDuration = mediaController.duration
                    val position = mediaController.currentPosition

                    val maxScroll = (plainLyricsItemHeightPx - plainLyricsViewportHeightPx).coerceAtLeast(0f)
                    val currentScrollPx = state.firstVisibleItemScrollOffset.toFloat()

                    val remainingDuration = totalDuration - position
                    val remainingScroll = maxScroll - currentScrollPx

                    if (remainingDuration > 0 && maxScroll > 0f) {
                        val speedPxPerMs = remainingScroll / remainingDuration.toFloat()

                        val delta = speedPxPerMs * updateIntervalMs

                        if (abs(delta) > 0.5f) {
                            launch {
                                state.animateScrollBy(
                                    value = delta,
                                    animationSpec = tween(updateIntervalMs.toInt(), 0, LinearEasing)
                                )
                            }
                        }
                    }
                }
                delay(updateIntervalMs.milliseconds)
            }
        }
    }

    Crossfade(
        loading
    ) {
        if (it) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LoadingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = color
                )
            }
        } else {
            Box(
                modifier = if (isLandscape) {
                    Modifier
                        .widthIn(min = 256.dp)
                        .fillMaxHeight()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                }
            ) {
                var showCopyDialog by remember { mutableStateOf(false) }
                var selectedLyricText by remember { mutableStateOf("") }
                val context = LocalContext.current
                val fullLyricsText = remember(lyrics) {
                    lyrics.mapNotNull { l ->
                        val text = l.words?.joinToString("") { it.text } ?: l.text.joinToString("\n")
                        if (text.isNotBlank()) text else null
                    }.joinToString("\n")
                }

                val onLyricClick: (Int, Lyric) -> Unit = { index, lyric ->
                    mediaController?.seekTo(lyric.startMs.toLong())
                    currentPosition = lyric.startMs
                    userScrolled = true
                    isSeekIndicatorVisible = true
                    hideSeekIndicatorJob?.cancel()
                    hideSeekIndicatorJob = coroutineScope.launch {
                        delay(3500)
                        isSeekIndicatorVisible = false
                        if (lyricsRecenter) {
                            userScrolled = false
                        }
                    }
                    coroutineScope.launch {
                        state.animateScrollToItem(
                            index = index,
                            scrollOffset = -scrollOffset
                        )
                    }
                }

                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(horizontal = 56.dp)
                            .onSizeChanged { size ->
                                scrollOffset = (size.height * 0.2f).toInt()
                                plainLyricsViewportHeightPx = size.height.toFloat()
                            }
                            .verticalFadingEdges(
                                FadingEdgesContentType.Dynamic.Lazy.List(
                                    FadingEdgesScrollConfig.Dynamic(),
                                    state
                                ),
                                FadingEdgesGravity.All,
                                96.dp
                            ),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(top = 100.dp, bottom = 100.dp),
                        state = state,
                    ) {
                        if (lyrics.size > 1) {
                            itemsIndexed(
                                lyrics,
                                key = { index, lyric -> "${index}:${lyric.text}" }
                            ) { index, lyric ->
                                if (!lyric.words.isNullOrEmpty()) {
                                    WordSyncedLyricItem(
                                        lyric = lyric,
                                        index = index,
                                        currentLyricIndex = currentLyricIndex.intValue,
                                        currentPosition = currentPosition,
                                        useBlur = useBlur,
                                        visibleItemsInfo = visibleItemsInfo,
                                        color = color,
                                        lyricsAnimationSpeed = lyricsAnimationSpeed,
                                        onClick = {
                                            onLyricClick(index, lyric)
                                        },
                                        onLongClick = {
                                            selectedLyricText = lyric.words.joinToString("") { it.text }
                                            showCopyDialog = true
                                        }
                                    )
                                } else {
                                    SyncedLyricItem(
                                        lyric = lyric,
                                        index = index,
                                        currentLyricIndex = currentLyricIndex.intValue,
                                        useBlur = useBlur,
                                        visibleItemsInfo = visibleItemsInfo,
                                        color = color,
                                        lyricsAnimationSpeed = lyricsAnimationSpeed,
                                        onClick = {
                                            onLyricClick(index, lyric)
                                        },
                                        onLongClick = {
                                            selectedLyricText = lyric.text.joinToString("\n")
                                            showCopyDialog = true
                                        }
                                    )
                                }
                            }
                        } else if (lyrics.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lyrics[0].text[0],
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = color,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = {
                                                    selectedLyricText = lyrics[0].text[0]
                                                    showCopyDialog = true
                                                }
                                            )
                                            .onSizeChanged { size ->
                                                plainLyricsItemHeightPx = size.height.toFloat()
                                            },
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                if (showCopyDialog) {
                    AlertDialog(
                        onDismissRequest = { showCopyDialog = false },
                        title = { Text(text = "歌词选项", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 350.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Lyric", selectedLyricText))
                                            Toast.makeText(context, "已复制当前行", Toast.LENGTH_SHORT).show()
                                            showCopyDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("复制单行")
                                    }

                                    Button(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("AllLyrics", fullLyricsText))
                                            Toast.makeText(context, "已复制全部歌词", Toast.LENGTH_SHORT).show()
                                            showCopyDialog = false
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("复制全部")
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "也可直接在下方自由长按选择全部歌词：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = fullLyricsText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCopyDialog = false }) {
                                Text("关闭")
                            }
                        }
                    )
                }

                // Dedicated Safe Touch Zones (Left & Right margins for tap to flip and smooth swiping)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(56.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onToggleView()
                        }
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(56.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onToggleView()
                        }
                )

                // NetEase Cloud Music-style center seek line & play button
                AnimatedVisibility(
                    visible = isSeekIndicatorVisible && centeredLyricInfo.value != null,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(400)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    val centeredLyric = centeredLyricInfo.value?.second
                    if (centeredLyric != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatLyricTime(centeredLyric.startMs),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = color.copy(alpha = 0.9f),
                                modifier = Modifier.padding(end = 12.dp)
                            )

                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                thickness = 1.dp,
                                color = color.copy(alpha = 0.35f)
                            )

                            Spacer(Modifier.width(12.dp))

                            IconButton(
                                onClick = {
                                    mediaController?.seekTo(centeredLyric.startMs.toLong())
                                    mediaController?.play()
                                    currentPosition = centeredLyric.startMs
                                    userScrolled = false
                                    isSeekIndicatorVisible = false
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play from this lyric",
                                    tint = color,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordSyncedLyricItem(
    lyric: Lyric,
    index: Int,
    currentLyricIndex: Int,
    currentPosition: Int,
    useBlur: Boolean,
    visibleItemsInfo: List<LazyListItemInfo>,
    color: Color,
    lyricsAnimationSpeed: Int = 1200,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val lyricBlur: Dp by animateDpAsState(
        targetValue = if (useBlur) calculateLyricBlur(
            index, currentLyricIndex, visibleItemsInfo
        ) else 0.dp,
        label = "Lyric Blur",
        animationSpec = tween(lyricsAnimationSpeed, 0, FastOutSlowInEasing)
    )

    val scale by animateFloatAsState(
        targetValue = if (currentLyricIndex == index) 1f else 0.9f,
        label = "Lyric Scale Animation",
        animationSpec = tween(lyricsAnimationSpeed, 0, FastOutSlowInEasing)
    )

    if (lyric.text[0].isEmpty()) {
        AnimatedContent(
            targetState = currentLyricIndex == index
        ) {
            if (it) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .focusable(false)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
                        InterludeIndicator(color)
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .wrapContentWidth()
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .focusable(false)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .blur(lyricBlur),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FlowRow (
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        lyric.words?.forEachIndexed { i, word ->
                            val nextWordStart = lyric.words.getOrNull(i + 1)?.startMs ?: lyric.endMs!!
                            val duration = word.endMs?.let { it - word.startMs } ?: (nextWordStart - word.startMs)
                            val isThisWordActive = currentPosition >= word.startMs && currentPosition < lyric.endMs!!

                            AnimatedWord(
                                wordText = word.text,
                                isActive = isThisWordActive,
                                durationMillis = duration,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }


@Composable
fun AnimatedWord(
    wordText: String,
    isActive: Boolean,
    durationMillis: Int,
    color: Color
) {
    val inactiveColor = color.copy(alpha = 0.4f)
    val wipeProgress = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(1f) }

    val dipAmount = dpToPx(1).toFloat()

    LaunchedEffect(isActive) {
        if (isActive) {
            textAlpha.snapTo(1f)
            wipeProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMillis,
                    easing = LinearEasing
                )
            )
        } else {
            wipeProgress.snapTo(0f)
            textAlpha.animateTo(
                targetValue = 0.4f,
                animationSpec = tween(durationMillis = 400, easing = LinearEasing)
            )
        }
    }

    val brush = if (isActive && wipeProgress.isRunning) {
        val currentOffset = wipeProgress.value * (1f + 0.3f)
        val activeEnd = (currentOffset - 0.3f).coerceIn(0f, 1f)
        val inactiveStart = currentOffset.coerceIn(0f, 1f)
        Brush.horizontalGradient(
            0f to color,
            activeEnd to color,
            inactiveStart to inactiveColor,
            1f to inactiveColor
        )
    } else {
        SolidColor(color)
    }

    val yOffset = remember { Animatable(0f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            yOffset.animateTo(-dipAmount, tween(120, easing = FastOutSlowInEasing))
            yOffset.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
        } else {
            yOffset.animateTo(0f, tween(durationMillis, 0, FastOutSlowInEasing))
        }
    }

    Text(
        text = wordText,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            textMotion = TextMotion.Animated
        ),
        modifier = Modifier
            .graphicsLayer {
                translationY = yOffset.value
                alpha = textAlpha.value
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(
                        brush = brush,
                        blendMode = BlendMode.SrcIn
                    )
                }
            },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncedLyricItem(
    lyric: Lyric,
    index: Int,
    currentLyricIndex: Int,
    useBlur: Boolean,
    visibleItemsInfo: List<LazyListItemInfo>,
    color: Color,
    lyricsAnimationSpeed: Int = 1200,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val lyricAlpha: Float by animateFloatAsState(
        targetValue = if (currentLyricIndex == index) 1f else 0.5f,
        label = "Current Lyric Alpha",
        animationSpec = tween(lyricsAnimationSpeed, 0, FastOutSlowInEasing)
    )

    val lyricBlur: Dp by animateDpAsState(
        targetValue = if (useBlur) calculateLyricBlur(
            index, currentLyricIndex, visibleItemsInfo
        ) else 0.dp,
        label = "Lyric Blur",
        animationSpec = tween(lyricsAnimationSpeed / 2, 0, FastOutSlowInEasing)
    )

    val scale by animateFloatAsState(
        targetValue = if (currentLyricIndex == index) 1f else 0.9f,
        label = "Lyric Scale Animation",
        animationSpec = tween(lyricsAnimationSpeed, 0, FastOutSlowInEasing)
    )

    if (lyric.text[0].isEmpty()) {
        AnimatedContent(
            targetState = currentLyricIndex == index
        ) {
            if (it) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .focusable(false)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    ) {
                        InterludeIndicator(color)
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .wrapContentWidth()
                    .heightIn(min = 42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .focusable(false)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .blur(lyricBlur),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                lyric.text.forEachIndexed { i, line ->
                    Text(
                        text = line,
                        style = if (i == 0) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color.copy(alpha = if (i == 0) lyricAlpha else lyricAlpha * 0.65f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// Bouncing dots for interlude.
@Composable
fun InterludeIndicator(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_master")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Row(
        modifier = modifier
            .height(48.dp)
            .wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(color, phase, 0)
        Dot(color, phase, 1)
        Dot(color, phase, 2)
    }
}

@Composable
fun Dot(color: Color, phase: Float, index: Int) {
    Canvas(modifier = Modifier.size(8.dp)) {
        val offset = index * 0.8f
        val sineValue = sin(phase - offset)
        val yOffset = sineValue * 6f
        val alpha = 0.4f + ((sineValue + 1) / 2) * 0.6f

        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension / 2,
            center = center.copy(y = center.y + yOffset)
        )
    }
}

// Calculate the amount of blur for each lyrics item depending on it's distance to the current lyric.
@Stable
private fun calculateLyricBlur(
    index: Int,
    currentLyricIndex: Int,
    visibleItemsInfo: List<LazyListItemInfo>
): Dp {
    return when {
        index == currentLyricIndex || !visibleItemsInfo.any { it.index == currentLyricIndex } -> 0.dp
        else -> minOf(abs(currentLyricIndex - index).toFloat(), 8f).dp
    }
}

// Calculate next update delay based on lyrics timestamps
private fun getNextUpdateDelay(currentTime: Int, lyrics: List<Lyric>): Long {
    // 1. Gather all future timestamps (both starts and ends) into a single sequence
    val nextTimestamp = lyrics.asSequence()
        .flatMap { lyric ->
            // Build a list of valid timestamps for each lyric item
            val timestamps = mutableListOf(lyric.startMs)
            lyric.endMs?.let { timestamps.add(it) }
            lyric.words?.forEach { word ->
                timestamps.add(word.startMs)
                word.endMs?.let { timestamps.add(it) }
            }
            timestamps
        }
        // 2. Filter for events that happen strictly in the future
        .filter { it > currentTime }
        // 3. Find the nearest upcoming event
        .minOrNull()
        ?: return 1000L // Fallback if we reached the very end of the song

    // 4. Return the precise time remaining until that next event
    return (nextTimestamp - currentTime).toLong()
}

private fun formatLyricTime(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}