package com.craftworks.music.providers.navidrome

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.craftworks.music.R
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import com.craftworks.music.managers.NavidromeManager.getCurrentServer
import java.io.File

@OptIn(UnstableApi::class)
fun downloadNavidromeSong(
    context: Context,
    song: MediaMetadata,
    albumName: String? = null
) {
    val server = getCurrentServer() ?: return

    val passwordSalt = NavidromeDataSource.generateSalt(8)
    val passwordHash = NavidromeDataSource.md5Hash(server.password + passwordSalt)
    val baseUrl = server.activeBaseUrl ?: server.url
    val url = "$baseUrl/rest/download.view?id=${song.extras?.getString("navidromeID")}&u=${server.username}&t=$passwordHash&s=$passwordSalt&v=1.16.1&c=Chora".toUri()

    val extension = song.extras?.getString("format") ?: "mp3"
    val songId = song.extras?.getString("navidromeID") ?: ""
    val title = song.title?.toString() ?: "Unknown"
    val artist = song.artist?.toString() ?: "Unknown"
    val album = song.albumTitle?.toString() ?: albumName ?: "Unknown"
    val fileName = "$title - $artist.$extension"

    val relativePath = if (!albumName.isNullOrBlank()) {
        "Chora${File.separator}$albumName${File.separator}$fileName"
    } else {
        "Chora${File.separator}$fileName"
    }

    val request = DownloadManager.Request(url)
        .setTitle("${context.getString(R.string.Notification_Download_Name)} $title")
        .setDescription(context.getString(R.string.Notification_Download_Desc))
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, relativePath)

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = manager.enqueue(request)

    com.craftworks.music.managers.DownloadedSongsManager.recordDownload(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        relativePath = relativePath,
        format = extension,
        downloadId = downloadId
    )

    com.craftworks.music.managers.DownloadedSongsManager.fetchCompanionAssets(
        context = context,
        server = server,
        songId = songId,
        title = title,
        artist = artist,
        relativePath = relativePath
    )
}