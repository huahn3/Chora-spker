package com.craftworks.music.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.craftworks.music.MainActivity
import com.craftworks.music.R
import com.craftworks.music.player.ChoraMediaLibraryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MusicWidget4x2Provider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MusicWidgetManager.updateWidgets(context)
    }

    override fun onEnabled(context: Context) {
        MusicWidgetManager.updateWidgets(context)
    }
}

class MusicWidget2x2Provider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MusicWidgetManager.updateWidgets(context)
    }

    override fun onEnabled(context: Context) {
        MusicWidgetManager.updateWidgets(context)
    }
}

class MusicWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MusicWidgetManager.handleAction(context, intent.action)
    }
}

object MusicWidgetManager {
    const val ACTION_PLAY_PAUSE = "com.craftworks.music.action.WIDGET_PLAY_PAUSE"
    const val ACTION_PREVIOUS = "com.craftworks.music.action.WIDGET_PREVIOUS"
    const val ACTION_NEXT = "com.craftworks.music.action.WIDGET_NEXT"
    const val ACTION_UPDATE = "com.craftworks.music.action.WIDGET_UPDATE"

    private val scope = CoroutineScope(Dispatchers.Main)

    @OptIn(UnstableApi::class)
    fun handleAction(context: Context, action: String?) {
        val service = ChoraMediaLibraryService.getInstance()
        val player = service?.player

        if (player != null) {
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }
                ACTION_PREVIOUS -> {
                    player.seekToPrevious()
                }
                ACTION_NEXT -> {
                    player.seekToNext()
                }
            }
        } else {
            // Service not running: send standard media button intent to wake/start playback
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java)
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            context.sendBroadcast(mediaButtonIntent)
        }

        updateWidgets(context)
    }

    @OptIn(UnstableApi::class)
    fun updateWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val component4x2 = ComponentName(context, MusicWidget4x2Provider::class.java)
        val component2x2 = ComponentName(context, MusicWidget2x2Provider::class.java)

        val ids4x2 = appWidgetManager.getAppWidgetIds(component4x2)
        val ids2x2 = appWidgetManager.getAppWidgetIds(component2x2)

        if (ids4x2.isEmpty() && ids2x2.isEmpty()) return

        val service = ChoraMediaLibraryService.getInstance()
        val player = service?.player
        val currentMediaItem = player?.currentMediaItem
        val isPlaying = player?.isPlaying == true

        val title = currentMediaItem?.mediaMetadata?.title?.toString()
            ?: context.getString(R.string.widget_no_playing)
        val artist = currentMediaItem?.mediaMetadata?.artist?.toString()
            ?: context.getString(R.string.widget_tap_to_open)
        val artworkUri = currentMediaItem?.mediaMetadata?.artworkUri

        scope.launch {
            val bitmap = loadArtwork(context, artworkUri)

            // Setup common PendingIntents
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val prevPendingIntent = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, MusicWidgetActionReceiver::class.java).apply { action = ACTION_PREVIOUS },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val playPendingIntent = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, MusicWidgetActionReceiver::class.java).apply { action = ACTION_PLAY_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nextPendingIntent = PendingIntent.getBroadcast(
                context, 3,
                Intent(context, MusicWidgetActionReceiver::class.java).apply { action = ACTION_NEXT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Update 4x2 widgets
            if (ids4x2.isNotEmpty()) {
                val views4x2 = RemoteViews(context.packageName, R.layout.widget_4x2).apply {
                    setTextViewText(R.id.widget_song_title, title)
                    setTextViewText(R.id.widget_song_artist, artist)
                    setImageViewResource(
                        R.id.widget_btn_play_pause,
                        if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
                    )

                    if (bitmap != null) {
                        setImageViewBitmap(R.id.widget_album_art, bitmap)
                    } else {
                        setImageViewResource(R.id.widget_album_art, R.drawable.albumplaceholder)
                    }

                    setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                    setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_previous, prevPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_play_pause, playPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_next, nextPendingIntent)
                }
                appWidgetManager.updateAppWidget(ids4x2, views4x2)
            }

            // Update 2x2 widgets
            if (ids2x2.isNotEmpty()) {
                val views2x2 = RemoteViews(context.packageName, R.layout.widget_2x2).apply {
                    setTextViewText(R.id.widget_song_title, title)
                    setTextViewText(R.id.widget_song_artist, artist)
                    setImageViewResource(
                        R.id.widget_btn_play_pause,
                        if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
                    )

                    if (bitmap != null) {
                        setImageViewBitmap(R.id.widget_album_art, bitmap)
                    } else {
                        setImageViewResource(R.id.widget_album_art, R.drawable.albumplaceholder)
                    }

                    setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                    setOnClickPendingIntent(R.id.widget_album_art, openAppPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_previous, prevPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_play_pause, playPendingIntent)
                    setOnClickPendingIntent(R.id.widget_btn_next, nextPendingIntent)
                }
                appWidgetManager.updateAppWidget(ids2x2, views2x2)
            }
        }
    }

    private suspend fun loadArtwork(context: Context, uri: Uri?): Bitmap? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        try {
            val uriString = uri.toString()
            val rawBitmap = when {
                uriString.startsWith("http://") || uriString.startsWith("https://") -> {
                    val url = URL(uriString)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.doInput = true
                    conn.connect()
                    val inputStream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    conn.disconnect()
                    bmp
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        val stream = context.contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(stream)
                        stream?.close()
                        bmp
                    }
                }
            } ?: return@withContext null

            // Scale to 256x256 to fit neatly into IPC RemoteViews binder buffer
            val size = 256
            val scaled = Bitmap.createScaledBitmap(rawBitmap, size, size, true)
            getRoundedCornerBitmap(scaled, 32f)
        } catch (e: Exception) {
            Log.w("WIDGET", "Could not load artwork for widget: ${e.message}")
            null
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }
}
