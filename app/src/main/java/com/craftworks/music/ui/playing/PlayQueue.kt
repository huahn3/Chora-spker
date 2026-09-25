package com.craftworks.music.ui.playing

import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.craftworks.music.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueBottomSheet(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    mediaController: MediaController?,
    colors: List<Color> = emptyList()
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetHeightFraction = if (isLandscape) 0.85f else 0.44f

    val dominantColor = remember(colors) {
        colors.getOrNull(2) ?: colors.getOrNull(1) ?: colors.firstOrNull() ?: Color(0xFF1E242B)
    }

    val backgroundBrush = remember(dominantColor) {
        val topColor = ColorUtils.blendARGB(dominantColor.toArgb(), Color(0xFF161B22).toArgb(), 0.65f).let { Color(it) }.copy(alpha = 0.96f)
        val bottomColor = ColorUtils.blendARGB(dominantColor.toArgb(), Color(0xFF0D1117).toArgb(), 0.85f).let { Color(it) }.copy(alpha = 0.98f)
        Brush.verticalGradient(listOf(topColor, bottomColor))
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(sheetHeightFraction)
                .background(
                    brush = backgroundBrush,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(Color.White.copy(alpha = 0.35f), CircleShape)
                    )
                }
                PlayQueueContent(
                    mediaController = mediaController,
                    paletteColors = colors,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueContent(
    mediaController: MediaController?,
    paletteColors: List<Color> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (mediaController == null)
        return
    val currentList = remember { mutableStateListOf<MediaItem>() }

    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }

    var currentMediaItem by remember { mutableStateOf(mediaController.currentMediaItem) }

    val haptic = LocalHapticFeedback.current

    DisposableEffect(mediaController) {
        fun syncList() {
            currentList.clear()
            currentList.addAll(
                List(mediaController.mediaItemCount) { i -> mediaController.getMediaItemAt(i) }
            )
        }

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncList()
                currentMediaItem = mediaController.currentMediaItem
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItem = mediaController.currentMediaItem
            }
        }

        // Initial load
        syncList()
        currentMediaItem = mediaController.currentMediaItem
        mediaController.addListener(listener)

        onDispose { mediaController.removeListener(listener) }
    }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(currentMediaItem) {
        if (currentMediaItem in currentList) {
            val targetIdx = currentList.indexOf(currentMediaItem)
            if (targetIdx >= 0) {
                lazyListState.scrollToItem(targetIdx)
            }
        }
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (dragStartIndex == -1) dragStartIndex = from.index
        currentList.add(to.index, currentList.removeAt(from.index))
        dragCurrentIndex = to.index
    }

    val accentColor = remember(paletteColors) {
        paletteColors.firstOrNull() ?: Color(0xFF7FA8DE)
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(currentList, key = { _, item -> item.mediaId }) { index, item ->
            ReorderableItem(
                state = reorderableState,
                key = item.mediaId,
                animateItemModifier = Modifier.animateItem(
                    placementSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)
                )
            ) { draggingThis ->
                val elevation by animateDpAsState(
                    targetValue = if (draggingThis) 6.dp else 0.dp,
                    label = "queue_item_elevation"
                )
                val isCurrentItem = item == currentMediaItem

                Surface(
                    tonalElevation = elevation,
                    shadowElevation = elevation,
                    color = when {
                        draggingThis -> Color.White.copy(alpha = 0.16f)
                        isCurrentItem -> accentColor.copy(alpha = 0.22f)
                        else -> Color.White.copy(alpha = 0.04f)
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = if (isCurrentItem) BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            mediaController.seekTo(index, 0)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCurrentItem && !draggingThis) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Now playing",
                                    tint = accentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.45f)
                                )
                            }
                        }

                        // Title + artist
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = item.mediaMetadata.title?.toString() ?: "Unknown",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrentItem) Color.White else Color.White.copy(alpha = 0.90f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            item.mediaMetadata.artist?.toString()?.let { artist ->
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCurrentItem) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Drag handle
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.baseline_drag_handle_24),
                            contentDescription = null,
                            tint = if (isCurrentItem) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.40f),
                            modifier = Modifier
                                .size(24.dp)
                                .draggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        // Commit to player only if the item actually moved.
                                        if (dragStartIndex != -1 && dragCurrentIndex != -1 &&
                                            dragStartIndex != dragCurrentIndex
                                        ) {
                                            mediaController.moveMediaItem(dragStartIndex, dragCurrentIndex)
                                        }
                                        dragStartIndex = -1
                                        dragCurrentIndex = -1
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}