package com.craftworks.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Where a [coverWash] is drawn.
 *
 * FULLSCREEN reproduces the Now Playing background geometry pixel for pixel;
 * COMPACT only rescales the radii to the drawing box so short, wide surfaces
 * (dock card, bottom nav bar) show every palette blob instead of being filled
 * by the first one.
 */
enum class CoverWashLayout { FULLSCREEN, COMPACT }

/**
 * Palette → colors animated with the Now Playing 1500ms crossfade, so the dock
 * and the player transition to a new cover's colors in lockstep.
 */
@Composable
fun rememberCoverWash(colors: List<Color>): List<Color> = colors.map { color ->
    animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 1500),
        label = "CoverWash"
    ).value
}

/**
 * Draws the Now Playing treatment: [base] fill, four radial palette blobs, then
 * [overlay]. Apply after a clip so the wash follows the shape, e.g.
 * `.clip(shape).coverWash(...)`.
 */
fun Modifier.coverWash(
    colors: List<Color>,
    layout: CoverWashLayout,
    base: Color,
    overlay: Color = Color.Transparent
): Modifier = drawBehind {
    drawRect(base)

    if (colors.isNotEmpty()) {
        val w = size.width
        val h = size.height

        // Same relative centers as the player background.
        val centers = arrayOf(
            Offset(w * 1.1f, h * 0.1f),
            Offset(w / 5f, h),
            Offset(w * 0.05f, h / 2f),
            Offset(w, h * 0.9f)
        )
        val radii = when (layout) {
            CoverWashLayout.FULLSCREEN -> floatArrayOf(w * 2f, h, h, h)
            CoverWashLayout.COMPACT -> floatArrayOf(w, w * 0.8f, w * 0.7f, w * 0.85f)
        }

        centers.forEachIndexed { index, center ->
            val color = colors.getOrNull(index) ?: colors.firstOrNull()
                ?: return@forEachIndexed
            drawRect(
                Brush.radialGradient(
                    colors = listOf(color, Color.Transparent),
                    center = center,
                    radius = radii[index]
                )
            )
        }
    }

    drawRect(overlay)
}

/**
 * Scrim for wash surfaces whose on-top text color is fixed by the theme
 * (dock/nav icons use `onSurface`, which stays white in dark mode). Palette
 * colors are drawn at full alpha, so a black-and-white cover (e.g. white type
 * on a black sleeve) yields near-white swatches and would leave white text on
 * a white card. Alpha grows with the palette's average luminance so the card
 * always keeps enough contrast, without ever touching the hue.
 */
fun coverWashScrim(colors: List<Color>, themeDark: Boolean): Color {
    val luminance = if (colors.isEmpty()) {
        if (themeDark) 0.0 else 1.0
    } else {
        colors.map { it.luminance() }.average()
    }

    return if (themeDark) {
        val alpha = (0.18 + (luminance - 0.35).coerceAtLeast(0.0) * 0.95)
            .coerceIn(0.18, 0.60)
        Color.Black.copy(alpha = alpha.toFloat())
    } else {
        val alpha = (0.22 + (0.60 - luminance).coerceAtLeast(0.0) * 0.95)
            .coerceIn(0.22, 0.55)
        Color.White.copy(alpha = alpha.toFloat())
    }
}
