package com.craftworks.music.managers.settings

import android.content.Context
import android.util.Log
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.craftworks.music.R
import com.craftworks.music.data.BottomNavItem
import com.craftworks.music.data.NavItems
import com.craftworks.music.dataStore
import com.craftworks.music.ui.playing.NowPlayingAlignment
import com.craftworks.music.ui.playing.NowPlayingBackground
import com.craftworks.music.ui.screens.HomeItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "APPEARANCE_SETTINGS"

enum class OLEDProtectionMode {
    OFF, LYRICS_ONLY, MINIMAL,
}
enum class AppTheme {
    LIGHT, DARK, SYSTEM
}

enum class PageTransitionStyle {
    ELEGANT_SPRING,
    CUBIC_BEZIER,
    SNAPPY,
    GENTLE
}

/**
 * Horizontal placement of the two mini player buttons (album art + output device
 * chip) inside the dock row, so one-handed reach can be tuned per handedness.
 */
enum class MiniPlayerButtonLayout {
    /** Both buttons grouped at the leading edge, text flows after them. */
    LEFT_PAIRED,

    /** Classic: album art pinned left, output chip pinned right. */
    SYMMETRIC,

    /** Both buttons grouped at the trailing edge, text flows before them. */
    RIGHT_PAIRED
}

