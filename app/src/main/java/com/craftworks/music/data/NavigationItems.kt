package com.craftworks.music.data

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Stable
import com.craftworks.music.R
import kotlinx.serialization.Serializable

@Stable
@Serializable
data class BottomNavItem(
    var title: String,
    var icon: Int,
    val screenRoute: String,
    var enabled: Boolean = true
)

/**
 * Single source of truth for the bottom navigation.
 *
 * The default list used to be copy-pasted into five places
 * (`AppearanceSettingsManager.bottomNavItemsFlow`, `MainActivity`'s portrait
 * NavigationBar, `MainActivity`'s TV default, `ChoraDock.DockNavRow` and
 * `AppearanceDialogs`), and the icon `when` mapping into four. They had already
 * drifted: the TV default disabled `songs_screen` while every other copy left it
 * enabled, so TV showed 5 tabs and phones 6. Any future nav change had to be
 * applied five times.
 */
object NavItems {

    const val HOME = "home_screen"
    const val ALBUMS = "album_screen"
    const val SONGS = "songs_screen"
    const val ARTISTS = "artists_screen"
    const val RADIOS = "radio_screen"
    const val PLAYLISTS = "playlist_screen"
    const val NOW_PLAYING = "playing_tv_screen"

    val default: List<BottomNavItem> = listOf(
        BottomNavItem("Home", R.drawable.rounded_home_24, HOME),
        BottomNavItem("Albums", R.drawable.rounded_library_music_24, ALBUMS),
        BottomNavItem("Songs", R.drawable.round_music_note_24, SONGS),
        BottomNavItem("Artists", R.drawable.rounded_artist_24, ARTISTS),
        BottomNavItem("Radios", R.drawable.rounded_radio, RADIOS),
        BottomNavItem("Playlists", R.drawable.placeholder, PLAYLISTS)
    )

    /**
     * Canonical icon for a route. Replaces the four identical `when (screenRoute)`
     * blocks, which all fell back to `R.drawable.placeholder` for unknown routes.
     */
    @DrawableRes
    fun iconFor(route: String): Int = when (route) {
        HOME -> R.drawable.rounded_home_24
        ALBUMS -> R.drawable.rounded_library_music_24
        SONGS -> R.drawable.round_music_note_24
        ARTISTS -> R.drawable.rounded_artist_24
        RADIOS -> R.drawable.rounded_radio
        PLAYLISTS -> R.drawable.placeholder
        NOW_PLAYING -> R.drawable.s_m_playback
        else -> R.drawable.placeholder
    }
}
