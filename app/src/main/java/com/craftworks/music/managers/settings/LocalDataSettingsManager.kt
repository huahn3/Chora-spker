package com.craftworks.music.managers.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.craftworks.music.data.model.MediaData
import com.craftworks.music.data.model.SortOrder
import com.craftworks.music.data.model.toMediaItem
import com.craftworks.music.data.model.toSong
import com.craftworks.music.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDataSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val LOCAL_RADIOS = stringPreferencesKey("radios_list")
        private val LOCAL_PLAYLISTS = stringPreferencesKey("playlists_list")

        private val MEDIA_RESUMPTION_PLAYLIST = stringPreferencesKey("media_resumption_playlist")
        private val MEDIA_RESUMPTION_INDEX = intPreferencesKey("media_resumption_index")
        private val MEDIA_RESUMPTION_TIME = longPreferencesKey("media_resumption_timestamp")

        private val SORT_ALBUM_ORDER = stringPreferencesKey("sort_album_order")
        private val SHOW_FAVORITES_ONLY = booleanPreferencesKey("show_favorites_only")
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    val localRadios: Flow<MutableList<MediaData.Radio>> =
        context.dataStore.data.map { preferences ->
            try {
                json.decodeFromString<List<MediaData.Radio>>(preferences[LOCAL_RADIOS] ?: "[]")
                    .toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        }.distinctUntilChanged()

    suspend fun saveLocalRadios(radios: List<MediaData.Radio>) {
        withContext(NonCancellable) {
            try {
                val radiosListJson =
                    json.encodeToString(radios.filter { it.navidromeID.startsWith("Local_") })
                context.dataStore.edit { preferences ->
                    preferences[LOCAL_RADIOS] = radiosListJson
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    val localPlaylists: Flow<MutableList<MediaData.Playlist>> =
        context.dataStore.data.map { preferences ->
            try {
                json.decodeFromString<List<MediaData.Playlist>>(preferences[LOCAL_PLAYLISTS] ?: "[]")
                    .toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        }.distinctUntilChanged()

    suspend fun saveLocalPlaylists(playlists: List<MediaData.Playlist>) {
        withContext(NonCancellable) {
            try {
                val playlistJson =
                    json.encodeToString(playlists.filter { it.navidromeID.startsWith("Local_") })
                context.dataStore.edit { preferences ->
                    preferences[LOCAL_PLAYLISTS] = playlistJson
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @UnstableApi
    suspend fun setPlaybackResumption(playlist: List<MediaItem>, currentPos: Int, currentTime: Long) {
        if (playlist.isEmpty()) return
        withContext(NonCancellable) {
            try {
                val songsToSave = if (playlist.size > 100) {
                    val start = (currentPos - 50).coerceAtLeast(0)
                    val end = (currentPos + 50).coerceAtMost(playlist.size)
                    playlist.subList(start, end).map { it.toSong() }
                } else {
                    playlist.map { it.toSong() }
                }
                val adjustedIndex = if (playlist.size > 100) {
                    val start = (currentPos - 50).coerceAtLeast(0)
                    (currentPos - start).coerceIn(0, songsToSave.size - 1)
                } else {
                    currentPos.coerceIn(0, songsToSave.size - 1)
                }

                val encoded = json.encodeToString(songsToSave)
                context.dataStore.edit { preferences ->
                    preferences[MEDIA_RESUMPTION_PLAYLIST] = encoded
                    preferences[MEDIA_RESUMPTION_INDEX] = adjustedIndex
                    preferences[MEDIA_RESUMPTION_TIME] = currentTime
                }
            } catch (e: Exception) {
                android.util.Log.e("DATASTORE", "Failed to save playback resumption", e)
            }
        }
    }

    /**
     * Updates only the resume timestamp. Used by the 1s playback tick so a
     * process kill mid-song still resumes at the right position, without
     * re-encoding (and re-writing) the whole playlist to DataStore every 10s.
     */
    suspend fun setPlaybackResumptionPosition(currentTime: Long) {
        try {
            context.dataStore.edit { preferences ->
                preferences[MEDIA_RESUMPTION_TIME] = currentTime
            }
        } catch (e: Exception) {
            android.util.Log.e("DATASTORE", "Failed to save resumption position", e)
        }
    }

    @UnstableApi
    val playbackResumptionPlaylistWithStartPosition: Flow<MediaSession.MediaItemsWithStartPosition> = context.dataStore.data.map { preferences ->
        withContext(NonCancellable) {
            try {
                val rawJson = preferences[MEDIA_RESUMPTION_PLAYLIST] ?: "[]"
                val songs = json.decodeFromString<List<MediaData.Song>>(rawJson)
                MediaSession.MediaItemsWithStartPosition(
                    songs.map { it.toMediaItem() },
                    preferences[MEDIA_RESUMPTION_INDEX] ?: 0,
                    preferences[MEDIA_RESUMPTION_TIME] ?: 0L
                )
            } catch (e: Exception) {
                android.util.Log.e("DATASTORE", "Failed to decode playback resumption", e)
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
        }
    }

    val sortAlbumOrder: Flow<SortOrder> =
        context.dataStore.data.map { preferences ->
            SortOrder.entries.find { it.key == preferences[SORT_ALBUM_ORDER] } ?: SortOrder.ALPHABETICAL
        }.distinctUntilChanged()

    val showFavoriteOnly: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[SHOW_FAVORITES_ONLY] ?: false
        }.distinctUntilChanged()

    suspend fun saveSortAlbumOrder(sortOrder: SortOrder) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SORT_ALBUM_ORDER] = sortOrder.key
            }
        }
    }

    suspend fun saveShowFavoriteOnly(showFavorites: Boolean) {
        withContext(NonCancellable) {
            context.dataStore.edit { preferences ->
                preferences[SHOW_FAVORITES_ONLY] = showFavorites;
            }
        }
    }
}