package com.craftworks.music.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.util.fastFilter
import androidx.core.math.MathUtils.clamp
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.StarRating
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.craftworks.music.MainActivity
import com.craftworks.music.R
import com.craftworks.music.data.model.toMediaItem
import com.craftworks.music.data.repository.AlbumRepository
import com.craftworks.music.data.repository.ArtistRepository
import com.craftworks.music.data.repository.LyricsRepository
import com.craftworks.music.data.repository.LyricsState
import com.craftworks.music.data.repository.PlaylistRepository
import com.craftworks.music.data.repository.RadioRepository
import com.craftworks.music.data.repository.SongRepository
import com.craftworks.music.managers.NavidromeManager
import com.craftworks.music.managers.JukeboxManager
import com.craftworks.music.widgets.MusicWidgetManager
import com.craftworks.music.managers.TranscodeManager
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import com.craftworks.music.managers.settings.PlaybackSettingsManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.pow

/*
    Thanks to Yurowitz on StackOverflow for this! Used it as a template.
    https://stackoverflow.com/questions/76838126/can-i-define-a-medialibraryservice-without-an-app
*/

@UnstableApi
@AndroidEntryPoint
class ChoraMediaLibraryService : MediaLibraryService() {
    //region Vars
    lateinit var player: Player
    var session: MediaLibrarySession? = null

    private var scrobbleJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var networkRecoveryJob: Job? = null
    /** 10~15s `playing` heartbeat for Playback Handoff (TTL = remaining + 5s). */
    private var playbackReportJob: Job? = null
    private var _sleepTimerRemainingTime = MutableStateFlow(0)
    val sleepTimerRemainingTime: StateFlow<Int> = _sleepTimerRemainingTime.asStateFlow()

    @Inject lateinit var appearanceSettingsManager: AppearanceSettingsManager
    @Inject lateinit var playbackSettingsManager: PlaybackSettingsManager
    @Inject lateinit var transcodeManager: TranscodeManager

    @Inject lateinit var albumRepository: AlbumRepository
    @Inject lateinit var artistRepository: ArtistRepository
    @Inject lateinit var songRepository: SongRepository
    @Inject lateinit var radioRepository: RadioRepository
    @Inject lateinit var playlistRepository: PlaylistRepository
    @Inject lateinit var lyricsRepository: LyricsRepository

    companion object {
        private var instance: ChoraMediaLibraryService? = null

        /** Sentinel meaning "stream as-is", and the fallback when the transcode
         *  settings flow hasn't published within the resolver's budget. */
        private const val NO_TRANSCODING = "No Transcoding"
        private const val DEFAULT_TRANSCODING_FORMAT = "mp3"

        /** Redirect probe cache: [full URI -> (resolved URI, written-at millis)]. */
        private const val REDIRECT_CACHE_TTL_MS = 5 * 60_000L
        private const val REDIRECT_CACHE_MAX_ENTRIES = 32
        private const val REDIRECT_CONNECT_TIMEOUT_MS = 1_500
        private const val REDIRECT_READ_TIMEOUT_MS = 1_500

        private val redirectCacheLock = Any()
        private val redirectCache = LinkedHashMap<String, Pair<String, Long>>(8, 0.75f, true)

        fun getInstance(): ChoraMediaLibraryService? {
            return instance
        }
    }

