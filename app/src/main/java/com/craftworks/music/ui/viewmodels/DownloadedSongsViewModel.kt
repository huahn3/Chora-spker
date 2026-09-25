package com.craftworks.music.ui.viewmodels

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.craftworks.music.R
import com.craftworks.music.managers.DownloadedSongsManager
import com.craftworks.music.managers.LocalProviderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

enum class DownloadedMusicFilter {
    ALL,
    DOWNLOADED,
    LOCAL_FOLDERS
}

@HiltViewModel
class DownloadedSongsViewModel @Inject constructor(
    private val app: Application
) : AndroidViewModel(app) {

    private val _songs = MutableStateFlow<List<MediaItem>>(emptyList())
    val songs: StateFlow<List<MediaItem>> = _songs.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(DownloadedMusicFilter.ALL)
    val selectedFilter: StateFlow<DownloadedMusicFilter> = _selectedFilter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSyncingLyrics = MutableStateFlow(false)
    val isSyncingLyrics: StateFlow<Boolean> = _isSyncingLyrics.asStateFlow()

    val localFolders: StateFlow<List<String>> = LocalProviderManager.allFolders

    val filteredSongs: StateFlow<List<MediaItem>> = combine(
        _songs,
        _searchQuery,
        _selectedFilter
    ) { songList, query, filter ->
        var list = songList

        // Filter by category
        list = when (filter) {
            DownloadedMusicFilter.ALL -> list
            DownloadedMusicFilter.DOWNLOADED -> list.filter {
                it.mediaMetadata.extras?.getBoolean("isDownloaded", false) == true
            }
            DownloadedMusicFilter.LOCAL_FOLDERS -> list.filter {
                it.mediaMetadata.extras?.getBoolean("isLocalFolder", false) == true
            }
        }

        // Filter by search query
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { item ->
                val title = item.mediaMetadata.title?.toString()?.lowercase() ?: ""
                val artist = item.mediaMetadata.artist?.toString()?.lowercase() ?: ""
                val album = item.mediaMetadata.albumTitle?.toString()?.lowercase() ?: ""
                title.contains(q) || artist.contains(q) || album.contains(q)
            }
        }

        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadDownloadedSongs()

        // Auto reload when local folders list changes
        viewModelScope.launch {
            LocalProviderManager.allFolders.collect {
                loadDownloadedSongs()
            }
        }

        // Auto reload when downloaded records change
        viewModelScope.launch {
            DownloadedSongsManager.downloadedRecords.collect {
                loadDownloadedSongs()
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: DownloadedMusicFilter) {
        _selectedFilter.value = filter
    }

    fun addLocalFolder(path: String) {
        if (path.isNotBlank()) {
            LocalProviderManager.addFolder(path)
        }
    }

    fun removeLocalFolder(path: String) {
        if (path.isNotBlank()) {
            LocalProviderManager.removeFolder(path)
        }
    }

    fun loadDownloadedSongs() {
        viewModelScope.launch {
            _isLoading.value = true
            val items = withContext(Dispatchers.IO) {
                queryOfflineAudio()
            }
            _songs.value = items
            _isLoading.value = false
        }
    }

    private fun queryOfflineAudio(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val seenPaths = mutableSetOf<String>()

        // 1. App-Downloaded Music: Environment.DIRECTORY_MUSIC/Chora
        try {
            val choraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Chora")
            if (choraDir.exists() && choraDir.isDirectory) {
                scanDirectory(choraDir, isDownloaded = true, seenPaths, result)
            }

            // Also check app external files directory for music
            val appMusicDir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (appMusicDir != null && appMusicDir.exists() && appMusicDir.isDirectory) {
                scanDirectory(appMusicDir, isDownloaded = true, seenPaths, result)
            }

            // Check any specifically recorded downloaded files (e.g. from previous downloads)
            for (rec in DownloadedSongsManager.downloadedRecords.value) {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), rec.relativePath)
                if (file.exists() && file.isFile && file.absolutePath !in seenPaths) {
                    processAudioFile(file, isDownloaded = true, seenPaths, result)
                }
            }
        } catch (e: Exception) {
            Log.e("DOWNLOADED_VM", "Error scanning downloaded music", e)
        }

        // 2. Custom Folders added by user via LocalProviderManager
        try {
            val folders = LocalProviderManager.getAllFolders()
            for (folderPath in folders) {
                val folder = File(folderPath)
                if (folder.exists() && folder.isDirectory) {
                    scanDirectory(folder, isDownloaded = false, seenPaths, result)
                }
            }
        } catch (e: Exception) {
            Log.e("DOWNLOADED_VM", "Error scanning user local folders", e)
        }

        return result
    }

    private fun scanDirectory(
        dir: File,
        isDownloaded: Boolean,
        seenPaths: MutableSet<String>,
        result: MutableList<MediaItem>
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                // Avoid scanning android hidden or system dirs
                if (!file.name.startsWith(".")) {
                    scanDirectory(file, isDownloaded, seenPaths, result)
                }
            } else if (file.isFile) {
                processAudioFile(file, isDownloaded, seenPaths, result)
            }
        }
    }

    private fun processAudioFile(
        file: File,
        isDownloaded: Boolean,
        seenPaths: MutableSet<String>,
        result: MutableList<MediaItem>
    ) {
        val audioExtensions = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac")
        val ext = file.extension.lowercase()
        if (ext !in audioExtensions) return
        if (file.absolutePath in seenPaths) return
        seenPaths.add(file.absolutePath)

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val bitrate = bitrateStr?.toIntOrNull() ?: 0
            val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val year = yearStr?.toIntOrNull() ?: 0
            val trackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val track = trackStr?.toIntOrNull() ?: 0

            // Resolve Artwork
            val artworkUri = resolveArtwork(file, retriever)
            retriever.release()

            // Check companion .lrc file or cache
            val companionLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
            val cacheLrc = File(app.filesDir, "lyrics/${title}_${artist}.lrc")
            val hasLyrics = (companionLrc.exists() && companionLrc.length() > 0) ||
                    (cacheLrc.exists() && cacheLrc.length() > 0)

            val fileUri = Uri.fromFile(file)
            val formatName = ext.uppercase()

            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(artist)
                .setArtworkUri(artworkUri)
                .setDurationMs(duration)
                .setRecordingYear(year)
                .setTrackNumber(track)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    putString("navidromeID", "Local_${file.absolutePath.hashCode()}")
                    putString("lyricsArtist", artist)
                    putString("path", file.absolutePath)
                    putString("format", formatName)
                    putLong("bitrate", (bitrate / 1000).toLong())
                    putLong("size", file.length())
                    putBoolean("isDownloaded", isDownloaded)
                    putBoolean("isLocalFolder", !isDownloaded)
                    putBoolean("hasLyrics", hasLyrics)
                    putString("sourceType", if (isDownloaded) "downloaded" else "local_folder")
                    putString("folderPath", file.parentFile?.absolutePath ?: "")
                })
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(fileUri.toString())
                .setUri(fileUri)
                .setMediaMetadata(mediaMetadata)
                .build()

            result.add(mediaItem)
        } catch (e: Exception) {
            Log.w("DOWNLOADED_VM", "Error reading file metadata for ${file.name}", e)
        }
    }

    private fun resolveArtwork(file: File, retriever: MediaMetadataRetriever): Uri {
        // 1. Companion cover file in directory
        val parent = file.parentFile
        if (parent != null) {
            val baseName = file.nameWithoutExtension
            val candidateImages = listOf(
                File(parent, "$baseName.jpg"),
                File(parent, "$baseName.png"),
                File(parent, "cover.jpg"),
                File(parent, "cover.png"),
                File(parent, "folder.jpg"),
                File(parent, "folder.png")
            )
            for (cand in candidateImages) {
                if (cand.exists() && cand.isFile && cand.length() > 0) {
                    return Uri.fromFile(cand)
                }
            }
        }

        // 2. Embedded picture
        try {
            val picBytes = retriever.embeddedPicture
            if (picBytes != null && picBytes.isNotEmpty()) {
                val coversDir = File(app.cacheDir, "covers")
                if (!coversDir.exists()) coversDir.mkdirs()
                val cachedCover = File(coversDir, "local_${file.absolutePath.hashCode()}.jpg")
                if (!cachedCover.exists() || cachedCover.length() == 0L) {
                    FileOutputStream(cachedCover).use { it.write(picBytes) }
                }
                return Uri.fromFile(cachedCover)
            }
        } catch (e: Exception) {
            Log.w("DOWNLOADED_VM", "Error extracting embedded artwork for ${file.name}", e)
        }

        // 3. Fallback placeholder
        return "android.resource://com.craftworks.music/${R.drawable.albumplaceholder}".toUri()
    }

    fun deleteSong(mediaItem: MediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val path = mediaItem.mediaMetadata.extras?.getString("path")
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                        }
                        // Also delete companion .lrc if exists
                        val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                        if (lrcFile.exists()) {
                            lrcFile.delete()
                        }
                        // Remove from DownloadedSongsManager record
                        val songId = mediaItem.mediaMetadata.extras?.getString("navidromeID") ?: ""
                        DownloadedSongsManager.removeRecord(songId)
                    }
                    val uri = mediaItem.mediaId.toUri()
                    if (uri.scheme == "content") {
                        app.contentResolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    Log.e("DOWNLOADED_VM", "Error deleting song", e)
                }
            }
            loadDownloadedSongs()
        }
    }

    fun fetchLyricsForSong(song: MediaItem, onFinished: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val path = song.mediaMetadata.extras?.getString("path")
            val title = song.mediaMetadata.title?.toString() ?: ""
            val artist = song.mediaMetadata.artist?.toString() ?: ""
            if (path.isNullOrBlank()) {
                onFinished?.invoke(false)
                return@launch
            }
            val file = File(path)
            val originalSongId = DownloadedSongsManager.downloadedRecords.value.find {
                it.title.equals(title, ignoreCase = true) && it.artist.equals(artist, ignoreCase = true)
            }?.songId

            val success = DownloadedSongsManager.fetchAndSaveLyricsForFile(
                context = app,
                file = file,
                title = title,
                artist = artist,
                songId = originalSongId
            )
            if (success) {
                loadDownloadedSongs()
            }
            onFinished?.invoke(success)
        }
    }

    fun syncAllMissingLyrics(onFinished: ((Int, Int) -> Unit)? = null) {
        viewModelScope.launch {
            _isSyncingLyrics.value = true
            var successCount = 0
            var failCount = 0
            val missingSongs = _songs.value.filter {
                it.mediaMetadata.extras?.getBoolean("hasLyrics", false) == false
            }

            for (song in missingSongs) {
                val path = song.mediaMetadata.extras?.getString("path")
                val title = song.mediaMetadata.title?.toString() ?: ""
                val artist = song.mediaMetadata.artist?.toString() ?: ""
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    val originalSongId = DownloadedSongsManager.downloadedRecords.value.find {
                        it.title.equals(title, ignoreCase = true) && it.artist.equals(artist, ignoreCase = true)
                    }?.songId

                    val ok = DownloadedSongsManager.fetchAndSaveLyricsForFile(
                        context = app,
                        file = file,
                        title = title,
                        artist = artist,
                        songId = originalSongId
                    )
                    if (ok) successCount++ else failCount++
                }
            }

            _isSyncingLyrics.value = false
            if (successCount > 0) {
                loadDownloadedSongs()
            }
            onFinished?.invoke(successCount, failCount)
        }
    }
}
