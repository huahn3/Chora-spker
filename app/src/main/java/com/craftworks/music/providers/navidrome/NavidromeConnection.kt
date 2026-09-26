package com.craftworks.music.providers.navidrome

import android.util.Log
import com.craftworks.music.data.model.MediaData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
@SerialName("subsonic-response")
data class SubsonicResponse(
    val status: String = "failed",
    val error: SubsonicError? = null,
    // Every one of these used to be a required field, so any server that omits
    // them (old Subsonic/Airsonic builds don't send `openSubsonic` at all)
    // threw and the caller silently received an empty list — indistinguishable
    // from "no data".
    val version: String = "",
    val type: String = "",
    val serverVersion: String = "",
    val openSubsonic: Boolean = false,

    // Music folders
    val musicFolders: MusicFolder? = null,

    // Songs
    val song: MediaData.Song? = null,
    val searchResult3: SearchResult3? = null,

    // Albums
    val albumList: albumList? = null,
    val album: MediaData.Album? = null,

    // Artists
    val artists: Artists? = null,
    val artist: MediaData.Artist? = null,
    val artistInfo: MediaData.ArtistInfo? = null,

    // Radios
    val internetRadioStations: internetRadioStations? = null,

    // Playlists
    val playlist: MediaData.Playlist? = null,
    val playlists: PlaylistContainer? = null,

    //Lyrics
    val lyrics: MediaData.PlainLyrics? = null,
    val lyricsList: LyricsList? = null,

    // Favourites
    val starred: Starred? = null,

    val sonicMatch: List<MediaData.Song>? = null
)

private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * Parses a Subsonic/OpenSubsonic response body.
 *
 * Throws [SubsonicParseException] with a readable message when the payload
 * isn't a Subsonic envelope at all (reverse proxy 502 HTML page, captive
 * portal, auth redirect to a login form). Callers used to see a bare NPE from
 * `jsonObject["subsonic-response"]!!` and swallow it into an empty list.
 */
fun parseSubsonicResponse(response: String): SubsonicResponse {
    val root = try {
        jsonParser.parseToJsonElement(response)
    } catch (e: Exception) {
        throw SubsonicParseException("Response was not valid JSON (is the server returning an HTML error page?)", e)
    }

    val envelope = runCatching { root.jsonObject }.getOrNull()
        ?: throw SubsonicParseException("Response was not a JSON object")

    val node = envelope["subsonic-response"]
        ?: throw SubsonicParseException("Response is missing the 'subsonic-response' root element")

    val parsed = try {
        jsonParser.decodeFromJsonElement<SubsonicResponse>(node)
    } catch (e: Exception) {
        throw SubsonicParseException("Malformed 'subsonic-response' payload: ${e.message}", e)
    }

    if (parsed.status != "ok") {
        val errorCode = parsed.error?.code
        val errorMessage = parsed.error?.message
        Log.d("NAVIDROME", "Navidrome Error Code: $errorCode, Message: $errorMessage")
        navidromeStatus.value = "Error $errorCode: $errorMessage"
    }

    return parsed
}

class SubsonicParseException(message: String, cause: Throwable? = null) : Exception(message, cause)