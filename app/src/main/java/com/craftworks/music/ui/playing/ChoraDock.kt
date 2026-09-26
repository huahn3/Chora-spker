package com.craftworks.music.ui.playing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.craftworks.music.PlayerSettleSpec
import com.craftworks.music.R
import com.craftworks.music.data.BottomNavItem
import com.craftworks.music.data.NavItems
import com.craftworks.music.data.model.Screen
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import kotlinx.coroutines.launch

/**
 * Unified floating dock: mini player row + gradient divider + icon/dot
 * navigation row, wrapped in one bordered rounded card. Slides away when
 * the full player is expanded. Rendered as a bottom-aligned overlay;
 * [onHeightChanged] hoists the real measured height for content padding.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoraDock(
    navController: NavHostController,
    playerOffset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    metadata: MediaMetadata?,
    viewModel: NowPlayingViewModel,
    modifier: Modifier = Modifier,
    onHeightChanged: (Dp) -> Unit = {},
    onPlayerClick: () -> Unit,
    onQueueLongClick: () -> Unit,
    onOutputDeviceClick: () -> Unit,
    onSwipeNext: () -> Unit = {},
    onSwipePrev: () -> Unit = {}
) {
    // Single source of truth: MainActivity's playerOffset Animatable (0f parked
    // below the screen, 1f expanded). The dock slides out proportionally to the
    // SAME value, so player and dock move as one rigid stack — with the finger
    // during drags, and during the settle spring.
    val density = LocalDensity.current
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val screenHeightPx = remember(density, screenHeightDp) {
        with(density) { screenHeightDp.dp.toPx() }
    }
    val expanded by remember { derivedStateOf { playerOffset.value >= 0.5f } }
    val hasSong = metadata?.title != null

    // Collected HERE (inside the dock's own scope) instead of being passed down from
    // MainActivity: popup toggles must not invalidate the activity-level scope.
    val jukeboxOpen by viewModel.jukeboxDialogOpen.collectAsStateWithLifecycle()
    val queueOpen by viewModel.playQueueOpen.collectAsStateWithLifecycle()
    val paused = jukeboxOpen || queueOpen

    var dockHeightPx by remember { mutableIntStateOf(0) }

    val dockScope = rememberCoroutineScope()

    // Rubber-band shift for the horizontal prev/next swipe on the mini row.
    val swipeX = remember { Animatable(0f) }
    // -1 = swiped left (next song), +1 = swiped right (previous). Drives the
    // direction the new song's text slides in from.
    var swipeDir by remember { mutableIntStateOf(0) }

    val cardShape = RoundedCornerShape(24.dp)

    // The dock is a small, solid, floating card — deliberately NOT tinted by the
    // cover art. Painting it with the palette wash made a light/black-and-white
    // cover turn the dock into a grey slab that fought the page it floated on;
    // the cover now only drives the nav-icon accent and the app-wide ambient
    // background. The card uses the plain surface roles so it stays legible over
    // album art, and separation comes from the shadow + the content fade that
    // MainActivity draws behind it.
    val themeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardSurface = if (themeDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                if (it.height != dockHeightPx) {
                    dockHeightPx = it.height
                    onHeightChanged(with(density) { it.height.toDp() })
                }
            }
            .offset { IntOffset(x = 0, y = (playerOffset.value * dockHeightPx).toInt()) }
            // Fade out during the first ~40% of the rise so the dock dissolves
            // into the player instead of rigidly sliding under it.
            .graphicsLayer { alpha = (1f - playerOffset.value * 2.5f).coerceIn(0f, 1f) }
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 10.dp)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // No shadow, no border: any elevation drawn outside the 24dp
                // radius shows up as a second rounded rectangle around the card,
                // which is the "ugly box under the dock" being reported. The card
                // is only ever surface + radius.
                .clip(cardShape)
                .background(cardSurface)
        ) {
            AnimatedVisibility(visible = hasSong) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Two-axis drag on the mini-player row: horizontal swipes
                        // move the text block with the finger and change track past
                        // a threshold (the new song's text slides/fades in), vertical
                        // drags map 1:1 onto the player's rise (finger-follow).
                        // Release settles by velocity first, then position. Taps
                        // still reach the child buttons because the drag only claims
                        // the gesture past touch slop.
                        // Same reason as MainActivity's overlay: screenHeightPx is
                        // captured here, so it belongs in the key.
                        .pointerInput(playerOffset, screenHeightPx) {
                            var dragVelY = 0f
                            var lastDragTime = 0L
                            var cumDx = 0f
                            var cumDy = 0f
                            var horiz = false
                            val horizLockSlop = with(density) { 10.dp.toPx() }
                            fun settle() {
                                dockScope.launch {
                                    if (horiz) {
                                        val threshold = size.width * 0.18f
                                        when {
                                            cumDx > threshold -> {
                                                swipeDir = 1
                                                onSwipePrev()
                                            }
                                            cumDx < -threshold -> {
                                                swipeDir = -1
                                                onSwipeNext()
                                            }
                                        }
                                    } else {
                                        val target = when {
                                            dragVelY < -700f -> 1f
                                            dragVelY > 700f -> 0f
                                            playerOffset.value > 0.4f -> 1f
                                            else -> 0f
                                        }
                                        playerOffset.animateTo(target, PlayerSettleSpec)
                                    }
                                    swipeX.animateTo(0f, PlayerSettleSpec)
                                }
                            }
                            detectDragGestures(
                                onDragStart = {
                                    dragVelY = 0f
                                    lastDragTime = 0L
                                    cumDx = 0f
                                    cumDy = 0f
                                    horiz = false
                                },
                                onDragEnd = { settle() },
                                onDragCancel = { settle() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val now = change.uptimeMillis
                                    val dt = (now - lastDragTime).coerceAtLeast(1L)
                                    lastDragTime = now
                                    cumDx += dragAmount.x
                                    cumDy += dragAmount.y
                                    if (!horiz && kotlin.math.abs(cumDx) > horizLockSlop &&
                                        kotlin.math.abs(cumDx) > kotlin.math.abs(cumDy) * 0.9f
                                    ) horiz = true
                                    if (horiz) {
                                        // Raw drag distance; the mini player damps
                                        // it and only moves the text block.
                                        val maxShift = with(density) { 140.dp.toPx() }
                                        dockScope.launch {
                                            swipeX.snapTo(cumDx.coerceIn(-maxShift, maxShift))
                                        }
                                    } else {
                                        // px/s with light smoothing for fling detection
                                        dragVelY = dragVelY * 0.6f +
                                                (dragAmount.y / dt * 1000f) * 0.4f
                                        val next = (playerOffset.value - dragAmount.y / screenHeightPx)
                                            .coerceIn(0f, 1f)
                                        dockScope.launch { playerOffset.snapTo(next) }
                                    }
                                }
                            )
                        }
                ) {
                    NowPlayingMiniPlayer(
                        metadata = metadata,
                        active = !expanded && !paused,
                        dragX = swipeX,
                        swipeDir = swipeDir,
                        onClick = onPlayerClick,
                        onQueueClick = onQueueLongClick,
                        onOutputDeviceClick = onOutputDeviceClick
                    )
                }
            }

            AnimatedVisibility(visible = hasSong) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(horizontal = 16.dp)
                        // Solid, theme-neutral line. outlineVariant is derived from
                        // the cover palette (and was drawn as a transparent→color→
                        // transparent gradient), so it turned invisible once the
                        // wash shifted toward a light cover.
                        .background(
                            if (themeDark) Color.White.copy(alpha = 0.24f)
                            else Color.Black.copy(alpha = 0.18f)
                        )
                )
            }

            DockNavRow(navController = navController, playerOffset = playerOffset)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockNavRow(
    navController: NavHostController,
    playerOffset: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Stable manager + stable flow instance: without remember() each recomposition
    // creates a new flow, restarting collection and briefly rendering an empty nav
    // row, which collapsed the dock height (the "dock disappears" symptom).
    val settingsManager = remember(context) { AppearanceSettingsManager(context) }
    val defaultNavItems = remember { NavItems.default }
    val orderedNavItems = settingsManager.bottomNavItemsFlow.collectAsState(
        initial = defaultNavItems
    ).value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        orderedNavItems.forEach { item ->
            if (!item.enabled) return@forEach

            val selected = item.screenRoute == backStackEntry?.destination?.route

            val icon = NavItems.iconFor(item.screenRoute)

            val tintColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                animationSpec = tween(200),
                label = "dockNavTint"
            )
            val pillColor by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                              else Color.Transparent,
                animationSpec = tween(220),
                label = "dockNavPill"
            )
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = tween(220),
                label = "dockNavIconScale"
            )
            val dotScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.4f,
                animationSpec = tween(200),
                label = "dockNavDot"
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (item.screenRoute != backStackEntry?.destination?.route) {
                            navController.navigate(item.screenRoute) {
                                popUpTo(Screen.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        coroutineScope.launch {
                            if (playerOffset.value >= 0.5f)
                                playerOffset.animateTo(0f, PlayerSettleSpec)
                        }
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(width = 44.dp, height = 34.dp)
                ) {
                    // A real 34dp circle. This was `fillMaxWidth()` + 26dp height
                    // clipped to a CircleShape, i.e. a 44x26 ellipse, which is why
                    // the selected tab read as a squashed pill next to the round
                    // glyphs in the row.
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )
                    Icon(
                        imageVector = ImageVector.vectorResource(icon),
                        contentDescription = item.title,
                        tint = tintColor,
                        modifier = Modifier
                            .scale(iconScale)
                            .size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .scale(dotScale)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (selected) 1f else 0f
                            )
                        )
                )
                // Keep reserved space so unselected items don't jump
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}
