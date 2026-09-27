package com.craftworks.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Subtle, screen-wide wash derived from the current cover palette, drawn behind
 * every screen so navigation matches the Now Playing treatment.
 */
@Composable
fun CoverAmbientBackground(
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    if (colors.isEmpty()) return

    val base = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val radius = with(density) { config.screenHeightDp.dp.toPx() } * 0.85f
    val w = with(density) { config.screenWidthDp.dp.toPx() }
    val h = with(density) { config.screenHeightDp.dp.toPx() }

    val c0 = colors[0]
    val c1 = colors.getOrNull(1) ?: c0
    val c2 = colors.getOrNull(2) ?: c1

    // Was four stacked full-screen Boxes, each allocating a radial Brush in the
    // composable body: 4 full-screen overdraw layers plus 4 shader allocations
    // per recomposition, behind EVERY screen. One cached draw pass now.
    val blobBrushes = remember(colors, radius, w, h) {
        listOf(
            Brush.radialGradient(
                colors = listOf(c0.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.12f),
                radius = radius
            ),
            Brush.radialGradient(
                colors = listOf(c1.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * 0.9f, h * 0.45f),
                radius = radius
            ),
            Brush.radialGradient(
                colors = listOf(c2.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(w * 0.4f, h * 1.05f),
                radius = radius
            )
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawWithCache {
                val brushes = blobBrushes
                onDrawBehind { brushes.forEach { drawRect(it) } }
            }
    )
}