@Singleton
class AppearanceSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val COVER_THEME = booleanPreferencesKey("cover_theme")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val NP_BACKGROUND_KEY = stringPreferencesKey("np_background_type")
        private val NP_TITLE_ALIGNMENT = stringPreferencesKey("np_title_alignment")
        private val NP_LYRICS_ALIGNMENT = stringPreferencesKey("np_lyrics_alignment")
        private val SHOW_NAVIDROME_KEY = booleanPreferencesKey("show_navidrome_logo")
        private val SHOW_MORE_INFO_KEY = booleanPreferencesKey("show_more_info")
        private val NOW_PLAYING_LYRIC_BLUR_KEY = booleanPreferencesKey("now_playing_lyrics_blur")
        private val BOTTOM_NAV_ITEMS_KEY = stringPreferencesKey("bottom_nav_order")
        private val HOME_ITEMS_KEY = stringPreferencesKey("home_items_order")
        private val APP_THEME = stringPreferencesKey("theme")

        private val SHOW_PROVIDER_DIVIDERS = booleanPreferencesKey("provider_dividers")
        private val LYRICS_ANIMATION_SPEED = intPreferencesKey("lyrics_animation_speed")
        private val LYRICS_AUTOSCROLL = booleanPreferencesKey("lyrics_auto_scroll")
        private val LYRICS_RECENTER_AFTER_SCROLL = booleanPreferencesKey("lyrics_recenter_after_Scroll")
        private val USE_REFRESH_ANIMATION = booleanPreferencesKey("use_refresh_animation")
        private val SHOW_TRACK_NUMBERS = booleanPreferencesKey("show_track_numbers")

        private val OLED_PROTECTION_MODE = stringPreferencesKey("oled_protection")
        private val DISABLE_SCREEN_STANDBY = booleanPreferencesKey("disable_screen_standby")
        private val PAGE_TRANSITION_STYLE = stringPreferencesKey("page_transition_style")
        private val MINI_PLAYER_BUTTON_LAYOUT = stringPreferencesKey("mini_player_button_layout")
    }

    val usernameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY] ?: "Username"
    }.distinctUntilChanged()

    suspend fun setUsername(username: String) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[USERNAME_KEY] = username
            }
        }
    }

    val npBackgroundFlow: Flow<NowPlayingBackground> = context.dataStore.data.map { preferences ->
        try {
            NowPlayingBackground.valueOf(
                preferences[NP_BACKGROUND_KEY]
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        NowPlayingBackground.ANIMATED_BLUR.name
                    else
                        NowPlayingBackground.STATIC_BLUR.name
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode preference", e)
            NowPlayingBackground.STATIC_BLUR
        }
    }.distinctUntilChanged()

    suspend fun setBackgroundType(backgroundType: NowPlayingBackground) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[NP_BACKGROUND_KEY] = backgroundType.name
            }
        }
    }

    val showMoreInfoFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_MORE_INFO_KEY] ?: true
    }.distinctUntilChanged()

    suspend fun setShowMoreInfo(showMoreInfo: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHOW_MORE_INFO_KEY] = showMoreInfo
            }
        }
    }

    val nowPlayingLyricsBlurFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NOW_PLAYING_LYRIC_BLUR_KEY]
            ?: true
    }.distinctUntilChanged()

    suspend fun setNowPlayingLyricsBlur(blur: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[NOW_PLAYING_LYRIC_BLUR_KEY] = blur
            }
        }
    }

    val showNavidromeLogoFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_NAVIDROME_KEY] ?: true
    }.distinctUntilChanged()

    suspend fun setShowNavidromeLogo(showNavidromeLogo: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHOW_NAVIDROME_KEY] = showNavidromeLogo
            }
        }
    }

    val homeItemsItemsFlow: Flow<List<HomeItem>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[HOME_ITEMS_KEY]
        val defaultValue = listOf(
            HomeItem("recently_played", true),
            HomeItem("recently_added", true),
            HomeItem("most_played", true),
            HomeItem("random_songs", true)
        )
        try {
            jsonString?.let { Json.decodeFromString<List<HomeItem>>(it) } ?: defaultValue
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode preference", e)
            defaultValue
        }
    }.distinctUntilChanged()

    suspend fun setHomeItems(items: List<HomeItem>) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[HOME_ITEMS_KEY] = Json.encodeToString(items)
            }
        }
    }

    /**
     * "全局跟随封面配色" master switch. When off, the theme, the ambient wash,
     * the dock/bottom-bar cover wash AND the Now Playing background all stop
     * following the cover art — previously the player kept following it while the
     * dock fell back to the system dynamic color, which looked broken.
     */
    val coverThemeFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[COVER_THEME] ?: true
    }.distinctUntilChanged()

    suspend fun setCoverTheme(enabled: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[COVER_THEME] = enabled
            }
        }
    }

    val bottomNavItemsFlow: Flow<List<BottomNavItem>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[BOTTOM_NAV_ITEMS_KEY]
        val defaultValue = NavItems.default
        try {
            jsonString?.let { Json.decodeFromString<List<BottomNavItem>>(it) } ?: defaultValue
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode preference", e)
            defaultValue
        }
    }.distinctUntilChanged()

    suspend fun setBottomNavItems(items: List<BottomNavItem>) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[BOTTOM_NAV_ITEMS_KEY] = Json.encodeToString(items)
            }
        }
    }

    val appTheme: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[APP_THEME] ?: "SYSTEM"
    }.distinctUntilChanged()

    suspend fun setAppTheme(theme: AppTheme) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[APP_THEME] = theme.name
            }
        }
    }

    val showProviderDividersFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_PROVIDER_DIVIDERS] ?: true
    }.distinctUntilChanged()

    suspend fun setShowProviderDividers(showDividers: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHOW_PROVIDER_DIVIDERS] = showDividers
            }
        }
    }

    val lyricsAnimationSpeedFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[LYRICS_ANIMATION_SPEED] ?: 1200
    }.distinctUntilChanged()

    suspend fun setLyricsAnimationSpeed(speed: Int) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[LYRICS_ANIMATION_SPEED] = speed
            }
        }
    }

    val refreshAnimationFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_REFRESH_ANIMATION]
            ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }.distinctUntilChanged()

    suspend fun setUseRefreshAnimation(useRefreshAnimation: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[USE_REFRESH_ANIMATION] = useRefreshAnimation
            }
        }
    }

    val showTrackNumbersFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_TRACK_NUMBERS] ?: true
    }.distinctUntilChanged()

    suspend fun setShowTrackNumbers(showTrackNumbers: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHOW_TRACK_NUMBERS] = showTrackNumbers
            }
        }
    }

    val nowPlayingTitleAlignment: Flow<NowPlayingAlignment> =
        context.dataStore.data.map { preferences ->
            NowPlayingAlignment.valueOf(
                preferences[NP_TITLE_ALIGNMENT] ?: NowPlayingAlignment.LEFT.name
            )
        }.distinctUntilChanged()

    suspend fun setNowPlayingTitleAlignment(nowPlayingTitleAlignment: NowPlayingAlignment) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[NP_TITLE_ALIGNMENT] = nowPlayingTitleAlignment.name
            }
        }
    }

    val nowPlayingLyricsAlignment: Flow<NowPlayingAlignment> =
        context.dataStore.data.map { preferences ->
            NowPlayingAlignment.valueOf(
                preferences[NP_LYRICS_ALIGNMENT] ?: NowPlayingAlignment.CENTER.name
            )
        }.distinctUntilChanged()

    suspend fun setNowPlayingLyricsAlignment(nowPlayingLyricsAlignment: NowPlayingAlignment) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[NP_LYRICS_ALIGNMENT] = nowPlayingLyricsAlignment.name
            }
        }
    }

    val lyricsAutoScroll: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[LYRICS_AUTOSCROLL] ?: true
        }.distinctUntilChanged()

    suspend fun setLyricsAutoScroll(autoScroll: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[LYRICS_AUTOSCROLL] = autoScroll
            }
        }
    }


    val lyricsRecenterAfterScroll: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[LYRICS_RECENTER_AFTER_SCROLL] ?: true
        }.distinctUntilChanged()

    suspend fun setLyricsRecenterAfterScroll(recenterAfterScroll: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[LYRICS_RECENTER_AFTER_SCROLL] = recenterAfterScroll
            }
        }
    }

    val oledProtectionMode: Flow<OLEDProtectionMode> = context.dataStore.data.map { preferences ->
        try {
            OLEDProtectionMode.valueOf(preferences[OLED_PROTECTION_MODE] ?: "OFF")
        }
        catch (ex: Exception) {
            OLEDProtectionMode.OFF
        }
    }.distinctUntilChanged()

    suspend fun setOledProtectionMode(mode: OLEDProtectionMode) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[OLED_PROTECTION_MODE] = mode.name
            }
        }
    }

    val disableScreenStandby: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DISABLE_SCREEN_STANDBY] ?: false
    }.distinctUntilChanged()

    suspend fun setDisableScreenStandby(enabled: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[DISABLE_SCREEN_STANDBY] = enabled
            }
        }
    }

    val pageTransitionStyleFlow: Flow<PageTransitionStyle> = context.dataStore.data.map { preferences ->
        try {
            PageTransitionStyle.valueOf(
                preferences[PAGE_TRANSITION_STYLE] ?: PageTransitionStyle.ELEGANT_SPRING.name
            )
        } catch (e: Exception) {
            PageTransitionStyle.ELEGANT_SPRING
        }
    }.distinctUntilChanged()

    suspend fun setPageTransitionStyle(style: PageTransitionStyle) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[PAGE_TRANSITION_STYLE] = style.name
            }
        }
    }

    val miniPlayerButtonLayoutFlow: Flow<MiniPlayerButtonLayout> = context.dataStore.data.map { preferences ->
        try {
            MiniPlayerButtonLayout.valueOf(
                preferences[MINI_PLAYER_BUTTON_LAYOUT] ?: MiniPlayerButtonLayout.SYMMETRIC.name
            )
        } catch (e: Exception) {
            MiniPlayerButtonLayout.SYMMETRIC
        }
    }.distinctUntilChanged()

    suspend fun setMiniPlayerButtonLayout(layout: MiniPlayerButtonLayout) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[MINI_PLAYER_BUTTON_LAYOUT] = layout.name
            }
        }
    }
}

