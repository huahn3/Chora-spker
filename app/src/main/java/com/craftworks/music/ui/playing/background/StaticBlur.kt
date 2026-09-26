package com.craftworks.music.ui.playing.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.craftworks.music.ui.theme.CoverWashLayout
import com.craftworks.music.ui.theme.coverWash
import com.craftworks.music.ui.theme.rememberCoverWash

@Stable
@Composable
fun StaticBlurBackground(
    colors: List<Color?>,
    overlayColor: Color = Color.Transparent
) {
    if (colors.isEmpty())
        return

    val resolved = colors.map { it ?: MaterialTheme.colorScheme.background }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .coverWash(
                colors = rememberCoverWash(resolved),
                layout = CoverWashLayout.FULLSCREEN,
                base = MaterialTheme.colorScheme.background,
                overlay = overlayColor
            )
    )
}
