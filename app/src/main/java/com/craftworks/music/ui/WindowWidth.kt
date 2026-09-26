package com.craftworks.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one and only window-width breakpoint.
 *
 * It used to be re-derived in three places with three different comparisons
 * (`<`, `>`, `<`) on `LocalWindowInfo.containerSize.width`: the dock-vs-navbar
 * choice, the "show the Playing entry in the bottom bar" toggle, and the
 * "bounce the user off the landscape player route" guard. At exactly 640dp the
 * first said "not compact" (no dock) while the second said "not > 640" (no
 * Playing entry), so the player screen was unreachable.
 */
val WIDE_LAYOUT_BREAKPOINT: Dp = 640.dp

/** True for phone-width windows, which get the floating ChoraDock. */
@Composable
fun isCompactDockLayout(): Boolean =
    LocalWindowInfo.current.containerSize.width < with(LocalDensity.current) {
        WIDE_LAYOUT_BREAKPOINT.toPx()
    }

/** True once the window is wide enough to also expose the Playing shortcut. */
@Composable
fun isWideLayout(): Boolean =
    LocalWindowInfo.current.containerSize.width >= with(LocalDensity.current) {
        WIDE_LAYOUT_BREAKPOINT.toPx()
    }
