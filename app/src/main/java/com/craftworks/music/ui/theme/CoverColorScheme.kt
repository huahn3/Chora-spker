package com.craftworks.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Builds a full Material 3 color scheme from a cover-art palette so every screen
 * follows the current song, mirroring the Now Playing background treatment.
 */
fun buildCoverColorScheme(palette: List<Color>, dark: Boolean): ColorScheme {
    if (palette.isEmpty()) {
        return if (dark) darkColorScheme() else lightColorScheme()
    }

    val seed = palette.first()
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(seed.toArgb(), it) }
    val hue = hsl[0]
    // Clamp saturation so grayscale covers still produce a tasteful (mild) tint.
    val sat = hsl[1].coerceIn(0.18f, 0.85f)
    val accentHue = hue
    val tertiaryHue = (hue + 50f) % 360f

    fun hslColor(h: Float, s: Float, l: Float): Color {
        val argb = ColorUtils.HSLToColor(
            floatArrayOf(
                ((h % 360f) + 360f) % 360f,
                s.coerceIn(0f, 1f),
                l.coerceIn(0f, 1f)
            )
        )
        return Color(argb)
    }

    // Second hue from the palette (if present) keeps schemes from looking monotone.
    val secondaryHue = palette.getOrNull(1)?.let {
        FloatArray(3).also { arr -> ColorUtils.colorToHSL(it.toArgb(), arr) }[0]
    } ?: (hue + 130f) % 360f

    // Tinted neutrals: previously capped too low (0.24), so the dock/popups read as
    // near-gray next to the player's blurred-cover background. Wider range keeps
    // surfaces clearly in the cover's hue without fighting the accents.
    val neutralSat = (sat * 0.55f).coerceIn(0.14f, 0.34f)

    return if (dark) {
        darkColorScheme(
            primary = hslColor(accentHue, sat.coerceAtMost(0.72f), 0.82f),
            onPrimary = hslColor(accentHue, sat * 0.6f, 0.14f),
            primaryContainer = hslColor(accentHue, sat * 0.7f, 0.32f),
            onPrimaryContainer = hslColor(accentHue, sat * 0.5f, 0.93f),
            secondary = hslColor(secondaryHue, sat * 0.55f, 0.80f),
            onSecondary = hslColor(secondaryHue, sat * 0.4f, 0.14f),
            secondaryContainer = hslColor(secondaryHue, sat * 0.45f, 0.30f),
            onSecondaryContainer = hslColor(secondaryHue, sat * 0.35f, 0.92f),
            tertiary = hslColor(tertiaryHue, sat * 0.7f, 0.78f),
            onTertiary = hslColor(tertiaryHue, sat * 0.5f, 0.12f),
            tertiaryContainer = hslColor(tertiaryHue, sat * 0.55f, 0.30f),
            onTertiaryContainer = hslColor(tertiaryHue, sat * 0.45f, 0.92f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = hslColor(accentHue, neutralSat, 0.075f),
            onBackground = hslColor(accentHue, neutralSat, 0.92f),
            surface = hslColor(accentHue, neutralSat, 0.085f),
            onSurface = hslColor(accentHue, neutralSat, 0.92f),
            surfaceVariant = hslColor(accentHue, neutralSat * 1.3f, 0.26f),
            onSurfaceVariant = hslColor(accentHue, neutralSat, 0.80f),
            surfaceContainerLowest = hslColor(accentHue, neutralSat, 0.03f),
            surfaceContainerLow = hslColor(accentHue, neutralSat, 0.115f),
            surfaceContainer = hslColor(accentHue, neutralSat, 0.15f),
            surfaceContainerHigh = hslColor(accentHue, neutralSat, 0.195f),
            surfaceContainerHighest = hslColor(accentHue, neutralSat, 0.24f),
            outline = hslColor(accentHue, neutralSat, 0.58f),
            outlineVariant = hslColor(accentHue, neutralSat, 0.26f),
            inverseSurface = hslColor(accentHue, neutralSat, 0.92f),
            inverseOnSurface = hslColor(accentHue, neutralSat, 0.12f),
            inversePrimary = hslColor(accentHue, sat, 0.42f),
            scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = hslColor(accentHue, sat.coerceAtMost(0.75f), 0.36f),
            onPrimary = Color.White,
            primaryContainer = hslColor(accentHue, sat * 0.75f, 0.90f),
            onPrimaryContainer = hslColor(accentHue, sat * 0.7f, 0.13f),
            secondary = hslColor(secondaryHue, sat * 0.55f, 0.36f),
            onSecondary = Color.White,
            secondaryContainer = hslColor(secondaryHue, sat * 0.6f, 0.90f),
            onSecondaryContainer = hslColor(secondaryHue, sat * 0.45f, 0.13f),
            tertiary = hslColor(tertiaryHue, sat * 0.65f, 0.34f),
            onTertiary = Color.White,
            tertiaryContainer = hslColor(tertiaryHue, sat * 0.6f, 0.90f),
            onTertiaryContainer = hslColor(tertiaryHue, sat * 0.5f, 0.13f),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = hslColor(accentHue, neutralSat, 0.98f),
            onBackground = hslColor(accentHue, neutralSat, 0.10f),
            surface = hslColor(accentHue, neutralSat, 0.97f),
            onSurface = hslColor(accentHue, neutralSat, 0.10f),
            surfaceVariant = hslColor(accentHue, neutralSat * 1.2f, 0.90f),
            onSurfaceVariant = hslColor(accentHue, neutralSat, 0.28f),
            surfaceContainerLowest = hslColor(accentHue, neutralSat, 1.0f),
            surfaceContainerLow = hslColor(accentHue, neutralSat, 0.96f),
            surfaceContainer = hslColor(accentHue, neutralSat, 0.935f),
            surfaceContainerHigh = hslColor(accentHue, neutralSat, 0.915f),
            surfaceContainerHighest = hslColor(accentHue, neutralSat, 0.89f),
            outline = hslColor(accentHue, neutralSat, 0.45f),
            outlineVariant = hslColor(accentHue, neutralSat, 0.80f),
            inverseSurface = hslColor(accentHue, neutralSat, 0.16f),
            inverseOnSurface = hslColor(accentHue, neutralSat, 0.95f),
            inversePrimary = hslColor(accentHue, sat * 0.7f, 0.82f),
        )
    }
}

