package com.craftworks.music.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.StarRating
import com.craftworks.music.data.repository.SongRepository
import com.craftworks.music.managers.DataRefreshManager
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SongsScreenViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val localDataSettingsManager: LocalDataSettingsManager
) : ViewModel() {

    private val _allSongs = MutableStateFlow<List<MediaItem>>(emptyList())
    val allSongs: StateFlow<List<MediaItem>> = _allSongs.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    init {
        viewModelScope.launch {
            localDataSettingsManager.showFavoriteOnly
                .distinctUntilChanged()
                .collect { showFavorites ->
                    _showFavoritesOnly.value = showFavorites
                    getSongs()
                }
        }
        viewModelScope.launch {
            DataRefreshManager.dataSourceChangedEvent.collect {
                getSongs()
            }
        }
    }

    private var getSongsJob: Job? = null
    fun getSongs() {
        getSongsJob?.cancel()
        getSongsJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                coroutineScope {
                    _allSongs.value = songRepository.getSongs(ignoreCachedResponse = true, favoritesOnly = _showFavoritesOnly.value)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private var isFetchingMore = false
    fun getMoreSongs(size: Int){
        if (isFetchingMore) return
        viewModelScope.launch {
            isFetchingMore = true
            try {
                coroutineScope {
                    val songOffset = _allSongs.value.size
                    val newSongs = songRepository.getSongs(songCount = size, songOffset = songOffset, favoritesOnly = _showFavoritesOnly.value)
                    if (newSongs.isNotEmpty()) {
                        val currentIds = _allSongs.value.mapTo(HashSet()) {
                            it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId
                        }
                        val distinctNew = newSongs.filter {
                            val id = it.mediaMetadata.extras?.getString("navidromeID") ?: it.mediaId
                            !currentIds.contains(id)
                        }
                        if (distinctNew.isNotEmpty()) {
                            _allSongs.value += distinctNew
                        }
                    }
                }
            } finally {
                isFetchingMore = false
            }
        }
    }

    fun search(query: String){
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            coroutineScope {
                _searchResults.value = songRepository.searchSongs(query)
            }
            _isLoading.value = false
        }
    }
    fun setShowFavoritesOnly(showFavorites: Boolean) {
        viewModelScope.launch {
            localDataSettingsManager.saveShowFavoriteOnly(showFavorites)
        }
    }

    fun setSongRating(
        songId: String,
        rating: Int,
    ) {
        val song =_allSongs.value.firstOrNull {
            it.mediaMetadata.extras?.getString("navidromeID") == songId
        } ?: _searchResults.value.first {
            it.mediaMetadata.extras?.getString("navidromeID") == songId
        }

        val maxStars = (song.mediaMetadata.userRating as? StarRating)?.maxStars ?: 5

        val updatedSong = song.buildUpon().setMediaMetadata(
            song.mediaMetadata.buildUpon()
                .setUserRating(StarRating(maxStars, rating.toFloat()))
                .build()
        ).build()

        _allSongs.value = _allSongs.value.map { item ->
            if (item.mediaId == song.mediaId) updatedSong else item
        }
        _searchResults.value = _searchResults.value.map { item ->
            if (item.mediaId == song.mediaId) updatedSong else item
        }

        viewModelScope.launch {
            songRepository.setSongRating(songId, rating)
        }
    }
}