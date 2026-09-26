package com.craftworks.music.ui.elements

import com.craftworks.music.managers.settings.rememberAppearanceSettings
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.craftworks.music.R
import com.craftworks.music.data.model.MediaData
import com.craftworks.music.data.model.albumList
import com.craftworks.music.data.model.songsList
import com.craftworks.music.data.model.toAlbum
import com.craftworks.music.managers.NavidromeManager
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.player.SongHelper
import com.craftworks.music.ui.viewmodels.AlbumScreenViewModel
import com.craftworks.music.ui.viewmodels.SongsScreenViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

//region Songs
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongsHorizontalColumn(
    songList: List<MediaItem>,
    onSongSelected: (itemsList: List<MediaItem>, index: Int) -> Unit,
    onAddToQueue: (song: MediaItem) -> Unit,
    onSetRating: (sond: MediaItem) -> Unit,
    isSearch: Boolean? = false,
    showFavoritesOnly: Boolean = false,
    viewModel: SongsScreenViewModel? = null
){
    val listState = rememberLazyListState()

    val showDividers by rememberAppearanceSettings().showProviderDividersFlow.collectAsStateWithLifecycle(true)

    // Load more songs at scroll.
    // The old guard was `songList.size % 100 != 0` -> return, which silently
    // stopped paging forever as soon as a local folder contributed a few songs
    // (size is then no longer a multiple of 100). The ViewModel now reports
    // whether the server has more.
    val fallbackCanLoadMore = remember { MutableStateFlow(false) }
    val canLoadMore by (viewModel?.canLoadMore ?: fallbackCanLoadMore)
        .collectAsStateWithLifecycle()

    if (NavidromeManager.checkActiveServers() && isSearch == false && !showFavoritesOnly){
        LaunchedEffect(listState, songList.size, canLoadMore) {
            if (!canLoadMore) return@LaunchedEffect

            snapshotFlow {
                val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val totalItemsCount = listState.layoutInfo.totalItemsCount

                lastVisibleItemIndex != null && totalItemsCount > 0 &&
                        (totalItemsCount - lastVisibleItemIndex) <= 25
            }
                .filter { it }
                .collect {
                    if (viewModel == null) return@collect
                    viewModel.getMoreSongs()
                }
        }
    }

    LazyColumn(
        modifier = Modifier
            .wrapContentHeight()
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Group songs by their source (Local or Navidrome)
        val groupedSongs = songList.groupBy { song ->
            if (song.mediaMetadata.extras?.getString("navidromeID")?.startsWith("Local_") == true) "Local" else "Navidrome"
        }

        groupedSongs.forEach { (groupName, songsInGroup) ->
            if (showDividers && groupedSongs.size > 1) {
                item {
                    HorizontalDivider(
                        modifier = Modifier
                            .height(1.dp)
                            .fillMaxWidth(),
                            //.background(MaterialTheme.colorScheme.background),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Text(
                        text = when (groupName) {
                            "Navidrome" -> stringResource(R.string.Source_Navidrome)
                            "Local" -> stringResource(R.string.Source_Local)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            //.background(MaterialTheme.colorScheme.background)
                            .padding(8.dp)
                    )
                }
            }
            itemsIndexed(
                items = songsInGroup,
                key = { _, song -> song.mediaMetadata.extras?.getString("navidromeID") ?: song.mediaId }
            ) { index, song ->
                HorizontalSongCard(
                    song = song,
                    onClick = {
                        onSongSelected(songsInGroup, index)
                    },
                    onAddToQueue = {
                        onAddToQueue(song)
                    },
                    onSetRating = {
                        onSetRating(song)
                    }
                )
            }
        }
    }
}
//endregion

//region Albums
@ExperimentalFoundationApi
@Composable
fun AlbumGrid(
    albums: List<MediaItem>,
    mediaController: MediaController?,
    onAlbumSelected: (album: MediaData.Album) -> Unit,
    isSearch: Boolean? = false,
    viewModel: AlbumScreenViewModel = viewModel(),
){
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    val showDividers by rememberAppearanceSettings().showProviderDividersFlow.collectAsStateWithLifecycle(true)

    // Group songs by their source (Local or Navidrome)
    val groupedAlbums = albums.groupBy { song ->
        if (song.mediaMetadata.extras?.getString("navidromeID")?.startsWith("Local_") == true) "Local" else "Navidrome"
    }

    if (NavidromeManager.checkActiveServers() && isSearch == false) {
        LaunchedEffect(gridState, albums.size) {
            if (albums.isEmpty()) return@LaunchedEffect

            snapshotFlow {
                val lastVisibleItemIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val totalItemsCount = gridState.layoutInfo.totalItemsCount

                lastVisibleItemIndex != null && totalItemsCount > 0 &&
                        (totalItemsCount - lastVisibleItemIndex) <= 10
            }
                .filter { it }
                .collect {
                    viewModel.getMoreAlbums()
                }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .wrapContentWidth()
            .fillMaxHeight(),
        state = gridState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        if (showDividers && groupedAlbums.size > 1) {
            groupedAlbums.forEach { (groupName, albumsInGroup) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column (Modifier.padding(start = 12.dp)) {
                        HorizontalDivider(
                            modifier = Modifier
                                .height(1.dp)
                                .fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Text(
                            text = when (groupName) {
                                "Navidrome" -> stringResource(R.string.Source_Navidrome)
                                "Local" -> stringResource(R.string.Source_Local)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )
                    }

                }
                itemsIndexed(
                    items = albumsInGroup,
                    key = { _, album -> album.mediaMetadata.extras?.getString("navidromeID") ?: album.mediaId }
                ) { index, album ->
                    AlbumCard(album = album,
                        onClick = {
                            onAlbumSelected(album.toAlbum())
                        },
                        onPlay = {
                            coroutineScope.launch {
                                val mediaItems = viewModel.getAlbum(album.mediaMetadata.extras?.getString("navidromeID") ?: "")
                                val songsToPlay = if (mediaItems.isNotEmpty() && mediaItems[0].mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM) {
                                    mediaItems.drop(1)
                                } else {
                                    mediaItems
                                }
                                if (songsToPlay.isNotEmpty())
                                    SongHelper.play(
                                        mediaItems = songsToPlay,
                                        index = 0,
                                        mediaController = mediaController
                                    )
                            }
                        }
                    )
                }
            }
        }
        else {
            items(
                items = albums,
                key = { it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId }
            ) { album ->
                AlbumCard(album = album,
                    onClick = {
                        onAlbumSelected(album.toAlbum())
                    },
                    onPlay = {
                        coroutineScope.launch {
                            val mediaItems = viewModel.getAlbum(album.mediaMetadata.extras?.getString("navidromeID") ?: "")
                            val songsToPlay = if (mediaItems.isNotEmpty() && mediaItems[0].mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM) {
                                mediaItems.drop(1)
                            } else {
                                mediaItems
                            }
                            if (songsToPlay.isNotEmpty())
                                SongHelper.play(
                                    mediaItems = songsToPlay,
                                    index = 0,
                                    mediaController = mediaController
                                )
                        }
                    }
                )
            }
        }
    }
}

@ExperimentalFoundationApi
@Composable
fun AlbumGrid(
    albums: List<MediaItem>,
    mediaController: MediaController?,
    onAlbumSelected: (album: MediaData.Album) -> Unit,
    // suspend: the caller's only caller runs inside coroutineScope.launch,
    // so the old signature forced a main-thread runBlocking on a network call.
    onGetAlbum: suspend (albumID: String) -> List<MediaItem>
) {
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    val showDividers by rememberAppearanceSettings().showProviderDividersFlow.collectAsStateWithLifecycle(true)

    // Group songs by their source (Local or Navidrome)
    val groupedAlbums = albums.groupBy { song ->
        if (song.mediaMetadata.extras?.getString("navidromeID")?.startsWith("Local_") == true) "Local" else "Navidrome"
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .wrapContentWidth()
            .fillMaxHeight(),
        state = gridState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        if (showDividers && groupedAlbums.size > 1) {
            groupedAlbums.forEach { (groupName, albumsInGroup) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column (Modifier.padding(start = 12.dp)) {
                        HorizontalDivider(
                            modifier = Modifier
                                .height(1.dp)
                                .fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Text(
                            text = when (groupName) {
                                "Navidrome" -> stringResource(R.string.Source_Navidrome)
                                "Local" -> stringResource(R.string.Source_Local)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )
                    }

                }
                itemsIndexed(
                    items = albumsInGroup,
                    key = { _, album -> album.mediaMetadata.extras?.getString("navidromeID") ?: album.mediaId }
                ) { index, album ->
                    AlbumCard(album = album,
                        onClick = {
                            onAlbumSelected(album.toAlbum())
                        },
                        onPlay = {
                            coroutineScope.launch {
                                val mediaItems = onGetAlbum(album.mediaMetadata.extras?.getString("navidromeID") ?: "")
                                val songsToPlay = if (mediaItems.isNotEmpty() && mediaItems[0].mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM) {
                                    mediaItems.drop(1)
                                } else {
                                    mediaItems
                                }
                                if (songsToPlay.isNotEmpty())
                                    SongHelper.play(
                                        mediaItems = songsToPlay,
                                        index = 0,
                                        mediaController = mediaController
                                    )
                            }
                        }
                    )
                }
            }
        }
        else {
            items(
                items = albums,
                key = { it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId }
            ) { album ->
                AlbumCard(album = album,
                    onClick = {
                        onAlbumSelected(album.toAlbum())
                    },
                    onPlay = {
                        coroutineScope.launch {
                            val mediaItems = onGetAlbum(album.mediaMetadata.extras?.getString("navidromeID") ?: "")
                            val songsToPlay = if (mediaItems.isNotEmpty() && mediaItems[0].mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM) {
                                mediaItems.drop(1)
                            } else {
                                mediaItems
                            }
                            if (songsToPlay.isNotEmpty())
                                SongHelper.play(
                                    mediaItems = songsToPlay,
                                    index = 0,
                                    mediaController = mediaController
                                )
                        }
                    }
                )
            }
        }
    }
}

@ExperimentalFoundationApi
@Composable
fun AlbumRow(
    albums: List<MediaItem>,
    onAlbumSelected: (album: MediaData.Album) -> Unit,
    onPlay: (album: MediaItem) -> Unit,
){
    val showProviderDividers by rememberAppearanceSettings().showProviderDividersFlow.collectAsStateWithLifecycle(true)
    val dividerIndex = albums.indexOfFirst { it.mediaMetadata.extras?.getString("navidromeID")?.startsWith("Local_") == true }

    LazyRow(
        modifier = Modifier
            .fillMaxSize()
            .heightIn(min = 172.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> album.mediaMetadata.extras?.getString("navidromeID") ?: album.mediaId }
        ) { index, album ->
            // Show divider between local and navidrome albums
            if (showProviderDividers) {
                if (index == dividerIndex && index != albums.lastIndex && index != 0) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        VerticalDivider(
                            modifier = Modifier
                                .height(172.dp)
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Text(
                            text = stringResource(R.string.Source_Local),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .rotateVertically(),
                        )
                    }
                }
            }

            AlbumCard(
                album = album,
                onClick = {
                    onAlbumSelected(album.toAlbum())
                },
                onPlay = {
                    onPlay(album)
                },
                modifier = Modifier.animateItem()
            )
        }
    }
}
//endregion

//region Artists
@ExperimentalFoundationApi
@Composable
fun ArtistsGrid(
    artists: List<MediaData.Artist>,
    onArtistSelected: (artist: MediaData.Artist) -> Unit
){
    val gridState = rememberLazyGridState()
    val showProviderDividers by rememberAppearanceSettings().showProviderDividersFlow.collectAsStateWithLifecycle(true)

    val groupedArtists = artists.groupBy { artist ->
        if (artist.navidromeID.startsWith("Local_")) "Local" else "Navidrome"
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .wrapContentWidth()
            .fillMaxHeight(),
        state = gridState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        if (showProviderDividers && groupedArtists.size > 1) {
            groupedArtists.forEach { (groupName, artistsInGroup) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(start = 12.dp)) {
                        HorizontalDivider(
                            modifier = Modifier
                                .height(1.dp)
                                .fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                        Text(
                            text = when (groupName) {
                                "Navidrome" -> stringResource(R.string.Source_Navidrome)
                                "Local" -> stringResource(R.string.Source_Local)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )
                    }

                }
                itemsIndexed(artistsInGroup) { index, artist ->
                    ArtistCard(artist = artist, onClick = {
                        onArtistSelected(artist)
                    })
                }
            }
        } else {
            items(
                items = artists,
                key = { it.navidromeID }
            ) { artist ->
                ArtistCard(artist = artist, onClick = {
                    onArtistSelected(artist)
                })
            }
        }
    }
}
//endregion

//region Playlists
@ExperimentalFoundationApi
@Composable
fun PlaylistGrid(playlists: List<MediaItem>, onPlaylistSelected: (playlist: MediaItem) -> Unit){
    val currentNavidromeServer by NavidromeManager.currentServerId.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .wrapContentWidth()
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        items(playlists) {playlist ->
            PlaylistCard(playlist = playlist,
                onClick = {
                    onPlaylistSelected(playlist)
                    Log.d("PLAYLISTS", "CLICKED PLAYLIST!")
                })
        }
    }
}
//endregion