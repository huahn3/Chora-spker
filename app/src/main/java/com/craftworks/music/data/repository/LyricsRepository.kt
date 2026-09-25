package com.craftworks.music.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.lrclib.LrclibDataSource
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.data.datasource.netease.NeteaseDataSource
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.parseLrc
import com.craftworks.music.data.model.toLrcString
import com.craftworks.music.managers.NavidromeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

object LyricsState {
    val lyrics = MutableStateFlow<List<Lyric>>(emptyList())
    val loading = MutableStateFlow(false)
    var open = mutableStateOf(false)
    var useLrcLib by mutableStateOf(true)
    var useNetEase by mutableStateOf(false)
}

@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val lrclibDataSource: LrclibDataSource,
    val neteaseDataSource: NeteaseDataSource,
    val navidromeDataSource: NavidromeDataSource
) {
    private var lyricsFetchJob: Job? = null

    suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean = false) {
        if (metadata?.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION) {
            LyricsState.lyrics.value = listOf()
            return
        }

        lyricsFetchJob?.cancel()

        coroutineScope {
            lyricsFetchJob = launch {
                LyricsState.loading.value = true

                val path = metadata?.extras?.getString("path")
                val title = metadata?.title?.toString() ?: ""
                val artist = metadata?.artist?.toString() ?: ""
                val navidromeID = metadata?.extras?.getString("navidromeID") ?: ""

                // 1. Check offline / local lyrics first
                val localLyrics = withContext(Dispatchers.IO) {
                    findLocalLyrics(path, title, artist, navidromeID)
                }

                if (!localLyrics.isNullOrEmpty()) {
                    Log.d("LYRICS", "Loaded local/offline lyrics (${localLyrics.size} lines)")
                    LyricsState.lyrics.value = localLyrics
                    LyricsState.loading.value = false
                    return@launch
                }

                coroutineScope {
                    val isLocal =
                        metadata?.extras?.getString("navidromeID")?.startsWith("Local_") ?: false

                    val navidromeSyncedDeferred = async {
                        if (NavidromeManager.checkActiveServers() && !isLocal) {
                            try {
                                navidromeDataSource.getNavidromeSyncedLyrics(
                                    metadata?.extras?.getString("navidromeID") ?: "",
                                    ignoreCachedResponse
                                )
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    val navidromePlainDeferred = async {
                        if (NavidromeManager.checkActiveServers() && !isLocal) {
                            try {
                                navidromeDataSource.getNavidromePlainLyrics(
                                    metadata,
                                    ignoreCachedResponse
                                )
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    val lrcLibDeferred = async {
                        if (LyricsState.useLrcLib) {
                            try {
                                lrclibDataSource.getLrcLibLyrics(
                                    metadata,
                                    ignoreCachedResponse
                                )
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    val netEaseDeferred = async {
                        if (LyricsState.useNetEase) {
                            try {
                                neteaseDataSource.getNeteaseLyrics(metadata)
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    val navidromeSynced = navidromeSyncedDeferred.await().orEmpty()
                    val navidromePlain = navidromePlainDeferred.await().orEmpty()
                    val lrcLib = lrcLibDeferred.await().orEmpty()
                    var netEase = netEaseDeferred.await().orEmpty()

                    // If all primary sources empty and NetEase was not yet checked, try NetEase fallback
                    if (netEase.isEmpty() && lrcLib.isEmpty() && navidromeSynced.isEmpty() && navidromePlain.isEmpty()) {
                        netEase = try {
                            neteaseDataSource.getNeteaseLyrics(metadata)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                    val resultLyrics = when {
                        lrcLib.size > 1 -> {
                            Log.d("LYRICS", "Using LRCLIB Synced Lyrics")
                            lrcLib
                        }
                        navidromeSynced.size > 1 -> {
                            Log.d("LYRICS", "Got Navidrome synced lyrics")
                            navidromeSynced
                        }
                        netEase.size > 1 -> {
                            Log.d("LYRICS", "Using NetEase Synced Lyrics")
                            netEase
                        }
                        navidromePlain.isNotEmpty() -> {
                            Log.d("LYRICS", "Using Navidrome Plain Lyrics")
                            navidromePlain
                        }
                        lrcLib.isNotEmpty() -> {
                            Log.d("LYRICS", "Using LRCLIB Plain Lyrics")
                            lrcLib
                        }
                        netEase.isNotEmpty() -> {
                            Log.d("LYRICS", "Using NetEase Plain Lyrics")
                            netEase
                        }
                        else -> {
                            Log.d("LYRICS", "No lyrics found.")
                            listOf()
                        }
                    }

                    LyricsState.lyrics.value = resultLyrics
                    LyricsState.loading.value = false

                    // Cache for future offline use
                    if (resultLyrics.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            cacheLyricsOffline(path, title, artist, navidromeID, resultLyrics)
                        }
                    }
                }
            }
        }
    }

    private fun findLocalLyrics(
        path: String?,
        title: String,
        artist: String,
        navidromeID: String
    ): List<Lyric>? {
        // A. Check companion .lrc file in same directory
        if (!path.isNullOrBlank()) {
            val audioFile = File(path)
            val parent = audioFile.parentFile
            val baseName = audioFile.nameWithoutExtension

            val candidateLrcFiles = mutableListOf(
                File(parent, "$baseName.lrc"),
                File(parent, "$title - $artist.lrc"),
                File(parent, "$artist - $title.lrc"),
                File(parent, "$title.lrc")
            )

            for (candidate in candidateLrcFiles) {
                if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                    try {
                        val parsed = parseLrc(candidate.readText())
                        if (parsed.isNotEmpty()) return parsed
                    } catch (e: Exception) {
                        Log.w("LYRICS", "Failed to parse companion lrc file", e)
                    }
                }
            }
        }

        // B. Check internal app lyrics cache
        val cacheDir = File(context.filesDir, "lyrics")
        val cachedFiles = mutableListOf<File>()
        if (navidromeID.isNotEmpty()) {
            cachedFiles.add(File(cacheDir, "${navidromeID}.lrc"))
        }
        if (title.isNotEmpty() && artist.isNotEmpty()) {
            cachedFiles.add(File(cacheDir, "${title}_${artist}.lrc"))
        }
        for (cached in cachedFiles) {
            if (cached.exists() && cached.isFile && cached.length() > 0) {
                try {
                    val parsed = parseLrc(cached.readText())
                    if (parsed.isNotEmpty()) return parsed
                } catch (e: Exception) {
                    Log.w("LYRICS", "Failed to parse cached lrc file", e)
                }
            }
        }

        // C. Check embedded lyrics from audio file
        if (!path.isNullOrBlank()) {
            val audioFile = File(path)
            val embedded = extractEmbeddedLyrics(audioFile)
            if (!embedded.isNullOrBlank()) {
                val parsed = parseLrc(embedded)
                if (parsed.isNotEmpty()) return parsed
            }
        }

        return null
    }

    private fun cacheLyricsOffline(
        path: String?,
        title: String,
        artist: String,
        navidromeID: String,
        lyrics: List<Lyric>
    ) {
        try {
            val cacheDir = File(context.filesDir, "lyrics")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val sb = StringBuilder()
            for (lyric in lyrics) {
                if (lyric.startMs >= 0) {
                    val minutes = lyric.startMs / 60000
                    val seconds = (lyric.startMs % 60000) / 1000
                    val millis = (lyric.startMs % 1000) / 10
                    val text = lyric.text.joinToString("\n")
                    sb.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, millis, text))
                } else {
                    sb.append(lyric.text.joinToString("\n")).append("\n")
                }
            }
            val lrcContent = sb.toString()

            if (navidromeID.isNotEmpty()) {
                File(cacheDir, "${navidromeID}.lrc").writeText(lrcContent)
            }
            if (title.isNotEmpty() && artist.isNotEmpty()) {
                File(cacheDir, "${title}_${artist}.lrc").writeText(lrcContent)
            }

            if (!path.isNullOrBlank()) {
                val audioFile = File(path)
                val companionLrc = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                if (!companionLrc.exists() || companionLrc.length() == 0L) {
                    try {
                        companionLrc.writeText(lrcContent)
                    } catch (_: Exception) {
                        // May fail if directory is read-only
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LYRICS", "Error caching lyrics offline", e)
        }
    }

    suspend fun fetchAndSaveLyricsForSong(
        file: File,
        title: String,
        artist: String,
        songId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val companionLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
            if (companionLrc.exists() && companionLrc.length() > 0L) {
                return@withContext true
            }

            var result: List<Lyric> = emptyList()

            // 1. Try Navidrome if ID provided
            if (!songId.isNullOrBlank() && !songId.startsWith("Local_") && NavidromeManager.checkActiveServers()) {
                try {
                    result = navidromeDataSource.getNavidromeSyncedLyrics(songId, true)
                } catch (_: Exception) {}
            }

            // 2. Try LRCLIB
            if (result.isEmpty()) {
                try {
                    val dummyMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setExtras(android.os.Bundle().apply {
                            putString("lyricsArtist", artist)
                        })
                        .build()
                    result = lrclibDataSource.getLrcLibLyrics(dummyMetadata, true)
                } catch (_: Exception) {}
            }

            // 3. Try NetEase
            if (result.isEmpty()) {
                try {
                    val dummyMetadata = MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setExtras(android.os.Bundle().apply {
                            putString("lyricsArtist", artist)
                        })
                        .build()
                    result = neteaseDataSource.getNeteaseLyrics(dummyMetadata)
                } catch (_: Exception) {}
            }

            if (result.isNotEmpty()) {
                val lrcText = result.toLrcString()
                if (lrcText.isNotBlank()) {
                    try {
                        companionLrc.writeText(lrcText)
                    } catch (_: Exception) {}
                    val cacheDir = File(context.filesDir, "lyrics")
                    cacheDir.mkdirs()
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        File(cacheDir, "${title}_${artist}.lrc").writeText(lrcText)
                    }
                    if (!songId.isNullOrBlank()) {
                        File(cacheDir, "${songId}.lrc").writeText(lrcText)
                    }
                    Log.d("LYRICS", "Successfully fetched and saved lyrics for ${file.name}")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e("LYRICS", "Error fetching lyrics for file ${file.name}", e)
        }
        return@withContext false
    }

    private fun extractEmbeddedLyrics(file: File): String? {
        try {
            if (!file.exists() || file.length() < 128) return null
            val buffer = ByteArray(minOf(file.length().toInt(), 65536))
            java.io.FileInputStream(file).use { it.read(buffer) }
            val headerStr = String(buffer, Charsets.ISO_8859_1)
            val usltIdx = headerStr.indexOf("USLT")
            if (usltIdx != -1 && usltIdx + 10 < buffer.size) {
                val frameSize = (buffer[usltIdx + 4].toInt() and 0xFF shl 24) or
                        (buffer[usltIdx + 5].toInt() and 0xFF shl 16) or
                        (buffer[usltIdx + 6].toInt() and 0xFF shl 8) or
                        (buffer[usltIdx + 7].toInt() and 0xFF)
                val contentStart = usltIdx + 10
                val contentLen = minOf(frameSize, buffer.size - contentStart)
                if (contentLen > 0) {
                    val rawContent = String(buffer, contentStart, contentLen, Charsets.UTF_8)
                    val lyricsText = rawContent.substringAfter("\u0000\u0000", rawContent).trim { it <= ' ' }
                    if (lyricsText.isNotBlank()) return lyricsText
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }
}