    private val rootItem = MediaItem.Builder()
        .setMediaId("nodeROOT")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private val homeItem = MediaItem.Builder()
        .setMediaId("nodeHOME")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                .setTitle("Home")
                .setExtras(Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                    )
                })
                .build()
        )
        .build()

    private val albumsItem = MediaItem.Builder()
        .setMediaId("nodeALBUMS")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .setTitle("Albums")
                .build()
        )
        .build()

    private val artistsItem = MediaItem.Builder()
        .setMediaId("nodeARTISTS")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
                .setTitle("Artists")
                .build()
        )
        .build()

    private val radiosItem = MediaItem.Builder()
        .setMediaId("nodeRADIOS")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .setTitle("Radios")
                .build()
        )
        .build()

    private val playlistsItem = MediaItem.Builder()
        .setMediaId("nodePLAYLISTS")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .setTitle("Playlists")
                .build()
        )
        .build()

    private var rootHierarchy = mutableListOf<MediaItem>()

    private val serviceMainScope = CoroutineScope(Dispatchers.Main)
    private val serviceIOScope = CoroutineScope(Dispatchers.IO)

    // Deliberately NOT cancelled in onDestroy: the final state/queue write is
    // launched from there and must still land after the service scopes die.
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var aHomeScreenItems = mutableListOf<MediaItem>()
    var aAlbumScreenItems = mutableListOf<MediaItem>()
    var aArtistsScreenItems = mutableListOf<MediaItem>()
    var aRadioScreenItems = mutableListOf<MediaItem>()
    var aPlaylistScreenItems = mutableListOf<MediaItem>()

    var aFolderSongs = mutableListOf<MediaItem>()

    //endregion

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        instance = this

        Log.d("AA", "onCreate Android Auto")

        if (session == null)
            initializePlayer()
        else
            Log.d("AA", "MediaSession already initialized, not recreating")
    }

    //region Playback Handoff reporting

    /** Audible output right now: a remote Jukebox speaker id, or this device. */
    private fun currentOutputDevice(): String =
        if (JukeboxManager.isRemoteActive.value) JukeboxManager.selectedDeviceId.value else "browser"

    /** Server vocabulary for the loop mode is `single` / `all` / `order`. */
    private fun currentPlayMode(): String = when (player.repeatMode) {
        Player.REPEAT_MODE_ONE -> "single"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "order"
    }

    /**
     * Volume a takeover should inherit. With a Jukebox speaker the local player
     * is hard-muted, so `player.volume` would report 0 → clamped to 1% and the
     * taking-over device would start almost silent. Report the speaker's own
     * volume (kept in sync by [JukeboxManager]) in that case.
     */
    private fun currentVolumePercent(): Int =
        if (JukeboxManager.isRemoteActive.value) JukeboxManager.deviceVolume.value.coerceIn(1, 100)
        else (player.volume * 100f).toInt().coerceIn(1, 100)

    /**
     * Handoff metadata sent with every `reportPlayback`. Grouped so no call
     * path can send half of it.
     */
    private data class HandoffFields(
        val outputDevice: String,
        val volume: Int,
        val playMode: String,
        val bilingualActive: Boolean
    )

    /**
     * MUST be called on the player's thread (main): [currentPlayMode] and
     * [currentVolumePercent] read ExoPlayer, which throws
     * `IllegalStateException: Player is accessed on the wrong thread` from any
     * other thread — and that is an uncaught crash, not a warning.
     */
    private fun captureHandoffFields() = HandoffFields(
        outputDevice = currentOutputDevice(),
        volume = currentVolumePercent(),
        playMode = currentPlayMode(),
        bilingualActive = LyricsState.isTranslationEnabled.value
    )

    /**
     * Single funnel for every `/rest/reportPlayback` call so the handoff
     * fields (outputDevice / volume / playMode / bilingualActive) can't be
     * present on one code path and missing on another.
     *
     * Callers must be on the player's thread (main) — player callbacks and
     * [serviceMainScope] qualify, [serviceIOScope] does not.
     */
    private fun reportPlaybackState(state: String, positionMs: Long) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaMetadata.extras?.getString("navidromeID")
        if (mediaId.isNullOrBlank() || mediaId.startsWith("Local") ||
            mediaItem.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION
        ) return

        val fields = captureHandoffFields()

        serviceIOScope.launch {
            songRepository.reportPlayback(
                songId = mediaId,
                state = state,
                positionMs = positionMs,
                outputDevice = fields.outputDevice,
                volume = fields.volume,
                playMode = fields.playMode,
                bilingualActive = fields.bilingualActive
            )
        }
    }

    /**
     * Inline variant of [reportPlaybackState] for callers that must report from
     * inside their own coroutine (`onTaskRemoved` / `onDestroy` wrap it in
     * `NonCancellable` + `withTimeoutOrNull`, `STATE_ENDED` reports the exact
     * item that ended rather than re-reading the player). They still have to
     * carry the handoff fields, otherwise the session another device reads
     * loses its volume / output / mode.
     *
     * Called from IO coroutines, so the field snapshot hops to the player's
     * thread: reading `player.volume` / `player.repeatMode` off it throws an
     * uncaught `IllegalStateException` and kills the process.
     */
    private suspend fun reportPlaybackInline(songId: String, state: String, positionMs: Long) {
        val fields = withContext(Dispatchers.Main) { captureHandoffFields() }
        songRepository.reportPlayback(
            songId = songId,
            state = state,
            positionMs = positionMs,
            outputDevice = fields.outputDevice,
            volume = fields.volume,
            playMode = fields.playMode,
            bilingualActive = fields.bilingualActive
        )
    }

    /**
     * 12s `playing` heartbeat. Session TTL is (remaining track + 5s), so a
     * longer interval drops us out of the list before the song ends.
     *
     * Runs on [serviceMainScope] because it reads the player; the network call
     * it triggers is dispatched to [serviceIOScope] by [reportPlaybackState].
     */
    private fun startPlaybackHeartbeat() {
        if (playbackReportJob?.isActive == true) return
        playbackReportJob = serviceMainScope.launch {
            while (isActive) {
                delay(12_000)
                if (!player.isPlaying) break
                reportPlaybackState("playing", player.currentPosition)
            }
        }
    }

    private fun stopPlaybackHeartbeat() {
        playbackReportJob?.cancel()
        playbackReportJob = null
    }

    //endregion

    @OptIn(UnstableApi::class)
    fun initializePlayer() {
        serviceIOScope.launch {
            appearanceSettingsManager.bottomNavItemsFlow.collect { items ->
                val routeToItem = mapOf(
                    "home_screen" to homeItem,
                    "album_screen" to albumsItem,
                    "artists_screen" to artistsItem,
                    "radio_screen" to radiosItem,
                    "playlist_screen" to playlistsItem
                )

                rootHierarchy = items
                    .filter { it.enabled }
                    .mapNotNull { routeToItem[it.screenRoute] }
                    .toMutableList()

                session?.notifyChildrenChanged("nodeROOT", 0, null)
            }
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)

        val resolvingDataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, httpDataSourceFactory),
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    var uri = dataSpec.uri

                    if (uri.path?.contains("stream") == true) {
                        val server = NavidromeManager.getCurrentServer()
                        if (server != null) {
                            val activeUrl = if (!server.activeBaseUrl.isNullOrBlank()) {
                                server.activeBaseUrl!!
                            } else {
                                kotlinx.coroutines.runBlocking {
                                    kotlinx.coroutines.withTimeoutOrNull(2500) {
                                        NavidromeManager.resolveActiveServerUrl(server, forceRefresh = false)
                                    } ?: (server.activeBaseUrl ?: server.url)
                                }
                            }
                            try {
                                val activeUri = Uri.parse(activeUrl)
                                if (!activeUri.host.isNullOrBlank() && (uri.host != activeUri.host || uri.port != activeUri.port || uri.scheme != activeUri.scheme)) {
                                    uri = uri.buildUpon()
                                        .scheme(activeUri.scheme)
                                        .encodedAuthority(activeUri.encodedAuthority)
                                        .build()
                                }
                            } catch (e: Exception) {
                                Log.w("MusicService", "Error resolving active stream URI: ${e.message}")
                            }
                        }

                        // Clean up any duplicate leading slashes in path (e.g. "//rest/stream.view")
                        val rawPath = uri.path
                        if (rawPath != null && rawPath.startsWith("//")) {
                            uri = uri.buildUpon()
                                .path(rawPath.replaceFirst(Regex("^/+"), "/"))
                                .build()
                        }

                        // Follow any HTTP 301/302 redirects (e.g. Cloudflare redirection to real backend port/host)
                        uri = followHttpRedirects(uri)

                        // resolveDataSpec is a blocking ExoPlayer loader-thread
                        // callback, so runBlocking is unavoidable here — but it must
                        // be BOUNDED: an unbounded `first()` on these flows stalled
                        // track start-up indefinitely when the transcode manager
                        // hadn't published yet.
                        val bitrate = runBlocking {
                            withTimeoutOrNull(500) { transcodeManager.currentBitrateFlow.first() }
                        } ?: NO_TRANSCODING
                        if (bitrate == NO_TRANSCODING)
                            return dataSpec.withUri(uri)

                        val format = runBlocking {
                            withTimeoutOrNull(500) { transcodeManager.currentFormatFlow.first() }
                        } ?: DEFAULT_TRANSCODING_FORMAT

                        val newUri = uri.buildUpon()
                            .appendQueryParameter("format", format)
                            .appendQueryParameter("maxBitRate", bitrate)
                            .build()

                        return dataSpec.withUri(newUri)
                    }
                    return dataSpec
                }
            }
        )

        player = ExoPlayer.Builder(this)
            .setSeekParameters(SeekParameters.EXACT)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory))
            .setWakeMode(
                if (NavidromeManager.checkActiveServers())
                    C.WAKE_MODE_NETWORK
                else
                    C.WAKE_MODE_LOCAL
            )
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false

        var playerScrobbled = false

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Apply ReplayGain
                if (mediaItem?.mediaMetadata?.extras?.getFloat("replayGain") != null) {
                    player.volume = clamp(
                        (10f.pow(
                            ((mediaItem.mediaMetadata.extras?.getFloat("replayGain") ?: 0f) / 20f)
                        )), 0f, 1f
                    )
                    Log.d("REPLAY GAIN", "Setting ReplayGain to ${player.volume}")
                }

                playerScrobbled = false

                super.onMediaItemTransition(mediaItem, reason)

                MusicWidgetManager.updateWidgets(this@ChoraMediaLibraryService)

                val isPlayingNow = player.isPlaying
                val mediaMetadata = mediaItem?.mediaMetadata
                val mediaId = mediaMetadata?.extras?.getString("navidromeID")

                // Jukebox remote output: forward track change and keep local player muted
                if (JukeboxManager.isRemoteActive.value) {
                    player.volume = 0f
                    if (!mediaId.isNullOrBlank() && !mediaId.startsWith("Local")) {
                        // A handoff takeover carries the victim's position in the
                        // extras; without it the speaker would restart at 0:00
                        // (the local item is created at 0 and seeked afterwards).
                        val handoffStartMs = mediaMetadata.extras?.getLong("handoffStartMs", 0L) ?: 0L
                        JukeboxManager.playSong(mediaId, handoffStartMs / 1000L)
                    }
                }

                serviceIOScope.launch {
                    lyricsRepository.getLyrics(mediaMetadata)
                    if (mediaId != null && isPlayingNow) {
                        songRepository.scrobbleSong(mediaId, false)
                    }
                }

                if (isPlayingNow) {
                    // Handoff: announce the new track, then settle into `playing`.
                    // Kept on the main thread — reportPlaybackState reads the
                    // player, and ExoPlayer throws on foreign threads.
                    reportPlaybackState("starting", 0L)
                    reportPlaybackState("playing", 0L)
                }

                if (isPlayingNow) startPlaybackHeartbeat() else stopPlaybackHeartbeat()

                saveState(sync = false)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                    player.prepare()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                MusicWidgetManager.updateWidgets(this@ChoraMediaLibraryService)

                // Jukebox remote output: forward pause/resume and keep local muted
                if (JukeboxManager.isRemoteActive.value) {
                    if (isPlaying) {
                        player.volume = 0f
                        JukeboxManager.control("resume")
                    } else {
                        JukeboxManager.control("pause")
                    }
                }

                val mediaItem = player.currentMediaItem
                val mediaId = mediaItem?.mediaMetadata?.extras?.getString("navidromeID")
                val currentPosition = player.currentPosition

                if (!mediaId.isNullOrBlank() && !mediaId.startsWith("Local") &&
                    mediaItem.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION
                ) {
                    if (isPlaying) {
                        val id = mediaId
                        serviceIOScope.launch { songRepository.scrobbleSong(id, false) }
                    }
                    // Reports with the full handoff field set (output/volume/mode/bilingual).
                    reportPlaybackState(if (isPlaying) "playing" else "paused", currentPosition)
                }

                if (isPlaying) startPlaybackHeartbeat() else stopPlaybackHeartbeat()

                if (!isPlaying) {
                    saveState(sync = false)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val mediaItem = player.currentMediaItem
                    val mediaId = mediaItem?.mediaMetadata?.extras?.getString("navidromeID")
                    val isPlayingNow = player.isPlaying
                    val pos = newPosition.positionMs

                    // Jukebox remote output: forward seek (if device supports it)
                    if (JukeboxManager.isRemoteActive.value) {
                        val posSec = (pos / 1000L).coerceAtLeast(0L)
                        JukeboxManager.control("seek", posSec, this@ChoraMediaLibraryService)
                    }

                    if (!mediaId.isNullOrBlank() && !mediaId.startsWith("Local") &&
                        mediaItem.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION
                    ) {
                        reportPlaybackState(if (isPlayingNow) "playing" else "paused", pos)
                    }
                    saveState(sync = false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                MusicWidgetManager.updateWidgets(this@ChoraMediaLibraryService)

                if (playbackState == Player.STATE_ENDED) {
                    val mediaItem = player.currentMediaItem
                    val mediaId = mediaItem?.mediaMetadata?.extras?.getString("navidromeID")
                    val trackDuration = player.duration.coerceAtLeast(0L)
                    stopPlaybackHeartbeat()
                    if (!mediaId.isNullOrBlank() && !mediaId.startsWith("Local") &&
                        mediaItem.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION
                    ) {
                        serviceIOScope.launch {
                            // Inline variant: at STATE_ENDED the player may already
                            // have auto-advanced, so report the id we captured above.
                            reportPlaybackInline(mediaId, "stopped", trackDuration)
                        }
                    }
                    saveState(sync = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                error.printStackTrace()
                Log.e("PLAYER", error.stackTraceToString())

                if (error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                    Log.w("PLAYER", "Read position out of range. Recovering by seeking to 0ms...")
                    serviceMainScope.launch {
                        player.seekTo(player.currentMediaItemIndex, 0L)
                        player.prepare()
                    }
                    return
                }

                val currentServer = NavidromeManager.getCurrentServer()
                if (currentServer != null && error.errorCode in listOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )) {
                    Log.w("PLAYER", "Playback failed due to network error. Re-resolving STUN server URL...")
                    // Remember where we were: the recovery below has to resume
                    // there, otherwise a transient network blip silently restarts
                    // the song from 0:00.
                    val resumeIndex = player.currentMediaItemIndex
                    val resumePosition = player.currentPosition
                    val wasPlaying = player.playWhenReady
                    networkRecoveryJob?.cancel()
                    networkRecoveryJob = serviceIOScope.launch {
                        NavidromeManager.resolveActiveServerUrl(currentServer, forceRefresh = true)
                        withContext(Dispatchers.Main) {
                            // The previous code re-resolved the URL and stopped
                            // there, leaving the player in STATE_IDLE forever
                            // ("network error then silence"). Re-prepare instead.
                            if (player.mediaItemCount > 0) {
                                player.seekTo(resumeIndex, resumePosition)
                                player.prepare()
                                player.playWhenReady = wasPlaying
                            }
                        }
                    }
                }

                val currentItem = player.currentMediaItem
                val userMessage = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                        Log.e("PLAYER", "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED for item: ${currentItem?.mediaId}, uri=${currentItem?.localConfiguration?.uri}")
                        // 自动跳过不支持格式的曲目
                        serviceMainScope.launch {
                            if (player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                                player.prepare()
                                player.play()
                            }
                        }
                        "音频解析失败：格式不支持，已自动跳过"
                    }
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "网络连接失败：服务器返回异常状态码"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时或不可达"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "本地音频文件未找到"
                    else -> PlaybackException.getErrorCodeName(error.errorCode)
                }

                serviceMainScope.launch {
                    Toast.makeText(
                        this@ChoraMediaLibraryService,
                        userMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setId("AutoSession")
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        // Auto-restore playback state on startup so app isn't blank
        serviceIOScope.launch {
            try {
                val data = getRefreshedResumptionData()
                if (data != null && data.mediaItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (player.mediaItemCount == 0) {
                            player.setMediaItems(data.mediaItems)
                            player.seekTo(data.startIndex, data.startPositionMs)
                            player.playWhenReady = false
                            Log.d("RESUMPTION", "Auto-restored ${data.mediaItems.size} items at index ${data.startIndex}, pos ${data.startPositionMs} (lazy prepare)")
                            MusicWidgetManager.updateWidgets(this@ChoraMediaLibraryService)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RESUMPTION", "Error auto-restoring playback state", e)
            }
        }

        scrobbleJob = serviceMainScope.launch {
            var tickCount = 0
            while (isActive) {
                val duration = player.duration
                val mediaItem = player.currentMediaItem

                if (duration > 0 && !playerScrobbled) {
                    val currentPosition = player.currentPosition
                    val progress = (currentPosition * 100 / duration).toInt()
                    val scrobblePercentage = playbackSettingsManager.scrobblePercentFlow.first() * 10

                    if (progress >= scrobblePercentage) {
                        playerScrobbled = true
                        if (NavidromeManager.checkActiveServers() &&
                            mediaItem?.mediaMetadata?.extras?.getString("navidromeID")
                                ?.startsWith("Local") == false &&
                            mediaItem.mediaMetadata.mediaType != MediaMetadata.MEDIA_TYPE_RADIO_STATION
                        ) {
                            val scrobbleId = mediaItem.mediaMetadata.extras?.getString("navidromeID") ?: ""
                            serviceIOScope.launch {
                                songRepository.scrobbleSong(scrobbleId, true)
                            }
                        }
                    }
                }

                if (player.isPlaying) {
                    tickCount++
                    if (tickCount % 10 == 0) {
                        val currentPosition = player.currentPosition
                        // Fallback heartbeat only: the dedicated 12s job normally
                        // owns this. It used to call `reportPlayback` directly, so
                        // every 10s it overwrote the server session with a payload
                        // missing outputDevice/volume/playMode — the exact fields a
                        // takeover inherits.
                        if (playbackReportJob?.isActive != true) {
                            reportPlaybackState("playing", currentPosition)
                        }
                        // saveState() is intentionally NOT called here: it rewrote
                        // up to 100 tracks to DataStore and POSTed the whole queue
                        // to the server every 10s. It already runs on item
                        // transition, pause, discontinuity, STATE_ENDED and
                        // teardown, which is the only data that actually changes.
                        // The resume *position* is still refreshed here, but via a
                        // timestamp-only write.
                        val resumePos = normalizedResumePosition()
                        serviceIOScope.launch {
                            LocalDataSettingsManager(applicationContext)
                                .setPlaybackResumptionPosition(resumePos)
                        }
                    }
                } else {
                    tickCount = 0
                }

                delay(1000)
            }
        }

        serviceMainScope.launch {
            transcodeManager.transcodingConfigChangesFlow
                .distinctUntilChanged()
                .collect {
                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                        if (player.currentTimeline.isEmpty.not()) {
                            updateTranscodingDuringPlayback()
                        }
                    }
                }
        }

        Log.d("AA", "Initialized MediaLibraryService.")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return session
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            serviceIOScope.launch {
                Log.d("SERVICE", "ONPOSTCONNTECT MUSIC SERVICE!")
                if (session.isAutoCompanionController(controller))
                    getHomeScreenItems()

                this@ChoraMediaLibraryService.session?.notifyChildrenChanged(
                    "nodeHOME",
                    aHomeScreenItems.size,
                    null
                )
            }
            super.onPostConnect(session, controller)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // Android Auto uses the legacy MediaController, so we need to do some very weird hacks to make it work nicely.

            // If only one item is requested, check if it belongs to a folder we've already loaded
            if (mediaItems.size == 1) {
                val requestedId = mediaItems[0].mediaId
                // Try to find the full item in the last browsed folder
                val fullItem = aFolderSongs.find { it.mediaId == requestedId }
                if (fullItem != null) {
                    val startIndex = aFolderSongs.indexOf(fullItem)
                    val folderQueue = aFolderSongs.subList(startIndex, aFolderSongs.size).map { item ->
                        item.buildUpon()
                            .setUri(item.mediaId)
                            .build()
                    }
                    return Futures.immediateFuture(folderQueue)
                }

                // Not found in the current folder
                val cachedItem = aPlaylistScreenItems.find { it.mediaId == requestedId }
                    ?: aRadioScreenItems.find { it.mediaId == requestedId }
                    ?: aAlbumScreenItems.find { it.mediaId == requestedId }
                    ?: aArtistsScreenItems.find { it.mediaId == requestedId }

                if (cachedItem != null) {
                    val enrichedItem = cachedItem.buildUpon()
                        .setUri(cachedItem.mediaId)
                        .build()
                    return Futures.immediateFuture(listOf(enrichedItem))
                }
            }

            val updatedMediaItems = mediaItems.map { item ->
                item.buildUpon()
                    .setUri(item.mediaId)
                    .build()
            }
            return Futures.immediateFuture(updatedMediaItems)
        }

        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating
        ): ListenableFuture<SessionResult> {
            val currentItem = player.currentMediaItem
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))

            val navidromeID = currentItem.mediaMetadata.extras?.getString("navidromeID") ?: ""
            val newRating = (rating as StarRating).starRating.toInt()

            // Optimistic local update so the UI (lock screen / Android Auto / car
            // projection) reflects the tap immediately; the server round trip is
            // fired in the background. Doing it the old way — runBlocking around a
            // network call inside this callback — stalled the MediaSession thread
            // and delayed replaceMediaItem() until the server answered.
            val updatedExtras = Bundle(currentItem.mediaMetadata.extras ?: Bundle()).apply {
                putInt("rating", newRating)
            }
            val updatedItem = currentItem.buildUpon()
                .setMediaMetadata(
                    currentItem.mediaMetadata.buildUpon()
                        .setExtras(updatedExtras)
                        .setUserRating(rating)
                        .build()
                )
                .build()

            val index = player.currentMediaItemIndex
            if (player.currentMediaItem?.mediaMetadata?.extras?.getString("navidromeID") == navidromeID) {
                player.replaceMediaItem(index, updatedItem)
            }

            // `super.onSetRating` must be invoked on this frame: Kotlin forbids
            // super-calls from inside the coroutine below.
            val baseFuture = super.onSetRating(session, controller, rating)
            val resultFuture = SettableFuture.create<SessionResult>()
            serviceIOScope.launch {
                try {
                    songRepository.setSongRating(navidromeID, newRating)
                } catch (e: Exception) {
                    Log.w("MusicService", "setSongRating failed: ${e.message}")
                }
                try {
                    resultFuture.set(baseFuture.get())
                } catch (e: Exception) {
                    // Never leave the session hanging on a failed future.
                    resultFuture.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                }
            }
            return resultFuture
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(
                try {
                    LibraryResult.ofItemList(
                        when (parentId) {
                            "nodeROOT" -> rootHierarchy
                            "nodeHOME" -> getHomeScreenItems()
                            "nodeALBUMS" -> getAlbumScreenItems()
                            "nodeARTISTS" -> getArtistScreenItems()
                            "nodeRADIOS" -> getRadioItems()
                            "nodePLAYLISTS" -> getPlaylistItems()
                            else -> {
                                val mediaItem =
                                    aHomeScreenItems.find { it.mediaId == parentId }
                                        ?: aPlaylistScreenItems.find { it.mediaId == parentId }
                                        ?: aAlbumScreenItems.find { it.mediaId == parentId }
                                        ?: aArtistsScreenItems.find { it.mediaId == parentId }
                                getFolderItems(
                                    parentId,
                                    mediaItem?.mediaMetadata?.mediaType
                                        ?: MediaMetadata.MEDIA_TYPE_ALBUM
                                )
                            }
                        }, params)
                } catch (_: Exception) {
                    LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                }
            )
        }


        @OptIn(UnstableApi::class)
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val mediaItem = aFolderSongs.find { it.mediaId == mediaId }
                ?: aPlaylistScreenItems.find { it.mediaId == mediaId }
                ?: aRadioScreenItems.find { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))

            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    mediaItem,
                    LibraryParams.Builder().build()
                )
            )
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifyChildrenChanged(
                parentId,
                when (parentId) {
                    "nodeROOT" -> 2
                    "nodeHOME" -> aHomeScreenItems.size
                    "nodeALBUMS" -> aAlbumScreenItems.size
                    "nodeARTISTS" -> aArtistsScreenItems.size
                    "nodeRADIOS" -> aRadioScreenItems.size
                    "nodePLAYLISTS" -> aPlaylistScreenItems.size
                    else -> 0
                },
                params
            )

            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isToStream: Boolean
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val settable = SettableFuture.create<MediaItemsWithStartPosition>()
            if (player.mediaItemCount > 0) {
                settable.set(
                    MediaItemsWithStartPosition(
                        List(player.mediaItemCount) { player.getMediaItemAt(it) },
                        player.currentMediaItemIndex,
                        player.currentPosition
                    )
                )
                return settable
            }
            serviceMainScope.launch {
                Log.d("RESUMPTION", "Getting onPlaybackResumption")
                try {
                    val data = withContext(Dispatchers.IO) {
                        getRefreshedResumptionData()
                    }
                    if (data != null && data.mediaItems.isNotEmpty()) {
                        settable.set(data)
                        if (player.mediaItemCount == 0) {
                            player.setMediaItems(data.mediaItems)
                            player.seekTo(data.startIndex, data.startPositionMs)
                            player.playWhenReady = false
                        }
                    } else {
                        settable.set(MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    }
                } catch (e: Exception) {
                    Log.e("RESUMPTION", "Error in onPlaybackResumption", e)
                    settable.set(MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }
            return settable
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    runBlocking {
                        songRepository.getSongs(query).toMutableList()
                    },
                    LibraryParams.Builder().build()
                )
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d("SERVICE", "onSearch!!!")
            session.notifySearchResultChanged(
                browser,
                query,
                runBlocking {
                    songRepository.getSongs(query).size +
                            albumRepository.searchAlbum(query).size +
                            radioRepository.getRadios().map { it.toMediaItem() }.fastFilter {
                                it.mediaMetadata.station?.contains(
                                    query
                                ) ?: false
                            }.size +
                            playlistRepository.getPlaylists().fastFilter {
                                it.mediaMetadata.title?.contains(
                                    query
                                ) == true
                            }.size
                },
                LibraryParams.Builder().build()
            )

            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel() // Cancel any previously running timer

        if (minutes <= 0) {
            Log.d("SLEEPTIMER", "Sleep timer cancelled.")
            _sleepTimerRemainingTime.value = 0
            return
        }

        Log.d("SLEEPTIMER", "Sleep timer set for $minutes minutes.")

        sleepTimerJob = serviceMainScope.launch {
            var timeRemaining = minutes
            _sleepTimerRemainingTime.value = timeRemaining

            while (timeRemaining > 0) {
                delay(60 * 1000L)
                timeRemaining--
                _sleepTimerRemainingTime.value = timeRemaining
            }

            if (::player.isInitialized && player.isPlaying) {
                player.stop()
                Log.d("SLEEPTIMER", "Timer finished. Playback stopped.")
            }
        }
    }

    private fun updateTranscodingDuringPlayback() {
        val currentWindowIndex = player.currentMediaItemIndex
        val currentPlaybackPosition = player.currentPosition
        val wasPlaying = player.isPlaying

        val currentQueue = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            currentQueue.add(player.getMediaItemAt(i))
        }

        player.setMediaItems(currentQueue, currentWindowIndex, currentPlaybackPosition)

        player.prepare()

        if (wasPlaying) {
            player.play()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("SERVICE", "onTaskRemoved called - closing app")
        val currentSongId = player.currentMediaItem?.mediaMetadata?.extras?.getString("navidromeID")
        val currentPos = player.currentPosition

        // 1. Immediately pause local player if playing
        if (player.isPlaying) {
            player.pause()
        }

        // 2. Report "paused" + 3. save state off the main thread. These used to
        // be runBlocking{ withTimeoutOrNull(2500) } inline here, freezing the UI
        // for up to 2.5s on every app swipe-away.
        if (!currentSongId.isNullOrBlank() && !currentSongId.startsWith("Local")) {
            shutdownScope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(2500) {
                        runCatching { reportPlaybackInline(currentSongId, "paused", currentPos) }
                            .onFailure { Log.e("SERVICE", "Error reporting pause in onTaskRemoved", it) }
                    }
                }
            }
        }
        saveState(sync = false)

        // 4. Clean shutdown
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d("SERVICE", "onDestroy called")
        val currentSongId = player.currentMediaItem?.mediaMetadata?.extras?.getString("navidromeID")
        val currentPos = player.currentPosition

        if (player.isPlaying) {
            player.pause()
        }

        // Fire-and-forget: onDestroy must not block the main thread on network.
        // shutdownScope (not serviceIOScope) because that one is cancelled at the
        // bottom of this method — the report would die with it, and other devices
        // would keep showing this session as "playing" until the TTL lapses.
        if (!currentSongId.isNullOrBlank() && !currentSongId.startsWith("Local")) {
            shutdownScope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(2500) {
                        runCatching { reportPlaybackInline(currentSongId, "paused", currentPos) }
                            .onFailure { Log.e("SERVICE", "Error reporting pause in onDestroy", it) }
                    }
                }
            }
        }
        saveState(sync = false)
        session?.release()
        scrobbleJob?.cancel()
        sleepTimerJob?.cancel()
        networkRecoveryJob?.cancel()
        // The ExoPlayer instance and the two long-lived scopes were never
        // released, so every service recreation leaked a decoder thread pool and
        // two scopes still collecting DataStore flows against a dead Service.
        releasePlayerAndScopes()
        instance = null
        MusicWidgetManager.updateWidgets(this)
        super.onDestroy()
    }

    /**
     * Tears down the player and cancels the service scopes. Runs last in
     * [onDestroy] so the state/report coroutines above have already been handed
     * their captured values.
     */
    private fun releasePlayerAndScopes() {
        runCatching { player.release() }
            .onFailure { Log.w("SERVICE", "player.release() failed", it) }
        serviceMainScope.cancel()
        serviceIOScope.cancel()
    }

    /**
     * Position to persist for resumption: 0 once the track has (effectively)
     * ended, so a finished song resumes as the next one instead of 3:59/4:00.
     */
    private fun normalizedResumePosition(): Long {
        val duration = player.duration
        val raw = player.currentPosition
        return if (player.playbackState == Player.STATE_ENDED ||
            (duration > 0 && raw >= duration - 1500)
        ) {
            0L
        } else {
            raw.coerceAtLeast(0L)
        }
    }

    fun saveState(sync: Boolean = false) {
        val count = player.mediaItemCount
        if (count == 0) return
        val currentIndex = player.currentMediaItemIndex
        val currentPosition = normalizedResumePosition()
        val items = List(count) { i -> player.getMediaItemAt(i) }

        val action = suspend {
            try {
                Log.d(
                    "AA",
                    "Saving state! Playlist size: ${items.size}, current index: $currentIndex, current position: $currentPosition"
                )
                LocalDataSettingsManager(applicationContext).setPlaybackResumption(
                    items,
                    currentIndex,
                    currentPosition
                )

                val currentItem = if (currentIndex in 0 until count) items[currentIndex] else null
                val currentNavidromeId = currentItem?.mediaMetadata?.extras?.getString("navidromeID")
                if (!currentNavidromeId.isNullOrBlank() && !currentNavidromeId.startsWith("Local")) {
                    val navidromeSongIds = items.mapNotNull { it.mediaMetadata.extras?.getString("navidromeID") }
                        .filter { !it.startsWith("Local") }
                    if (navidromeSongIds.isNotEmpty()) {
                        songRepository.savePlayQueue(navidromeSongIds, currentNavidromeId, currentPosition)
                    }
                }
            } catch (e: Exception) {
                Log.e("STATE", "Error saving playback state", e)
            }
        }

        if (sync) {
            runBlocking {
                withTimeoutOrNull(2500) {
                    action()
                }
            }
        } else {
            // shutdownScope survives onDestroy, so the teardown save is not
            // cancelled together with the service scopes.
            shutdownScope.launch { action() }
        }
    }

    //region getChildren
    private fun getHomeScreenItems(): MutableList<MediaItem> {
        Log.d("SERVICE", "GETTING ANDROID AUTO SCREEN ITEMS")
        runBlocking {
            if (aHomeScreenItems.isEmpty()) {
                val recentlyPlayedAlbums = async { albumRepository.getAlbums("recent", 6) }.await()
                val mostPlayedAlbums = async { albumRepository.getAlbums("frequent", 6) }.await()

                recentlyPlayedAlbums.forEach { album ->
                    aHomeScreenItems.add(
                        album.apply {
                            this.mediaMetadata.extras?.putString(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                                this@ChoraMediaLibraryService.getString(R.string.recently_played)
                            )
                        }
                    )
                }

                mostPlayedAlbums.forEach { album ->
                    aHomeScreenItems.add(
                        album.apply {
                            this.mediaMetadata.extras?.putString(
                                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                                this@ChoraMediaLibraryService.getString(R.string.most_played)
                            )
                        }
                    )
                }
            }
        }
        return aHomeScreenItems
    }

    private fun getAlbumScreenItems() : MutableList<MediaItem> {
        Log.d("SERVICE", "GETTING ANDROID AUTO ALBUM SCREEN ITEMS")
        runBlocking {
            if (aAlbumScreenItems.isEmpty()) {
                while (true) {
                    val albums = async { albumRepository.getAlbums("alphabeticalByName", 250, aAlbumScreenItems.size) }.await()
                    aAlbumScreenItems.addAll(albums)
                    if (albums.isEmpty()) {
                        break
                    }
                }
            }
        }
        Log.d("SERVICE", "Got ALL albums. Should be 492, is ${aAlbumScreenItems.size}")
        return aAlbumScreenItems
    }

    private fun getArtistScreenItems() : MutableList<MediaItem> {
        Log.d("SERVICE", "GETTING ANDROID AUTO ARTIST SCREEN ITEMS")
        runBlocking {
            if (aArtistsScreenItems.isEmpty()) {
                val albums = async { artistRepository.getArtists() }.await()

                albums.forEach {
                    aArtistsScreenItems.add(
                        MediaItem.Builder()
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(it.name)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                                    .setArtworkUri(it.artistImageUrl?.toUri())
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .build()
                            )
                            .setMediaId(it.navidromeID)
                            .setUri(it.navidromeID)
                            .build()
                    )
                }
            }
        }
        return aArtistsScreenItems
    }

    private fun getRadioItems(): MutableList<MediaItem> {
        runBlocking {
            if (aRadioScreenItems.isEmpty()) {
                aRadioScreenItems.addAll(
                    radioRepository.getRadios().map { radio ->
                        Log.d("MediaItemTransition", radio.toString())
                        radio.toMediaItem()
                    }
                )
                Log.d("MediaItemTransition", "aRadioScreenItems: ${aRadioScreenItems.map { it.mediaMetadata }}")
            }
        }
        return aRadioScreenItems
    }

    private fun getPlaylistItems(): MutableList<MediaItem> {
        runBlocking {
            if (aPlaylistScreenItems.isEmpty()) {
                aPlaylistScreenItems.addAll(playlistRepository.getPlaylists())
            }
        }
        return aPlaylistScreenItems
    }

    private fun getFolderItems(parentId: String, type: Int): MutableList<MediaItem> {
        runBlocking {
            aFolderSongs.clear()
            when (type) {
                MediaMetadata.MEDIA_TYPE_ALBUM -> {
                    val albumSongs = async { albumRepository.getAlbum(parentId) }.await()
                    aFolderSongs.addAll(
                        albumSongs?.subList(1, albumSongs.size) ?: emptyList()
                    )
                }

                MediaMetadata.MEDIA_TYPE_PLAYLIST -> {
                    aFolderSongs.addAll(
                        playlistRepository.getPlaylistSongs(parentId)
                    )
                }

                MediaMetadata.MEDIA_TYPE_ARTIST -> {
                    aFolderSongs.addAll(
                        artistRepository.getArtistAlbums(parentId)
                    )
                }

                else -> aFolderSongs.clear()
            }
        }

        return aFolderSongs
    }
    //endregion

    private suspend fun getRefreshedResumptionData(): MediaSession.MediaItemsWithStartPosition? {
        val resumption = LocalDataSettingsManager(applicationContext)
            .playbackResumptionPlaylistWithStartPosition.firstOrNull() ?: return null
        if (resumption.mediaItems.isEmpty()) return null

        val currentServer = NavidromeManager.getCurrentServer()
        val baseUrl = if (currentServer != null) {
            if (!currentServer.activeBaseUrl.isNullOrBlank()) {
                currentServer.activeBaseUrl!!.trimEnd('/')
            } else {
                NavidromeManager.resolveActiveServerUrl(currentServer, forceRefresh = false).trimEnd('/')
            }
        } else null

        val restoredItems = resumption.mediaItems.map { item ->
            val songId = item.mediaMetadata.extras?.getString("navidromeID")
            if (currentServer != null && baseUrl != null && !songId.isNullOrBlank() && !songId.startsWith("Local")) {
                val salt = NavidromeDataSource.generateSalt(8)
                val token = NavidromeDataSource.md5Hash(currentServer.password + salt)
                val streamUrl = "$baseUrl/rest/stream.view?&id=$songId&u=${currentServer.username}&t=$token&s=$salt&v=1.12.0&c=Chora"
                val coverUrl = "$baseUrl/rest/getCoverArt.view?&id=$songId&u=${currentServer.username}&t=$token&s=$salt&v=1.16.1&c=Chora&size=300"

                val updatedMetadata = item.mediaMetadata.buildUpon()
                    .setArtworkUri(coverUrl.toUri())
                    .build()

                item.buildUpon()
                    .setUri(streamUrl.toUri())
                    .setMediaId(streamUrl)
                    .setMediaMetadata(updatedMetadata)
                    .build()
            } else {
                item
            }
        }

        val targetIndex = resumption.startIndex.coerceIn(0, restoredItems.size - 1)
        val targetItem = restoredItems.getOrNull(targetIndex)
        val itemDuration = targetItem?.mediaMetadata?.durationMs ?: 0L
        val rawPos = resumption.startPositionMs
        val targetPos = if (itemDuration > 0 && rawPos >= itemDuration - 1500) {
            0L
        } else {
            rawPos.coerceAtLeast(0L)
        }

        return MediaSession.MediaItemsWithStartPosition(restoredItems, targetIndex, targetPos)
    }

    private fun followHttpRedirects(initialUri: Uri): Uri {
        // Every stream load used to open a brand-new TCP + TLS handshake and fire a HEAD
        // probe before ExoPlayer opened its own connection — a pure extra round trip on
        // each track start, and up to 30s of blocking (5 hops x 3s connect + 3s read)
        // on the loader thread when a proxy was slow. Redirect targets are effectively
        // static, so remember the answer briefly and skip the probe entirely.
        val cacheKey = initialUri.toString()
        synchronized(redirectCacheLock) {
            redirectCache[cacheKey]?.let { (target, ts) ->
                if (System.currentTimeMillis() - ts < REDIRECT_CACHE_TTL_MS) {
                    Log.d("MusicService", "followHttpRedirects cache hit for $cacheKey")
                    return Uri.parse(target)
                }
            }
        }

        var currentUri = initialUri
        var redirectFollowed = false
        var sawError = false
        for (hop in 0 until 5) {
            val scheme = currentUri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") break
            try {
                val conn = (java.net.URI(currentUri.toString()).toURL().openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    connectTimeout = REDIRECT_CONNECT_TIMEOUT_MS
                    readTimeout = REDIRECT_READ_TIMEOUT_MS
                    if (this is javax.net.ssl.HttpsURLConnection) {
                        val server = NavidromeManager.getCurrentServer()
                        if (server?.allowSelfSignedCert == true) {
                            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                            })
                            val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                            sc.init(null, trustAll, java.security.SecureRandom())
                            sslSocketFactory = sc.socketFactory
                            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                        }
                    }
                }
                val code = conn.responseCode
                if (code in 301..308) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank()) {
                        val targetUri = java.net.URI(location)
                        val hopTarget = if (targetUri.isAbsolute) targetUri else java.net.URI(currentUri.toString()).resolve(targetUri)
                        Log.d("MusicService", "followHttpRedirects hop $hop: $currentUri -> $hopTarget")
                        currentUri = Uri.parse(hopTarget.toString())
                        redirectFollowed = true

                        val portPart = if (hopTarget.port != -1) ":${hopTarget.port}" else ""
                        val resolvedOrigin = "${hopTarget.scheme}://${hopTarget.host}$portPart".trimEnd('/')
                        val server = NavidromeManager.getCurrentServer()
                        if (server != null && server.activeBaseUrl != resolvedOrigin) {
                            server.activeBaseUrl = resolvedOrigin
                            serviceIOScope.launch {
                                NavidromeManager.saveServers()
                            }
                        }
                        continue
                    }
                }
                conn.disconnect()
                break
            } catch (e: Exception) {
                // Don't cache a failed probe — a transient timeout would otherwise be
                // pinned for the whole TTL and block a legitimately moved server.
                sawError = true
                Log.w("MusicService", "followHttpRedirects exception: ${e.message}")
                break
            }
        }

        if (!sawError) {
            synchronized(redirectCacheLock) {
                redirectCache[cacheKey] = currentUri.toString() to System.currentTimeMillis()
                while (redirectCache.size > REDIRECT_CACHE_MAX_ENTRIES) {
                    val eldest = redirectCache.keys.iterator()
                    if (!eldest.hasNext()) break
                    eldest.next()
                    eldest.remove()
                }
            }
            if (redirectFollowed) {
                Log.d("MusicService", "followHttpRedirects resolved $cacheKey -> $currentUri")
            }
        }
        return currentUri
    }
}