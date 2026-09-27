package com.craftworks.music.providers.navidrome

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.data.model.MediaData
import com.craftworks.music.data.model.albumList
import com.craftworks.music.data.model.artistList
import com.craftworks.music.data.model.songsList
import com.craftworks.music.data.model.toMediaItem
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult3(
    val song: List<MediaData.Song>? = listOf(),
    val album: List<MediaData.Album>? = listOf(),
    val artist: List<MediaData.Artist>? = listOf(),
)

/**
 * Single-song lookup used by Playback Handoff: `/rest/getSong.view?id=` returns
 * one `song` object that must become a playable [MediaItem] (stream + cover
 * URLs signed with this install's salt), otherwise a takeover has nothing to
 * hand to the player.
 */
@OptIn(UnstableApi::class)
fun parseNavidromeSongJSON(
    response: String,
    navidromeUrl: String,
    navidromeUsername: String,
    navidromePassword: String
): MediaItem? = signNavidromeSong(response, navidromeUrl, navidromeUsername, navidromePassword)?.toMediaItem()

/**
 * The part of [parseNavidromeSongJSON] that can be tested on the JVM: decode the
 * envelope and stamp Subsonic credentials onto the stream/cover URLs.
 *
 * Kept separate because `toMediaItem()` needs a live `android.net.Uri`
 * (`Uri.parse` is a stub under plain unit tests). Returns null for a `failed`
 * envelope or a payload without a `song` node — the cases that used to surface
 * as a bare "song not found" toast during takeover.
 */
fun signNavidromeSong(
    response: String,
    navidromeUrl: String,
    navidromeUsername: String,
    navidromePassword: String
): MediaData.Song? {
    val subsonicResponse = parseSubsonicResponse(response)
    val song = subsonicResponse.song ?: return null

    val salt = NavidromeDataSource.generateSalt(8)
    val hash = NavidromeDataSource.md5Hash(navidromePassword + salt)

    return song.copy(
        media = "$navidromeUrl/rest/stream.view?&id=${song.navidromeID}&u=$navidromeUsername&t=$hash&s=$salt&v=1.12.0&c=Chora",
        imageUrl = "$navidromeUrl/rest/getCoverArt.view?&id=${song.navidromeID}&u=$navidromeUsername&t=$hash&s=$salt&v=1.16.1&c=Chora&size=300"
    )
}

@OptIn(UnstableApi::class)
fun parseNavidromeSearch3JSON(
    response: String,
    navidromeUrl: String,
    navidromeUsername: String,
    navidromePassword: String,
) : List<Any> {
    val subsonicResponse = parseSubsonicResponse(response)

    // Generate password salt and hash
    val passwordSaltMedia = NavidromeDataSource.generateSalt(8)
    val passwordHashMedia = NavidromeDataSource.md5Hash(navidromePassword + passwordSaltMedia)

    subsonicResponse.searchResult3?.song?.map {
        it.media = "$navidromeUrl/rest/stream.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHashMedia&s=$passwordSaltMedia&v=1.12.0&c=Chora"
        it.imageUrl = "$navidromeUrl/rest/getCoverArt.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHashMedia&s=$passwordSaltMedia&v=1.16.1&c=Chora&size=300"
    }

    subsonicResponse.searchResult3?.album?.map {
        it.coverArt = "$navidromeUrl/rest/getCoverArt.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHashMedia&s=$passwordSaltMedia&v=1.16.1&c=Chora&size=300"
    }

    var mediaDataSongs = emptyList<MediaItem>()
    var mediaDataAlbums = emptyList<MediaItem>()
    var mediaDataArtists = emptyList<MediaData.Artist>()

    subsonicResponse.searchResult3?.song?.filterNot { newSong ->
        songsList.any { existingSong ->
            existingSong.navidromeID == newSong.navidromeID
        }
    }?.let { mediaDataSongs = it.map {
        it.copy(
            media = "$navidromeUrl/rest/stream.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHashMedia&s=$passwordSaltMedia&v=1.12.0&c=Chora"
        ).toMediaItem()
        }
    }

    subsonicResponse.searchResult3?.album?.filterNot { newAlbum ->
        albumList.any { existingAlbum ->
            existingAlbum.navidromeID == newAlbum.navidromeID
        }
    }?.let { mediaDataAlbums = it.map { it.toMediaItem() } }

    subsonicResponse.searchResult3?.artist?.filterNot { newArtist ->
        artistList.any { existingArtist ->
            existingArtist.navidromeID == newArtist.navidromeID
        }
    }?.let { mediaDataArtists = it }

    return when {
        mediaDataSongs.isNotEmpty() -> mediaDataSongs
        mediaDataAlbums.isNotEmpty() -> mediaDataAlbums
        else -> mediaDataArtists
    }
}