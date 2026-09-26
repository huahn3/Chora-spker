package com.craftworks.music.data.repository

import androidx.media3.common.MediaItem
import com.craftworks.music.data.datasource.local.LocalDataSource
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.managers.LocalProviderManager
import com.craftworks.music.managers.NavidromeManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepository @Inject constructor(
    private val localDataSource: LocalDataSource,
    private val navidromeDataSource: NavidromeDataSource
) {
    /** Same idea as [SongRepository.SongsPage]: the remote cursor must not
     *  include locally-scanned albums. */
    data class AlbumsPage(
        val items: List<MediaItem>,
        val remoteCount: Int,
        val requestedRemoteCount: Int
    ) {
        val remoteExhausted: Boolean
            get() = requestedRemoteCount > 0 && remoteCount < requestedRemoteCount
    }

    suspend fun getAlbums(
        sort: String? = "alphabeticalByName",
        size: Int? = 100,
        offset: Int? = 0,
        ignoreCachedResponse: Boolean = false,
        favoritesOnly: Boolean = false,
    ): List<MediaItem> = getAlbumsPage(sort, size, offset, ignoreCachedResponse, favoritesOnly).items

    suspend fun getAlbumsPage(
        sort: String? = "alphabeticalByName",
        size: Int? = 100,
        offset: Int? = 0,
        ignoreCachedResponse: Boolean = false,
        favoritesOnly: Boolean = false,
    ): AlbumsPage = coroutineScope {
        val deferredAlbums = mutableListOf<Deferred<List<MediaItem>>>()
        var remoteCount = 0

        if (NavidromeManager.checkActiveServers())
            deferredAlbums.add(async {
                navidromeDataSource.getNavidromeAlbums(sort, size, offset, ignoreCachedResponse, favoritesOnly=favoritesOnly)
                    .also { remoteCount = it.size }
            })

        if (LocalProviderManager.checkActiveFolders())
            if (offset == 0)
                deferredAlbums.add(async { localDataSource.getLocalAlbums(sort) })


        AlbumsPage(
            items = deferredAlbums.awaitAll().flatten(),
            remoteCount = remoteCount,
            requestedRemoteCount = size ?: 0
        )
    }

    suspend fun getAlbum(albumId: String, ignoreCachedResponse: Boolean = false): List<MediaItem>? = coroutineScope {
        if (albumId.startsWith("Local_"))
            localDataSource.getLocalAlbum(albumId)
        else
            navidromeDataSource.getNavidromeAlbum(albumId, ignoreCachedResponse)

    }

    suspend fun searchAlbum(query: String): List<MediaItem> = coroutineScope {
        val deferredAlbums = mutableListOf<Deferred<List<MediaItem>>>()

        if (LocalProviderManager.checkActiveFolders())
            deferredAlbums.add(async { localDataSource.searchLocalAlbums(query) })

        if (NavidromeManager.checkActiveServers())
            deferredAlbums.add(async { navidromeDataSource.searchNavidromeAlbums(query) })

        deferredAlbums.awaitAll().flatten()
    }
}