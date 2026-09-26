package com.craftworks.music.ui.playing

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.repository.LyricsRepository
import com.craftworks.music.data.repository.SongRepository
import com.craftworks.music.managers.CoverThemeManager
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.data.repository.StarredRepository
import com.craftworks.music.managers.settings.PlaybackSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class NowPlayingViewModel @Inject constructor (
    @ApplicationContext private val context: Context,
    val songRepository: SongRepository,
    val lyricsRepository: LyricsRepository,
    val appearanceSettingsManager: AppearanceSettingsManager,
    val playbackSettingsManager: PlaybackSettingsManager,
    val starredRepository: StarredRepository,
) : ViewModel() {
    private val _isStarred = MutableStateFlow(false)
    val isStarred = _isStarred.asStateFlow()

    private val _playQueueOpen = MutableStateFlow(false)
    val playQueueOpen = _playQueueOpen.asStateFlow()

    private val _detailsOpen = MutableStateFlow(false)
    val detailsOpen = _detailsOpen.asStateFlow()

    private val _sleepTimerDialogOpen = MutableStateFlow(false)
    val sleepTimerDialogOpen = _sleepTimerDialogOpen.asStateFlow()

    private val _jukeboxDialogOpen = MutableStateFlow(false)
    val jukeboxDialogOpen = _jukeboxDialogOpen.asStateFlow()

    fun setPlayQueueOpen(open: Boolean) { _playQueueOpen.value = open }
    fun setDetailsOpen(open: Boolean) { _detailsOpen.value = open }
    fun setSleepTimerDialogOpen(open: Boolean) { _sleepTimerDialogOpen.value = open }
    fun setJukeboxDialogOpen(open: Boolean) { _jukeboxDialogOpen.value = open }

    fun toggleLyricsTranslation() {
        viewModelScope.launch {
            lyricsRepository.toggleTranslation(context)
        }
    }

    fun forceRetranslateLyrics() {
        viewModelScope.launch {
            lyricsRepository.forceRetranslate(context)
        }
    }

    val backgroundStyle = appearanceSettingsManager.npBackgroundFlow
    val oledProtectionMode = appearanceSettingsManager.oledProtectionMode

    private val _paletteColors = MutableStateFlow<List<Color>>(emptyList())
    val paletteColors = _paletteColors.asStateFlow()

    private val _iconTextColor = MutableStateFlow<Color>(Color.White)
    val iconTextColor = _iconTextColor.asStateFlow()

    private val _isBackgroundDark = MutableStateFlow(false)
    val isBackgroundDark = _isBackgroundDark.asStateFlow()

    private var plainBackground = false
    private var lastBackgroundStyle: NowPlayingBackground = NowPlayingBackground.STATIC_BLUR
    private var lastSystemDark = false

    // "跟随封面配色" master switch: the theme (dock, surfaces) already falls back
    // when it is off, so the player background must follow suit or the two drift.
    private val coverColorEnabled = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            appearanceSettingsManager.coverThemeFlow.collect { enabled ->
                coverColorEnabled.value = enabled
                val cover = CoverThemeManager.state.value
                val follow = enabled && !plainBackground && cover.colors.isNotEmpty()
                applyPalette(
                    colors = if (follow) cover.colors else emptyList(),
                    isDark = if (follow) cover.isDark else lastSystemDark
                )
            }
        }
        viewModelScope.launch {
            CoverThemeManager.state.collect { cover ->
                if (plainBackground || !coverColorEnabled.value || cover.colors.isEmpty()) return@collect
                applyPalette(cover.colors, cover.isDark)
            }
        }
    }

    private fun applyPalette(colors: List<Color>, isDark: Boolean) {
        _paletteColors.value = colors
        _isBackgroundDark.value = isDark
        _iconTextColor.value = iconColorFor(isDark)
    }

    fun refreshLyrics(mediaMetadata: MediaMetadata?) {
        viewModelScope.launch {
            lyricsRepository.getLyrics(mediaMetadata, true)
        }
    }

    fun updateStarredStatus(mediaMetadata: MediaMetadata?) {
        val navId = mediaMetadata?.extras?.getString("navidromeID") ?: ""
        val initialStarred = mediaMetadata?.extras?.getString("starred")?.isNotEmpty() == true
        _isStarred.value = initialStarred

        if (navId.isNotBlank()) {
            viewModelScope.launch {
                try {
                    val starredList = starredRepository.getStarredItems(false)
                    val isSongStarred = starredList.any { item ->
                        item.mediaMetadata.extras?.getString("navidromeID") == navId || item.mediaId == navId
                    }
                    _isStarred.value = isSongStarred
                } catch (_: Exception) {}
            }
        }
    }

    fun toggleStar(mediaMetadata: MediaMetadata?) {
        val navId = mediaMetadata?.extras?.getString("navidromeID") ?: return
        val targetStarred = !_isStarred.value
        _isStarred.value = targetStarred
        viewModelScope.launch {
            try {
                if (targetStarred) {
                    starredRepository.starItem(id = navId, ignoreCachedResponse = true)
                } else {
                    starredRepository.unStarItem(id = navId, ignoreCachedResponse = true)
                }
            } catch (e: Exception) {
                _isStarred.value = !targetStarred
            }
        }
    }

    fun updatePaletteFromUri(uri: Uri?, currentBackgroundStyle: NowPlayingBackground, isSystemDark: Boolean) {
        lastBackgroundStyle = currentBackgroundStyle
        updatePaletteFromUri(uri, isSystemDark)
    }

    /**
     * Style-agnostic refresh: requests extraction and re-applies the palette
     * using the last observed background style. Callers must not disagree on the
     * style — that used to flip [plainBackground] back and forth depending on
     * which LaunchedEffect ran last.
     */
    fun updatePaletteFromUri(uri: Uri?, isSystemDark: Boolean) {
        lastSystemDark = isSystemDark
        CoverThemeManager.update(uri?.toString())

        plainBackground = lastBackgroundStyle == NowPlayingBackground.PLAIN
        if (plainBackground || !coverColorEnabled.value) {
            applyPalette(emptyList(), isSystemDark)
            return
        }

        val cover = CoverThemeManager.state.value
        if (cover.uri == uri?.toString()) {
            applyPalette(
                colors = cover.colors,
                isDark = if (cover.colors.isNotEmpty()) cover.isDark else isSystemDark
            )
        }
    }

    private fun iconColorFor(dark: Boolean): Color =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context).onBackground
            else dynamicLightColorScheme(context).onBackground
        } else {
            if (dark) Color.White
            else Color.Black
        }
}