/**
 * Remembers one [AppearanceSettingsManager] per composition.
 *
 * The class is a Hilt `@Singleton`, but composables were calling the
 * constructor directly (~120 sites). Every recomposition therefore built a new
 * instance, and because each `val xFlow = dataStore.data.map { ... }` is an
 * *instance* property, `collectAsState(flow)` got a brand-new Flow key on every
 * recomposition: it cancelled and restarted the collection, re-read DataStore
 * and briefly fell back to the `initial` value (visible flicker — the same trap
 * that caused the "dock disappears" incident, which only ChoraDock fixed).
 *
 * Prefer injecting the singleton via Hilt where possible; use this at
 * composition sites that can't.
 */
@Composable
fun rememberAppearanceSettings(): AppearanceSettingsManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppearanceSettingsManager(context) }
}

/** @see rememberAppearanceSettings */
@Composable
fun rememberPlaybackSettings(): PlaybackSettingsManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { PlaybackSettingsManager(context) }
}

/** @see rememberAppearanceSettings */
@Composable
fun rememberMediaProviderSettings(): MediaProviderSettingsManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { MediaProviderSettingsManager(context) }
}

/** @see rememberAppearanceSettings */
@Composable
fun rememberLocalDataSettings(): LocalDataSettingsManager {
    val context = LocalContext.current.applicationContext
    return remember(context) { LocalDataSettingsManager(context) }
}
