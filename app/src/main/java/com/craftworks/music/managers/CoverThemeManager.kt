package com.craftworks.music.managers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the current cover-art color palette.
 * Extracted once per artwork URI (memory + Coil disk cache) and consumed by
 * both the Now Playing background and the app-wide cover-driven theme.
 */
object CoverThemeManager {
    data class CoverPalette(
        val uri: String? = null,
        val colors: List<Color> = emptyList(),
        val isDark: Boolean = false
    )

    private val _state = MutableStateFlow(CoverPalette())
    val state: StateFlow<CoverPalette> = _state.asStateFlow()

    private lateinit var appContext: Context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val memoryCache = LruCache<String, List<Color>>(32)
    private var job: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun update(artworkUri: String?) {
        if (artworkUri == _state.value.uri) return
        job?.cancel()
        if (artworkUri.isNullOrBlank()) {
            _state.value = CoverPalette()
            return
        }
        job = scope.launch {
            memoryCache.get(artworkUri)?.let { cached ->
                _state.value = CoverPalette(artworkUri, cached, isDarkPalette(cached))
                return@launch
            }
            try {
                val colors = extractPalette(artworkUri)
                if (!isActive) return@launch
                memoryCache.put(artworkUri, colors)
                _state.value = CoverPalette(artworkUri, colors, isDarkPalette(colors))
            } catch (e: Exception) {
                Log.w("COVER_THEME", "Palette extraction failed for $artworkUri: ${e.message}")
            }
        }
    }

    private fun isDarkPalette(colors: List<Color>): Boolean {
        if (colors.isEmpty()) return true
        val avg = colors.map { ColorUtils.calculateLuminance(it.toArgb()) }.average()
        return avg <= 0.5f
    }

    private suspend fun extractPalette(artworkUri: String): List<Color> {
        val context: Context = appContext
        val request = ImageRequest.Builder(context)
            .data(artworkUri.replace("size=128", "size=32"))
            .allowHardware(false)
            .build()

        val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
        val bitmap: Bitmap = drawable?.toBitmap() ?: return emptyList()

        return withContext(Dispatchers.Default) {
            val palette = Palette.Builder(bitmap).generate()
            listOfNotNull(
                palette.vibrantSwatch?.rgb?.let { Color(it) },
                palette.darkVibrantSwatch?.rgb?.let { Color(it) },
                palette.dominantSwatch?.rgb?.let { Color(it) },
                palette.lightVibrantSwatch?.rgb?.let { Color(it) },
                palette.mutedSwatch?.rgb?.let { Color(it) },
                palette.lightMutedSwatch?.rgb?.let { Color(it) }
            )
        }
    }
}
