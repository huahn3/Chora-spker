package com.craftworks.music.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.craftworks.music.data.model.SortOrder
import com.craftworks.music.data.repository.AlbumRepository
import com.craftworks.music.managers.DataRefreshManager
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumScreenViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val localDataSettingsManager: LocalDataSettingsManager
) : ViewModel() {

    private val _allAlbums = MutableStateFlow<List<MediaItem>>(emptyList())
    val allAlbums: StateFlow<List<MediaItem>> = _allAlbums.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ALPHABETICAL)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()
    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                localDataSettingsManager.sortAlbumOrder,
                localDataSettingsManager.showFavoriteOnly
            ) { sortOrder, showFavorites -> sortOrder to showFavorites }
                .distinctUntilChanged()
                .collect { (sortOrder, showFavorites) ->
                    _sortOrder.value = sortOrder
                    _showFavoritesOnly.value = showFavorites
                    getAlbums()
                }
        }
        viewModelScope.launch {
            DataRefreshManager.dataSourceChangedEvent.collect {
                getAlbums()
            }
        }
    }

    // Remote-only cursor: the merged list size used to include locally-scanned
    // albums, so every page after the first skipped that many server albums.
    private var remoteAlbumCount = 0
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.asStateFlow()

    private var getAlbumsJob: Job? = null
    fun getAlbums() {
        getAlbumsJob?.cancel()

        getAlbumsJob = viewModelScope.launch {
            _isLoading.value = true

            try {
                val page = albumRepository.getAlbumsPage(
                    _sortOrder.value.key, PAGE_SIZE, 0, true, _showFavoritesOnly.value
                )
                remoteAlbumCount = page.remoteCount
                _canLoadMore.value = !page.remoteExhausted
                _allAlbums.value = page.items.sortedByDescending {
                    it.mediaMetadata.extras?.getString("navidromeID")?.startsWith("Local_") == true
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getAlbum(id: String): List<MediaItem> {
        return albumRepository.getAlbum(id) ?: emptyList()
    }

    private var isFetchingMore = false
    fun getMoreAlbums(size: Int = PAGE_SIZE) {
        if (isFetchingMore) return
        viewModelScope.launch {
            isFetchingMore = true
            try {
                val page = albumRepository.getAlbumsPage(
                    _sortOrder.value.key, size, remoteAlbumCount, favoritesOnly = _showFavoritesOnly.value
                )
                remoteAlbumCount += page.remoteCount
                _canLoadMore.value = !page.remoteExhausted
                if (page.items.isNotEmpty()) {
                    val currentIds = _allAlbums.value.mapTo(HashSet()) {
                        it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId
                    }
                    val distinctNew = page.items.filter {
                        val id = it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId
                        !currentIds.contains(id)
                    }
                    if (distinctNew.isNotEmpty()) {
                        _allAlbums.value += distinctNew
                    }
                }
            } finally {
                isFetchingMore = false
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            coroutineScope {
                _searchResults.value = albumRepository.searchAlbum(query)
            }
            _isLoading.value = false
        }
    }

    fun setSorting(newSortOrder: SortOrder) {
        viewModelScope.launch {
            localDataSettingsManager.saveSortAlbumOrder(newSortOrder)
        }
    }
    fun setShowFavoritesOnly(showFavorites: Boolean) {
        viewModelScope.launch {
            localDataSettingsManager.saveShowFavoriteOnly(showFavorites)
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
