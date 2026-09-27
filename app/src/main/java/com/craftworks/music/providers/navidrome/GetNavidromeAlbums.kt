package com.craftworks.music.providers.navidrome

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.data.model.MediaData
import com.craftworks.music.data.model.toMediaItem
import kotlinx.serialization.Serializable

@Serializable
data class albumList(val album: List<MediaData.Album>? = listOf())

fun parseNavidromeAlbumListJSON(
    response: String,
    navidromeUrl: String,
    navidromeUsername: String,
    navidromePassword: String,
) : List<MediaItem> {
    val subsonicResponse = parseSubsonicResponse(response)

    // Generate password salt and hash for coverArt
    val passwordSaltArt = NavidromeDataSource.generateSalt(8)
    val passwordHashArt = NavidromeDataSource.md5Hash(navidromePassword + passwordSaltArt)

    val baseCoverArtUrl = "$navidromeUrl/rest/getCoverArt.view?u=$navidromeUsername&t=$passwordHashArt&s=$passwordSaltArt&v=1.16.1&c=Chora&size=300"

    val mediaDataAlbums = subsonicResponse.albumList?.album?.map {
        val mediaItem = it.toMediaItem()
        mediaItem.buildUpon().setMediaMetadata(
            mediaItem.mediaMetadata.buildUpon()
                .setArtworkUri("$baseCoverArtUrl&id=${it.navidromeID}".toUri())
                .build()
        ).build()
    } ?: emptyList()

    return mediaDataAlbums
}

@OptIn(UnstableApi::class)
fun parseNavidromeAlbumJSON(
    response: String,
    navidromeUrl: String,
    navidromeUsername: String,
    navidromePassword: String,
): List<MediaItem> {
    val subsonicResponse = parseSubsonicResponse(response)

    val passwordSalt = NavidromeDataSource.generateSalt(8)
    val passwordHash = NavidromeDataSource.md5Hash(navidromePassword + passwordSalt)

    val selectedAlbum = subsonicResponse.album
    val album = mutableListOf<MediaItem>()

    selectedAlbum?.songs?.forEach {
        it.media = "$navidromeUrl/rest/stream.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHash&s=$passwordSalt&v=1.12.0&c=Chora"
        it.imageUrl = "$navidromeUrl/rest/getCoverArt.view?&id=${it.navidromeID}&u=$navidromeUsername&t=$passwordHash&s=$passwordSalt&v=1.16.1&c=Chora&size=300"
    }

    album.add(selectedAlbum?.toMediaItem() ?: MediaItem.EMPTY)

    album.addAll(selectedAlbum?.songs?.map {
        it.toMediaItem()
    } ?: emptyList())

    return if (selectedAlbum != null) album
    else emptyList()
}