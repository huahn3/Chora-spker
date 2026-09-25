package com.craftworks.music.managers

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.craftworks.music.data.NavidromeProvider
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

@Serializable
data class DownloadedSongRecord(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val relativePath: String,
    val format: String = "mp3",
    val downloadId: Long = -1L,
    val downloadTime: Long = System.currentTimeMillis()
)

object DownloadedSongsManager {
    private const val TAG = "DOWNLOAD_MANAGER"
    private const val PREFS_NAME = "DownloadedSongsPrefs"
    private const val KEY_RECORDS = "downloaded_records"

    private lateinit var sharedPreferences: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient()

    private val _downloadedRecords = MutableStateFlow<List<DownloadedSongRecord>>(emptyList())
    val downloadedRecords: StateFlow<List<DownloadedSongRecord>> = _downloadedRecords.asStateFlow()

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadRecords()
    }

    private fun loadRecords() {
        try {
            val raw = sharedPreferences.getString(KEY_RECORDS, null)
            if (!raw.isNullOrBlank()) {
                val list: List<DownloadedSongRecord> = json.decodeFromString(raw)
                _downloadedRecords.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading downloaded records", e)
        }
    }

    private fun saveRecords() {
        try {
            val raw = json.encodeToString(_downloadedRecords.value)
            sharedPreferences.edit { putString(KEY_RECORDS, raw) }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving downloaded records", e)
        }
    }

    fun recordDownload(
        songId: String,
        title: String,
        artist: String,
        album: String,
        relativePath: String,
        format: String,
        downloadId: Long = -1L
    ) {
        val newRecord = DownloadedSongRecord(
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            relativePath = relativePath,
            format = format,
            downloadId = downloadId,
            downloadTime = System.currentTimeMillis()
        )
        _downloadedRecords.value = (_downloadedRecords.value.filter { it.songId != songId } + newRecord)
        saveRecords()
    }

    fun removeRecord(songId: String) {
        _downloadedRecords.value = _downloadedRecords.value.filter { it.songId != songId }
        saveRecords()
    }

    fun isDownloadedSong(songId: String): Boolean {
        return _downloadedRecords.value.any { it.songId == songId }
    }

    fun isDownloadedFile(file: File): Boolean {
        val path = file.absolutePath
        val choraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Chora").absolutePath
        if (path.startsWith(choraDir)) return true
        return _downloadedRecords.value.any {
            val recFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), it.relativePath)
            recFile.absolutePath == path
        }
    }

    /**
     * Pre-fetches companion .lrc lyrics and cover .jpg alongside the downloaded song so it is 100% offline ready.
     */
    fun fetchCompanionAssets(
        context: Context,
        server: NavidromeProvider,
        songId: String,
        title: String,
        artist: String,
        relativePath: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val audioFile = File(musicDir, relativePath)
            val parentDir = audioFile.parentFile ?: return@launch
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            val baseName = audioFile.nameWithoutExtension

            val passwordSalt = NavidromeDataSource.generateSalt(8)
            val passwordHash = NavidromeDataSource.md5Hash(server.password + passwordSalt)
            val baseUrl = server.activeBaseUrl ?: server.url

            // 1. Fetch & Save Cover Art
            try {
                val coverFile = File(parentDir, "$baseName.jpg")
                val genericCoverFile = File(parentDir, "cover.jpg")
                if (!coverFile.exists() || coverFile.length() == 0L) {
                    val coverUrl = "$baseUrl/rest/getCoverArt.view?&id=$songId&u=${server.username}&t=$passwordHash&s=$passwordSalt&v=1.16.1&c=Chora&size=500"
                    val request = Request.Builder().url(coverUrl).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                FileOutputStream(coverFile).use { it.write(bytes) }
                                if (!genericCoverFile.exists() || genericCoverFile.length() == 0L) {
                                    FileOutputStream(genericCoverFile).use { it.write(bytes) }
                                }
                                Log.d(TAG, "Saved companion cover: ${coverFile.absolutePath}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download companion cover", e)
            }

            // 2. Fetch & Save Synced Lyrics (.lrc) via multi-source fallback
            try {
                val lrcFile = File(parentDir, "$baseName.lrc")
                if (!lrcFile.exists() || lrcFile.length() == 0L) {
                    val lrcText = fetchLyricsMultiSource(baseUrl, server.username, passwordHash, passwordSalt, songId, title, artist)
                    if (!lrcText.isNullOrBlank()) {
                        lrcFile.writeText(lrcText)
                        // Also save to internal cache
                        val cacheDir = File(context.filesDir, "lyrics")
                        cacheDir.mkdirs()
                        if (title.isNotBlank() && artist.isNotBlank()) {
                            File(cacheDir, "${title}_${artist}.lrc").writeText(lrcText)
                        }
                        if (songId.isNotBlank()) {
                            File(cacheDir, "${songId}.lrc").writeText(lrcText)
                        }
                        Log.d(TAG, "Saved companion lyrics: ${lrcFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download companion lyrics", e)
            }
        }
    }

    /**
     * Standalone method to fetch and save lyrics for any downloaded or local file.
     */
    suspend fun fetchAndSaveLyricsForFile(
        context: Context,
        file: File,
        title: String,
        artist: String,
        songId: String? = null
    ): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val companionLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
        if (companionLrc.exists() && companionLrc.length() > 0L) {
            return@withContext true
        }

        val server = NavidromeManager.getCurrentServer()
        val (baseUrl, username, passwordHash, passwordSalt) = if (server != null) {
            val salt = NavidromeDataSource.generateSalt(8)
            val hash = NavidromeDataSource.md5Hash(server.password + salt)
            val base = server.activeBaseUrl ?: server.url
            listOf(base, server.username, hash, salt)
        } else {
            listOf("", "", "", "")
        }

        val lrcContent = fetchLyricsMultiSource(baseUrl, username, passwordHash, passwordSalt, songId ?: "", title, artist)
        if (!lrcContent.isNullOrBlank()) {
            try {
                companionLrc.writeText(lrcContent)
            } catch (_: Exception) {}
            val cacheDir = File(context.filesDir, "lyrics")
            cacheDir.mkdirs()
            if (title.isNotBlank() && artist.isNotBlank()) {
                File(cacheDir, "${title}_${artist}.lrc").writeText(lrcContent)
            }
            if (!songId.isNullOrBlank()) {
                File(cacheDir, "${songId}.lrc").writeText(lrcContent)
            }
            Log.d(TAG, "Saved lyrics for file: ${companionLrc.absolutePath}")
            return@withContext true
        }
        return@withContext false
    }

    private fun fetchLyricsMultiSource(
        baseUrl: String,
        username: String,
        passwordHash: String,
        passwordSalt: String,
        songId: String,
        title: String,
        artist: String
    ): String? {
        // Source 1: Navidrome Synced Lyrics (JSON)
        if (baseUrl.isNotBlank() && songId.isNotBlank() && !songId.startsWith("Local_")) {
            try {
                val url = "$baseUrl/rest/getLyricsBySongId.view?id=$songId&enhanced=true&u=$username&t=$passwordHash&s=$passwordSalt&v=1.16.1&c=Chora&f=json"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val lrc = parseNavidromeJsonLyrics(body)
                        if (!lrc.isNullOrBlank()) return lrc
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Navidrome synced lyrics failed: ${e.message}")
            }
        }

        // Source 2: Navidrome Plain Lyrics (JSON)
        if (baseUrl.isNotBlank() && title.isNotBlank() && artist.isNotBlank()) {
            try {
                val encTitle = URLEncoder.encode(title, "UTF-8")
                val encArtist = URLEncoder.encode(artist, "UTF-8")
                val url = "$baseUrl/rest/getLyrics.view?artist=$encArtist&title=$encTitle&u=$username&t=$passwordHash&s=$passwordSalt&v=1.16.1&c=Chora&f=json"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val lrc = parseNavidromeJsonLyrics(body)
                        if (!lrc.isNullOrBlank()) return lrc
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Navidrome plain lyrics failed: ${e.message}")
            }
        }

        // Source 3: LRCLIB
        if (title.isNotBlank()) {
            try {
                val encTitle = URLEncoder.encode(title, "UTF-8")
                val encArtist = URLEncoder.encode(artist, "UTF-8")
                val url = "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist"
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Chora-App")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val obj = JSONObject(body)
                        val synced = obj.optString("syncedLyrics", "")
                        if (synced.isNotBlank()) return synced
                        val plain = obj.optString("plainLyrics", "")
                        if (plain.isNotBlank()) return plain
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB failed: ${e.message}")
            }
        }

        // Source 4: NetEase Music
        if (title.isNotBlank()) {
            try {
                val query = if (artist.isNotBlank()) "$title $artist" else title
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://music.163.com/api/search/get?s=$encQuery&type=1&limit=1"
                val searchReq = Request.Builder().url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                httpClient.newCall(searchReq).execute().use { searchResp ->
                    if (searchResp.isSuccessful) {
                        val searchBody = searchResp.body?.string() ?: ""
                        val searchObj = JSONObject(searchBody)
                        val resultObj = searchObj.optJSONObject("result")
                        val songsArr = resultObj?.optJSONArray("songs")
                        val neteaseId = songsArr?.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                        if (neteaseId > 0) {
                            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$neteaseId&lv=-1&kv=-1&tv=-1"
                            val lyricReq = Request.Builder().url(lyricUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()
                            httpClient.newCall(lyricReq).execute().use { lyricResp ->
                                if (lyricResp.isSuccessful) {
                                    val lyricBody = lyricResp.body?.string() ?: ""
                                    val lyricObj = JSONObject(lyricBody)
                                    val lrcNode = lyricObj.optJSONObject("lrc")
                                    val lrcText = lrcNode?.optString("lyric", "") ?: ""
                                    if (lrcText.isNotBlank()) return lrcText
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "NetEase failed: ${e.message}")
            }
        }

        return null
    }

    private fun parseNavidromeJsonLyrics(jsonStr: String): String? {
        try {
            val root = JSONObject(jsonStr).optJSONObject("subsonic-response") ?: return null
            val lyricsList = root.optJSONObject("lyricsList")
            val structuredLyrics = lyricsList?.optJSONArray("structuredLyrics")
            if (structuredLyrics != null && structuredLyrics.length() > 0) {
                val firstStruct = structuredLyrics.getJSONObject(0)
                val lines = firstStruct.optJSONArray("line")
                if (lines != null && lines.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until lines.length()) {
                        val lineObj = lines.getJSONObject(i)
                        val startMs = lineObj.optLong("start", 0L)
                        val text = lineObj.optString("value", "").trim()
                        val min = startMs / 60000
                        val sec = (startMs % 60000) / 1000
                        val ms = (startMs % 1000) / 10
                        sb.append(String.format("[%02d:%02d.%02d]%s\n", min, sec, ms, text))
                    }
                    return sb.toString()
                }
            }

            // Fallback plain lyrics
            val plainLyricsObj = root.optJSONObject("lyrics")
            val plainValue = plainLyricsObj?.optString("value", "")
            if (!plainValue.isNullOrBlank()) {
                return plainValue
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Navidrome lyrics JSON", e)
        }
        return null
    }
}
