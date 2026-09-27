package com.craftworks.music.data.datasource.navidrome

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.NavidromeLibrary
import com.craftworks.music.data.datasource.installHttpDefaults
import com.craftworks.music.data.datasource.redactSubsonicUrl
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.MediaData
import com.craftworks.music.data.model.toLyric
import com.craftworks.music.data.model.toLyrics
import com.craftworks.music.managers.NavidromeManager
import com.craftworks.music.providers.navidrome.navidromeStatus
import com.craftworks.music.providers.navidrome.parseNavidromeAlbumJSON
import com.craftworks.music.providers.navidrome.parseNavidromeAlbumListJSON
import com.craftworks.music.providers.navidrome.parseNavidromeArtistAlbumsJSON
import com.craftworks.music.providers.navidrome.parseNavidromeArtistBiographyJSON
import com.craftworks.music.providers.navidrome.parseNavidromeArtistsJSON
import com.craftworks.music.providers.navidrome.parseNavidromeFavouritesJSON
import com.craftworks.music.providers.navidrome.parseNavidromeLibrariesJSON
import com.craftworks.music.providers.navidrome.parseNavidromePlainLyricsJSON
import com.craftworks.music.providers.navidrome.parseNavidromePlaylistJSON
import com.craftworks.music.providers.navidrome.parseNavidromePlaylistsJSON
import com.craftworks.music.providers.navidrome.parseNavidromeRadioJSON
import com.craftworks.music.providers.navidrome.parseNavidromeSearch3JSON
import com.craftworks.music.providers.navidrome.parseNavidromeSimilarSongsJSON
import com.craftworks.music.providers.navidrome.parseNavidromeSongJSON
import com.craftworks.music.providers.navidrome.SubsonicParseException
import com.craftworks.music.providers.navidrome.parseNavidromeStatus
import com.craftworks.music.providers.navidrome.parseNavidromeSyncedLyricsJSON
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class NavidromeDataSource @Inject constructor() {
    /**
     * Set once from [ChoraApplication.onCreate] (and again from the Hilt
     * singleton as a belt-and-braces fallback). Direct `NavidromeDataSource()`
     * callers in [NavidromeManager] / ping path have no Context of their own.
     */
    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpCache)
            installHttpDefaults()
        }
    }

    private val insecureClient: HttpClient by lazy { buildInsecureClient() }

    companion object {
        const val CLIENT_UNIQUE_ID_HEADER = "X-ND-Client-Unique-Id"

        /** Application context; set from `ChoraApplication.onCreate`. */
        @Volatile
        var appContextRef: Context? = null

        @Volatile
        private var persistentSalt: String? = null

        @Volatile
        private var clientUniqueId: String? = null

        fun md5Hash(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(input.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Subsonic accepts any salt, so we persist one per install. A random salt per
         * fetch made every cover URL unique, which broke Coil's disk cache and forced
         * re-downloads each time the player was opened.
         */
        fun initPersistentSalt(context: android.content.Context) {
            if (persistentSalt != null) return
            val prefs = context.applicationContext
                .getSharedPreferences("ChoraNetworkPrefs", android.content.Context.MODE_PRIVATE)
            persistentSalt = prefs.getString("media_salt", null) ?: randomSalt(8).also {
                prefs.edit().putString("media_salt", it).apply()
            }
        }

        /**
         * Stable per-install client id. The handoff server keys sessions by
         * `X-ND-Client-Unique-Id`; without a stable value the session id
         * falls back to a cookie-derived player id that changes on cold start,
         * duplicating sessions and breaking SSE `targetSessionId` filtering.
         *
         * Format: `chora-<8 hex>` — short, URL-safe, and recognizable in the
         * server's session list / `playerName`.
         */
        fun getOrCreateClientUniqueId(context: Context): String {
            clientUniqueId?.let { return it }
            synchronized(this) {
                clientUniqueId?.let { return it }
                val prefs = context.applicationContext
                    .getSharedPreferences("ChoraNetworkPrefs", Context.MODE_PRIVATE)
                val existing = prefs.getString("client_unique_id", null)
                if (!existing.isNullOrBlank()) {
                    clientUniqueId = existing
                    return existing
                }
                val fresh = "chora-" + java.util.UUID.randomUUID()
                    .toString().replace("-", "").take(8)
                prefs.edit().putString("client_unique_id", fresh).apply()
                clientUniqueId = fresh
                return fresh
            }
        }

        private fun randomSalt(length: Int): String {
            val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            return (1..length).map { allowedChars.random() }.joinToString("")
        }

        fun generateSalt(length: Int): String {
            if (length == 8) {
                persistentSalt?.let { return it }
                synchronized(this) {
                    persistentSalt?.let { return it }
                    return randomSalt(8).also { persistentSalt = it }
                }
            }
            return randomSalt(length)
        }
    }

    private fun buildInsecureClient(): HttpClient {
        val trustAllCerts = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        return HttpClient(OkHttp.create {
            config {
                val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpCache)
            installHttpDefaults()
        }
    }

    private suspend fun getRequest(
        endpoint: String,
        musicFolderIds: List<Int>? = null,
        ignoreCachedResponse: Boolean = false,
        retryCount: Int = 0
    ): List<Any> = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext emptyList()
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)

        val salt = generateSalt(8)
        val token = md5Hash(server.password + salt)

        val url = URLBuilder("${baseUrl}/rest/${endpoint.replace(" ", "%20")}").apply {
            parameters.append("u", server.username)
            parameters.append("t", token)
            parameters.append("s", salt)
            musicFolderIds?.forEach { parameters.append("musicFolderId", it.toString()) }
            parameters.append("v", "1.16.1")
            parameters.append("c", "Chora")
            parameters.append("f", "json")
        }.buildString()

        NavidromeManager.setSyncingStatus(true)

        val activeClient = if (server.allowSelfSignedCert == true) insecureClient else client
        val parsedData = mutableListOf<Any>()

        try {
            val response: HttpResponse = activeClient.get(url) {
                appContextRef?.let { header(CLIENT_UNIQUE_ID_HEADER, getOrCreateClientUniqueId(it)) }
                // Force network request if ignoreCachedResponse is true
                if (ignoreCachedResponse) {
                    headers {
                        append("Cache-Control", "no-cache")
                    }
                }
            }

            if (response.status != HttpStatusCode.OK) {
                Log.w("NAVIDROME", "HTTP ${response.status} for URL: ${redactSubsonicUrl(url)}")
                return@withContext emptyList()
            }
            val responseContent = response.bodyAsText()

            when {
                endpoint.startsWith("ping")         -> parsedData.addAll(parseNavidromeStatus(responseContent))
                endpoint.startsWith("getMusicFolders") -> parsedData.addAll(parseNavidromeLibrariesJSON(responseContent))

                endpoint.startsWith("search3")      -> parsedData.addAll(parseNavidromeSearch3JSON(responseContent, baseUrl, server.username, server.password))

                // Single song (Playback Handoff takeover target). Had no branch
                // at all, so `getNavidromeSong` silently returned null for every
                // call and the takeover had nothing to play.
                endpoint.startsWith("getSong.")     -> parsedData.addAll(
                    listOfNotNull(parseNavidromeSongJSON(responseContent, baseUrl, server.username, server.password))
                )
                endpoint.startsWith("getSong?")     -> parsedData.addAll(
                    listOfNotNull(parseNavidromeSongJSON(responseContent, baseUrl, server.username, server.password))
                )

                endpoint.startsWith("getAlbumList") -> parsedData.addAll(parseNavidromeAlbumListJSON(responseContent, baseUrl, server.username, server.password))
                endpoint.startsWith("getAlbum.")    -> parsedData.addAll(parseNavidromeAlbumJSON(responseContent, baseUrl, server.username, server.password)) // Note: getAlbum.view takes an album ID, typically not musicFolderId

                endpoint.startsWith("getArtists")   -> parsedData.addAll(parseNavidromeArtistsJSON(responseContent))
                endpoint.startsWith("getArtist.")   -> parsedData.addAll(parseNavidromeArtistAlbumsJSON(responseContent, baseUrl, server.username, server.password))
                endpoint.startsWith("getArtistInfo")-> parsedData.addAll(listOf(parseNavidromeArtistBiographyJSON(responseContent)))

                endpoint.startsWith("getPlaylists") -> parsedData.addAll(parseNavidromePlaylistsJSON(responseContent, baseUrl, server.username, server.password))
                endpoint.startsWith("getPlaylist.") -> parsedData.addAll(parseNavidromePlaylistJSON(responseContent, baseUrl, server.username, server.password))

                endpoint.startsWith("getInternetRadioStations") -> parsedData.addAll(parseNavidromeRadioJSON(responseContent))

                endpoint.startsWith("getLyrics.") -> parsedData.addAll(listOf(parseNavidromePlainLyricsJSON(responseContent)))
                endpoint.startsWith("getLyricsBySongId.") -> parsedData.addAll(parseNavidromeSyncedLyricsJSON(responseContent))

                endpoint.startsWith("getStarred") -> { parsedData.addAll(parseNavidromeFavouritesJSON(responseContent, baseUrl, server.username, server.password)) }

                endpoint.startsWith("setRating") -> { NavidromeManager.setSyncingStatus(false) }

                endpoint.startsWith("star") -> { NavidromeManager.setSyncingStatus(false) }
                endpoint.startsWith("unstar") -> { NavidromeManager.setSyncingStatus(false) }

                endpoint.startsWith("getSimilarSongs") -> parsedData.addAll(parseNavidromeSimilarSongsJSON(responseContent, baseUrl, server.username, server.password))
            }
        } catch (e: Exception) {
            Log.e("NAVIDROME", "Network error for URL: ${redactSubsonicUrl(url)}", e)
            val isConnectionError = e is ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    e is UnresolvedAddressException ||
                    e.message?.contains("Failed to connect", ignoreCase = true) == true ||
                    e.message?.contains("Connection refused", ignoreCase = true) == true

            if (isConnectionError && retryCount < 1) {
                Log.w("NAVIDROME", "Connection error detected. STUN port may have changed. Re-resolving dynamic URL...")
                NavidromeManager.resolveActiveServerUrl(server, forceRefresh = true)
                return@withContext getRequest(endpoint, musicFolderIds, ignoreCachedResponse, retryCount + 1)
            }
            if (e is UnresolvedAddressException) {
                navidromeStatus.value = "Unknown host"
            } else if (e is SubsonicParseException) {
                // A parse failure means the server answered with something that
                // isn't a Subsonic payload (proxy error page, login redirect).
                // Reporting it as "no data" left every list silently empty.
                navidromeStatus.value = "Unexpected response: ${e.message}"
                Log.e("NAVIDROME", e.message, e)
            } else {
                navidromeStatus.value = e.message.toString()
            }
        } finally {
            NavidromeManager.setSyncingStatus(false)
        }

        parsedData
    }

    suspend fun pingNavidromeServer(): List<String> = withContext(Dispatchers.IO) {
        getRequest("ping.view?f=json").filterIsInstance<String>()
    }

    suspend fun getNavidromeLibraries(): List<NavidromeLibrary> = withContext(Dispatchers.IO) {
        getRequest("getMusicFolders.view?f=json").filterIsInstance<NavidromeLibrary>()
    }

    // Albums
    suspend fun getNavidromeAlbums(
        sort: String? = "alphabeticalByName",
        size: Int? = 100,
        offset: Int? = 0,
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
        favoritesOnly: Boolean = false
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val effectiveSort = if (favoritesOnly) "starred" else sort

        getRequest(
            "getAlbumList.view?type=$effectiveSort&size=$size&offset=$offset",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun getNavidromeAlbum(
        albumId: String, ignoreCachedResponse: Boolean = false
    ): List<MediaItem>? = withContext(Dispatchers.IO) {
        getRequest(
            "getAlbum.view?id=${albumId}",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun searchNavidromeAlbums(
        query: String? = "",
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        getRequest(
            "search3.view?query=$query&songCount=0&songOffset=0&artistCount=0&albumCount=100",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    // Songs
    suspend fun getNavidromeSongs(
        query: String? = "",
        songCount: Int = 100,
        songOffset: Int = 0,
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
        favoritesOnly: Boolean = false,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        (if (query == "" && favoritesOnly) getRequest(
        "getStarred.view",
        musicFolderIds,
        ignoreCachedResponse
        ) else getRequest(
        "search3.view?query=$query&songCount=$songCount&songOffset=$songOffset&artistCount=0&albumCount=0",
        musicFolderIds,
        ignoreCachedResponse
        )).filterIsInstance<MediaItem>()
    }

    suspend fun getNavidromeSong(
        songId: String, ignoreCachedResponse: Boolean = false
    ): MediaItem? = withContext(Dispatchers.IO) {
        getRequest(
            "getSong.view?id=$songId",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>().firstOrNull()
    }

    suspend fun scrobbleSong(songId: String, submission: Boolean) = withContext(Dispatchers.IO) {
        getRequest(
            "scrobble.view?id=$songId&submission=$submission",
            null,
            true
        )
    }

    suspend fun reportPlayback(
        songId: String,
        state: String,
        positionMs: Long,
        outputDevice: String? = null,
        volume: Int? = null,
        playMode: String? = null,
        bilingualActive: Boolean? = null
    ) = withContext(Dispatchers.IO) {
        if (songId.isBlank() || songId.startsWith("Local")) return@withContext emptyList()
        val ignoreScrobble = (state == "paused" || state == "stopped")
        // NOTE: `volume=0` is swallowed server-side (treated as "not sent", stored
        // as 100 or keeps old value), so mute can never be reported — skip it.
        val params = buildString {
            append("reportPlayback.view?mediaId=$songId&id=$songId&mediaType=song&state=$state&positionMs=$positionMs&playbackRate=1.0&ignoreScrobble=$ignoreScrobble")
            if (!outputDevice.isNullOrBlank()) append("&outputDevice=$outputDevice")
            if (volume != null && volume in 1..100) append("&volume=$volume")
            if (!playMode.isNullOrBlank()) append("&playMode=$playMode")
            if (bilingualActive != null) append("&bilingualActive=$bilingualActive")
        }
        getRequest(
            params,
            null,
            true
        )
    }

    suspend fun savePlayQueue(
        songIds: List<String>,
        currentSongId: String,
        positionMs: Long
    ) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyList()
        val queryParams = buildString {
            append("savePlayQueue.view?")
            songIds.take(50).forEach { id ->
                append("id=").append(id).append("&")
            }
            append("current=").append(currentSongId)
            append("&position=").append(positionMs)
        }
        getRequest(queryParams, null, true)
    }

    // Artists
    suspend fun getNavidromeArtists(
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
        favoritesOnly: Boolean = false
    ): List<MediaData.Artist> {
        var artists = getRequest(
            "getArtists.view?f=json",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaData.Artist>();
        if (favoritesOnly) return withContext(Dispatchers.IO) {
            artists.filter { artist -> artist.starred != "" }
        }
        return withContext(Dispatchers.IO) { artists }
    }

    suspend fun getNavidromeArtistAlbums(
        artistId: String, ignoreCachedResponse: Boolean = false
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        getRequest(
            "getArtist.view?id=$artistId",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun getNavidromeArtistInfo(
        artistId: String, ignoreCachedResponse: Boolean = false
    ): MediaData.ArtistInfo? = withContext(Dispatchers.IO) {
        getRequest(
            "getArtistInfo.view?id=$artistId",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaData.ArtistInfo>().firstOrNull()
    }

    suspend fun searchNavidromeArtists(
        query: String? = "",
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
    ): List<MediaData.Artist> = withContext(Dispatchers.IO) {
        if (query.isNullOrBlank()) getNavidromeArtists(musicFolderIds = musicFolderIds, ignoreCachedResponse = ignoreCachedResponse)
        else getRequest(
            "search3.view?query=$query&artistCount=100&albumCount=0&songCount=0",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaData.Artist>()
    }

    // Playlists
    suspend fun getNavidromePlaylists(
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        getRequest(
            "getPlaylists.view?f=json",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun getNavidromePlaylist(
        playlistId: String, ignoreCachedResponse: Boolean = false
    ): List<MediaItem>? = withContext(Dispatchers.IO) {
        getRequest(
            "getPlaylist.view?id=$playlistId",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun createNavidromePlaylist(
        name: String, songIds: List<String>? = null, ignoreCachedResponse: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        var endpoint = "createPlaylist.view?name=$name"
        songIds?.forEach { songId -> endpoint += "&songId=$songId" }
        val response = getRequest(endpoint, null, ignoreCachedResponse)
        response.isNotEmpty()
    }

    suspend fun addSongToNavidromePlaylist(
        playlistId: String, songId: String, ignoreCachedResponse: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val response = getRequest(
            "updatePlaylist.view?playlistId=$playlistId&songIdToAdd=$songId",
            null,
            ignoreCachedResponse
        )
        response.isNotEmpty()
    }

    suspend fun removeSongFromNavidromePlaylist(
        playlistId: String, songIndexToRemove: Int, ignoreCachedResponse: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val response = getRequest(
            "updatePlaylist.view?playlistId=$playlistId&songIndexToRemove=$songIndexToRemove",
            null,
            ignoreCachedResponse
        )
        response.isNotEmpty()
    }

    suspend fun deleteNavidromePlaylist(
        playlistId: String, ignoreCachedResponse: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val response = getRequest(
            "deletePlaylist.view?id=$playlistId",
            null,
            ignoreCachedResponse
        )
        response.isNotEmpty()
    }

    // Radios
    suspend fun getNavidromeRadios(
        ignoreCachedResponse: Boolean = false
    ): List<MediaData.Radio> = withContext(Dispatchers.IO) {
        getRequest(
            "getInternetRadioStations.view?f=json",
            null,
            ignoreCachedResponse
        ).filterIsInstance<MediaData.Radio>()
    }

    suspend fun createNavidromeRadio(
        name: String, url: String, homePageUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        getRequest(
            "createInternetRadioStation.view?name=$name&streamUrl=$url&homepageUrl=$homePageUrl",
            null,
            true
        )
    }

    suspend fun updateNavidromeRadio(
        radioId: String, name: String, url: String, homePageUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        getRequest(
            "updateInternetRadioStation.view?name=$name&streamUrl=$url&homepageUrl=$homePageUrl&id=$radioId",
            null,
            true
        )
    }

    suspend fun deleteNavidromeRadio(
        radioId: String
    ) = withContext(Dispatchers.IO) {
        getRequest(
            "deleteInternetRadioStation.view?id=$radioId",
            null,
            true
        )
    }

    // Lyrics
    suspend fun getNavidromePlainLyrics(
        metadata: MediaMetadata?, ignoreCachedResponse: Boolean = false
    ): List<Lyric> = withContext(Dispatchers.IO) {
        getRequest("getLyrics.view?artist=${metadata?.artist}&title=${metadata?.title}", null).filterIsInstance<MediaData.PlainLyrics>().getOrNull(0)?.toLyric()?.takeIf { it.text.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    }

    suspend fun getNavidromeSyncedLyrics(
        songId: String, ignoreCachedResponse: Boolean = false
    ): List<Lyric> = withContext(Dispatchers.IO) {
        getRequest("getLyricsBySongId.view?id=${songId}&enhanced=true", null, ignoreCachedResponse)
            .filterIsInstance<MediaData.StructuredLyrics>().flatMap { it.toLyrics() }
    }

    // Starred Items
    suspend fun getNavidromeStarred(
        ignoreCachedResponse: Boolean = false,
        musicFolderIds: List<Int>? = NavidromeManager.getEnabledLibraryIdsForCurrentServer(),
        ): List<MediaItem> = withContext(Dispatchers.IO) {
        getRequest(
            "getStarred.view?",
            musicFolderIds,
            ignoreCachedResponse
        ).filterIsInstance<MediaItem>()
    }

    suspend fun starNavidromeItem(id: String = "", albumId: String = "", artistId: String = "", ignoreCachedResponse: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            var endpoint = "star.view?"
            if (id.isNotEmpty()) {
                endpoint += "id=$id"
            }
            if (albumId.isNotEmpty()) {
                endpoint += "albumId=$albumId"
            }
            if (artistId.isNotEmpty()) {
                endpoint += "artistId=$artistId"
            }
            getRequest(endpoint, null, ignoreCachedResponse)
            true
        }

    suspend fun unstarNavidromeItem(
        id: String = "", albumId: String = "", artistId: String = "", ignoreCachedResponse: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        var endpoint = "unstar.view?"
        if (id.isNotEmpty()) {
            endpoint += "id=$id"
        }
        if (albumId.isNotEmpty()) {
            endpoint += "albumId=$albumId"
        }
        if (artistId.isNotEmpty()) {
            endpoint += "artistId=$artistId"
        }
        getRequest(endpoint, null, ignoreCachedResponse)
        true
    }

    suspend fun setNavidromeRating(
        id: String = "", rating: Int = 0
    ) = withContext(Dispatchers.IO) {
        getRequest(
            "setRating.view?id=$id&rating=$rating",
            null,
            true
        )
    }

    suspend fun getNavidromeSimilarSong(
        id: String,
        count: Int
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        getRequest(
            "getSonicSimilarTracks.view?id=$id&count=$count",
            null,
            true
        ).filterIsInstance<MediaItem>()
    }
}