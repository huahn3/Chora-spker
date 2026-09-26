package com.craftworks.music

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component3
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component4
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component5
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component6
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component7
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.NotificationUtil.IMPORTANCE_LOW
import androidx.media3.common.util.NotificationUtil.createNotificationChannel
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.rememberDrawerState
import com.craftworks.music.data.BottomNavItem
import com.craftworks.music.data.NavItems
import com.craftworks.music.data.model.Screen
import com.craftworks.music.managers.CoverThemeManager
import com.craftworks.music.managers.LocalProviderManager
import com.craftworks.music.managers.NavidromeManager
import com.craftworks.music.managers.settings.AppTheme
import com.craftworks.music.managers.settings.AppearanceSettingsManager
import com.craftworks.music.player.ChoraMediaLibraryService
import com.craftworks.music.player.rememberManagedMediaController
import com.craftworks.music.ui.elements.dialogs.tv.OnboardingDialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.craftworks.music.managers.settings.LocalDataSettingsManager
import com.craftworks.music.ui.elements.dialogs.AddSongToPlaylist
import com.craftworks.music.ui.elements.dialogs.JukeboxDeviceBottomSheet
import com.craftworks.music.ui.elements.dialogs.showAddSongToPlaylistDialog
import com.craftworks.music.ui.isCompactDockLayout
import com.craftworks.music.ui.isWideLayout
import com.craftworks.music.ui.playing.ChoraDock
import com.craftworks.music.ui.playing.NowPlayingContent
import com.craftworks.music.ui.playing.NowPlayingViewModel
import com.craftworks.music.ui.playing.PlayQueueBottomSheet
import com.craftworks.music.ui.theme.CoverAmbientBackground
import com.craftworks.music.ui.theme.CoverWashLayout
import com.craftworks.music.ui.theme.MusicPlayerTheme
import com.craftworks.music.ui.theme.coverWash
import com.craftworks.music.ui.theme.coverWashScrim
import com.craftworks.music.ui.theme.rememberCoverWash
import com.gigamole.composefadingedges.FadingEdgesGravity
import com.gigamole.composefadingedges.content.FadingEdgesContentType
import com.gigamole.composefadingedges.content.scrollconfig.FadingEdgesScrollConfig
import com.gigamole.composefadingedges.verticalFadingEdges
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    lateinit var navController: NavHostController

    @androidx.annotation.OptIn(UnstableApi::class)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(applicationContext, ChoraMediaLibraryService::class.java)
        this@MainActivity.startService(serviceIntent)

        enableEdgeToEdge()

        setContent {
            val selectedThemeName by remember { AppearanceSettingsManager(this) }.appTheme.collectAsState(
                AppTheme.SYSTEM.name
            )
            val darkTheme = when (selectedThemeName) {
                AppTheme.DARK.name -> true
                AppTheme.LIGHT.name -> false
                else -> isSystemInDarkTheme()
            }

            val coverColorMode by remember { AppearanceSettingsManager(this) }.coverThemeFlow.collectAsStateWithLifecycle(true)

            MusicPlayerTheme (darkTheme, coverColorMode = coverColorMode) {
                navController = rememberNavController()

                val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()

                val mediaController by rememberManagedMediaController()
                var metadata by remember { mutableStateOf<MediaMetadata?>(null) }

                val coroutineScope = rememberCoroutineScope()

                // Fast-load playback resumption metadata immediately on startup so mini player appears instantly
                LaunchedEffect(Unit) {
                    if (metadata == null) {
                        try {
                            val resumption = LocalDataSettingsManager(applicationContext)
                                .playbackResumptionPlaylistWithStartPosition.firstOrNull()
                            if (metadata == null && resumption != null && resumption.mediaItems.isNotEmpty()) {
                                val idx = resumption.startIndex.coerceIn(0, resumption.mediaItems.size - 1)
                                metadata = resumption.mediaItems[idx].mediaMetadata
                            }
                        } catch (e: Exception) {
                            Log.w("RESUMPTION", "Could not fast-load playback resumption: ${e.message}")
                        }
                    }
                }

                // Update palette colors for queue and player background.
                // Style-agnostic: NowPlaying owns the background style, so this
                // must not force STATIC_BLUR and race its LaunchedEffect.
                LaunchedEffect(metadata?.artworkUri) {
                    nowPlayingViewModel.updatePaletteFromUri(
                        metadata?.artworkUri,
                        darkTheme
                    )
                }

                // Update metadata from mediaController.
                DisposableEffect(mediaController) {
                    val listener = object : Player.Listener {
                        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
                            metadata = mediaMetadata
                            super.onPlaylistMetadataChanged(mediaMetadata)
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            metadata = mediaItem?.mediaMetadata
                            super.onMediaItemTransition(mediaItem, reason)
                        }

                        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                            metadata = mediaMetadata
                            super.onMediaMetadataChanged(mediaMetadata)
                        }
                    }

                    if (mediaController?.mediaMetadata != null) {
                        metadata = mediaController?.mediaMetadata
                    }
                    mediaController?.addListener(listener)

                    onDispose {
                        mediaController?.removeListener(listener)
                    }
                }


                val isTv = LocalConfiguration.current.uiMode and
                        Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

                if (isTv) {
                    // Set background color to colorScheme.background
                    window.decorView.setBackgroundColor(androidx.tv.material3.MaterialTheme.colorScheme.background.toArgb())

                    SetupNavGraph(
                        navController = navController,
                        bottomPadding = 0.dp,
                        mediaController = mediaController
                    )
                } else {
                    // Set background color to colorScheme.background
                    window.decorView.setBackgroundColor(MaterialTheme.colorScheme.background.toArgb())

                    // Follow-the-finger fullscreen player. A single Animatable
                    // (0f = parked below the screen, 1f = expanded) replaces
                    // BottomSheetScaffold: its SheetState can't be dragged
                    // programmatically and desynced cur/tgt on flings, this can't.
                    // Animatable itself isn't saveable, so the expanded flag is
                    // mirrored into a rememberSaveable and seeds it — otherwise a
                    // config-change recreation (fold, font scale, locale) dropped
                    // the user back to the mini player mid-song.
                    var playerExpandedState by rememberSaveable { mutableStateOf(false) }
                    val playerOffset = remember { Animatable(if (playerExpandedState) 1f else 0f) }
                    val isPlayerExpanded by remember {
                        derivedStateOf { playerOffset.value >= 0.5f }
                    }
                    LaunchedEffect(isPlayerExpanded) {
                        playerExpandedState = isPlayerExpanded
                    }
                    val overlayDensity = LocalDensity.current
                    val overlayScreenConfig = LocalConfiguration.current
                    val overlayScreenHeightPx = remember(overlayDensity, overlayScreenConfig) {
                        with(overlayDensity) { overlayScreenConfig.screenHeightDp.dp.toPx() }
                    }

                    // 1. 播放界面展开时（封面、歌词、播放队列、歌曲详情），返回键拦截并缩小为 Mini Player
                    BackHandler(enabled = isPlayerExpanded) {
                        if (nowPlayingViewModel.playQueueOpen.value) {
                            nowPlayingViewModel.setPlayQueueOpen(false)
                            return@BackHandler
                        }
                        if (nowPlayingViewModel.detailsOpen.value) {
                            nowPlayingViewModel.setDetailsOpen(false)
                            return@BackHandler
                        }
                        coroutineScope.launch {
                            playerOffset.animateTo(0f, PlayerSettleSpec)
                        }
                    }

                    // 2. Mini Player 收起状态下的返回逻辑（检测首页双击退出，其他界面正常回退）
                    var lastBackPressTime by rememberSaveable { mutableLongStateOf(0L) }
                    BackHandler(enabled = !isPlayerExpanded) {
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        val isAtHome = currentRoute == Screen.Home.route

                        if (isAtHome) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastBackPressTime < 2000) {
                                val service = ChoraMediaLibraryService.getInstance()
                                service?.let { svc ->
                                    val p = svc.player
                                    if (p.isPlaying) {
                                        p.pause()
                                    }
                                    svc.saveState(sync = false)
                                }
                                finishAffinity()
                            } else {
                                lastBackPressTime = currentTime
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.press_again_to_exit),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@BackHandler
                        }

                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Home.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    var dockHeight by remember { mutableStateOf(152.dp) }
                    val animatedDockHeight by animateDpAsState(
                        targetValue = dockHeight,
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                        label = "dockContentPadding"
                    )
                    val isCompactDock = isCompactDockLayout()

                    val coverPalette by CoverThemeManager.state.collectAsStateWithLifecycle()
                    if (coverColorMode) {
                        // Drawn as a root sibling behind the transparent Scaffold below.
                        CoverAmbientBackground(colors = coverPalette.colors)
                    }

                    Scaffold(
                        modifier = Modifier.graphicsLayer {
                            // Home content recedes (shrinks + fades) as the player
                            // rises, so the transition reads as depth, not a slide.
                            val o = playerOffset.value
                            scaleX = 1f - 0.05f * o
                            scaleY = 1f - 0.05f * o
                            alpha = 1f - 0.35f * o
                        },
                        bottomBar = {
                            if (!isCompactDock) {
                                AnimatedBottomNavBar(
                                    navController = navController,
                                    paletteColors = coverPalette.colors,
                                    coverColorMode = coverColorMode
                                )
                            }
                        },
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        containerColor = Color.Transparent
                    ) { paddingValues ->
                        if (isCompactDock) {
                            SetupNavGraph(
                                navController,
                                animatedDockHeight,
                                mediaController
                            )
                        } else {
                            // paddingValues used to be discarded here (0.dp), so on
                            // >=640dp — where the bottom NavigationBar/NavigationRail
                            // is 80dp+ tall — the last item of every list sat behind
                            // it. No screen applied navigationBarsPadding itself.
                            SetupNavGraph(
                                navController,
                                paddingValues.calculateBottomPadding(),
                                mediaController
                            )
                        }
                    }

                    if (isCompactDock) {
                        // Lyrics-list collapse chaining: scrollable children consume
                        // the drag first; their leftover (list already at top,
                        // pulling down) flows here and collapses the player. So the
                        // higher up in the lyrics you are, the easier it collapses —
                        // once scrolled down, pulling down just scrolls the list.
                        val collapseConnection = remember(playerOffset, overlayScreenHeightPx) {
                            object : NestedScrollConnection {
                                override fun onPostScroll(
                                    consumed: androidx.compose.ui.geometry.Offset,
                                    available: androidx.compose.ui.geometry.Offset,
                                    source: NestedScrollSource
                                ): androidx.compose.ui.geometry.Offset {
                                    if (available.y > 0f && playerOffset.value > 0f) {
                                        val next = (playerOffset.value - available.y / overlayScreenHeightPx)
                                            .coerceIn(0f, 1f)
                                        coroutineScope.launch { playerOffset.snapTo(next) }
                                        return androidx.compose.ui.geometry.Offset(0f, available.y)
                                    }
                                    return androidx.compose.ui.geometry.Offset.Zero
                                }

                                override suspend fun onPostFling(
                                    consumed: Velocity,
                                    available: Velocity
                                ): Velocity {
                                    if (playerOffset.value < 1f) {
                                        val target = when {
                                            available.y < -700f -> 1f
                                            available.y > 700f -> 0f
                                            playerOffset.value < 0.55f -> 0f
                                            else -> 1f
                                        }
                                        playerOffset.animateTo(target, PlayerSettleSpec)
                                        return available
                                    }
                                    return Velocity.Zero
                                }
                            }
                        }
                        // Fullscreen player overlay: rides the same Animatable the dock
                        // drag drives, so it rises/falls exactly with the finger.
                        // graphicsLayer translates hit-testing too, so the parked
                        // (off-screen) player can never swallow dock taps.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val o = playerOffset.value
                                    translationY = (1f - o) * size.height
                                    // Unfurl: rises as a rounded card, flattens to
                                    // fullscreen at the end of the drag.
                                    scaleX = 0.92f + 0.08f * o
                                    scaleY = 0.92f + 0.08f * o
                                    alpha = 0.75f + 0.25f * o
                                    clip = true
                                    shape = RoundedCornerShape(((1f - o) * 48f).dp)
                                }
                                .nestedScroll(collapseConnection)
                                // Swipe-down-to-collapse: same follow-the-finger
                                // mapping as the dock's swipe-up. Children that own
                                // vertical scrolling (lyrics list) win the gesture
                                // collision, so drags there still scroll the list.
                                // Screen height is captured inside the closure and
                                // used as the drag denominator, so it MUST be part of
                                // the key: after a fold/rotation the old height kept
                                // driving the mapping, making the swipe sensitivity
                                // off by up to 2x.
                                .pointerInput(playerOffset, overlayScreenHeightPx) {
                                    var dragVelY = 0f
                                    var lastDragTime = 0L
                                    fun settle() {
                                        coroutineScope.launch {
                                            val target = when {
                                                dragVelY > 700f -> 0f
                                                dragVelY < -700f -> 1f
                                                playerOffset.value < 0.55f -> 0f
                                                else -> 1f
                                            }
                                            playerOffset.animateTo(target, PlayerSettleSpec)
                                        }
                                    }
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            dragVelY = 0f
                                            lastDragTime = 0L
                                        },
                                        onDragEnd = { settle() },
                                        onDragCancel = { settle() },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            val now = change.uptimeMillis
                                            val dt = (now - lastDragTime).coerceAtLeast(1L)
                                            lastDragTime = now
                                            dragVelY = dragVelY * 0.6f +
                                                    (dragAmount / dt * 1000f) * 0.4f
                                            val next = (playerOffset.value - dragAmount / overlayScreenHeightPx)
                                                .coerceIn(0f, 1f)
                                            coroutineScope.launch { playerOffset.snapTo(next) }
                                        }
                                    )
                                }
                        ) {
                            NowPlayingContent(
                                mediaController = mediaController,
                                metadata = metadata,
                                viewModel = nowPlayingViewModel,
                                showInternalQueue = false,
                                showJukeboxSheet = false
                            )
                        }

                        val currentView = LocalView.current
                        val dockCtx = LocalContext.current
                        val disableScreenStandy by remember(dockCtx) { AppearanceSettingsManager(dockCtx) }.disableScreenStandby.collectAsStateWithLifecycle(true)
                        DisposableEffect(isPlayerExpanded) {
                            val fullscreenNow = isPlayerExpanded
                            if (fullscreenNow) {
                                if (disableScreenStandy)
                                    currentView.keepScreenOn = true
                                Log.d("NOW-PLAYING", "KeepScreenOn: True")
                            } else {
                                currentView.keepScreenOn = false
                                Log.d("NOW-PLAYING", "KeepScreenOn: False")
                            }

                            onDispose {
                                currentView.keepScreenOn = false
                                Log.d("NOW-PLAYING", "KeepScreenOn: False")
                            }
                        }

                        Box(Modifier.fillMaxSize()) {
                            ChoraDock(
                                navController = navController,
                                playerOffset = playerOffset,
                                metadata = metadata,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                viewModel = nowPlayingViewModel,
                                onHeightChanged = { dockHeight = it },
                                onPlayerClick = {
                                    coroutineScope.launch {
                                        playerOffset.animateTo(1f, PlayerSettleSpec)
                                    }
                                },
                                onQueueLongClick = {
                                    nowPlayingViewModel.setPlayQueueOpen(true)
                                },
                                onOutputDeviceClick = {
                                    nowPlayingViewModel.setJukeboxDialogOpen(true)
                                },
                                onSwipeNext = {
                                    mediaController?.seekToNext()
                                },
                                onSwipePrev = {
                                    mediaController?.seekToPrevious()
                                }
                            )
                        }
                    }
                }

                // Scope-isolated hosts: the popup open/close state is read INSIDE
                // these functions so flipping it only recomposes a tiny subtree.
                // Reading it at this level recomposed the whole Scaffold + nav graph,
                // stalling the popup's first frame by up to ~2s.
                PlayQueueSheetHost(nowPlayingViewModel, mediaController)
                AddSongToPlaylistDialogHost()

                JukeboxSheetHost(nowPlayingViewModel, mediaController)

                var showNoProvidersDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val folders = LocalProviderManager.getAllFolders()
                    val servers = NavidromeManager.getAllServers()

                    showNoProvidersDialog = !(folders.isEmpty() && servers.isEmpty())
                }

                if (!showNoProvidersDialog) {
                    if (isTv) {
                        OnboardingDialog { showNoProvidersDialog = true }
                    } else {
                        com.craftworks.music.ui.elements.dialogs.OnboardingDialog() { showNoProvidersDialog = true }
//                        NoMediaProvidersDialog(
//                            setShowDialog = { showNoProvidersDialog = true },
//                            navController
//                        )
                    }
                }
            }
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            permissions.entries.forEach { permission ->
                Log.d(
                    "PERMISSIONS", "Is '${permission.key}' permission granted? ${permission.value}"
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            requestPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.ACCESS_LOCAL_NETWORK
                )
            )
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }

        createNotificationChannel(
            this,
            "download_channel",
            R.string.Notification_Download_Name,
            R.string.Notification_Download_Desc,
            IMPORTANCE_LOW
        )

    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onDestroy() {
        Log.d("MAIN_ACTIVITY", "onDestroy called - closing app")
        val service = ChoraMediaLibraryService.getInstance()
        service?.let { svc ->
            val p = svc.player
            if (p.isPlaying) {
                p.pause()
            }
            svc.saveState(sync = false)
        }
        stopService(Intent(this, ChoraMediaLibraryService::class.java))
        println("Destroyed, Goodbye :(")
        super.onDestroy()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSideNavigation(
    navController: NavHostController,
    mediaController: MediaController?,
    content: @Composable () -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val (home, albums, songs, artists, radios, playlists, settings) = remember { FocusRequester.createRefs() }
    val currentRoute by navController.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)

    val orderedNavItems = remember(context) { AppearanceSettingsManager(context) }.bottomNavItemsFlow.collectAsState(
        initial = NavItems.default
    ).value

    NavigationDrawer(
        modifier = Modifier.fillMaxSize(),
        drawerState = drawerState,
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 24.dp)
                    .focusProperties {
                        enter = {
                            when (currentRoute) {
                                Screen.Home -> home
                                Screen.Albums -> albums
                                Screen.Song -> songs
                                Screen.Artists -> artists
                                Screen.Radio -> radios
                                Screen.Playlists -> playlists
                                Screen.Setting -> settings
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NavigationDrawerItem(
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                    selected = Screen.Search.route == backStackEntry?.destination?.route,
                    onClick = {
                        if (Screen.Search.route != backStackEntry?.destination?.route) {
                            navController.navigate(Screen.Search.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                            }
                        }
                    },
                    leadingContent = {
                        androidx.tv.material3.Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                ) {
                    androidx.tv.material3.Text(text = "Search")
                }

                orderedNavItems.forEach { item ->
                    if (!item.enabled) return@forEach

                    val isSelected = item.screenRoute == backStackEntry?.destination?.route
                    val icon = NavItems.iconFor(item.screenRoute)
                    NavigationDrawerItem(
                        modifier = Modifier
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .focusRequester(
                                when (item.screenRoute) {
                                    Screen.Home.route -> home
                                    Screen.Albums.route -> albums
                                    Screen.Song.route -> songs
                                    Screen.Artists.route -> artists
                                    Screen.Radio.route -> radios
                                    Screen.Playlists.route -> playlists
                                    Screen.Setting.route -> settings
                                    else -> FocusRequester.Default
                                }
                            ),
                        selected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                navController.navigate(item.screenRoute) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            androidx.tv.material3.Icon(
                                imageVector = ImageVector.vectorResource(icon),
                                contentDescription = item.title,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    ) {
                        androidx.tv.material3.Text(text = item.title)
                    }
                }

                val isPlayingSelected =
                    Screen.NowPlayingLandscape.route == backStackEntry?.destination?.route

                var isPlayingVisible by remember { mutableStateOf(mediaController?.currentMediaItem != null) }
                LaunchedEffect(mediaController?.mediaMetadata) {
                    isPlayingVisible = mediaController?.currentMediaItem != null
                }

                if (isPlayingVisible) {
                    NavigationDrawerItem(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                        selected = isPlayingSelected,
                        onClick = {
                            if (!isPlayingSelected) {
                                navController.navigate(Screen.NowPlayingLandscape.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                }
                            }
                        },
                        leadingContent = {
                            androidx.tv.material3.Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.s_m_playback),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    ) {
                        androidx.tv.material3.Text(text = "Playing")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    NavigationDrawerItem(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                        selected = Screen.Setting.route == backStackEntry?.destination?.route,
                        onClick = {
                            if (Screen.Setting.route != backStackEntry?.destination?.route)
                                navController.navigate(Screen.Setting.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                }
                        },
                        leadingContent = {
                            androidx.tv.material3.Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.rounded_settings_24),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    ) {
                        androidx.tv.material3.Text(text = stringResource(R.string.settings))
                    }
                    /*
                    NavigationDrawerItem(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                        selected = false,
                        onClick = {
                            (context as Activity).finish()
                            exitProcess(0)
                        },
                        leadingContent = {
                            androidx.tv.material3.Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.round_power_settings_new_24),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            focusedContainerColor = androidx.tv.material3.MaterialTheme.colorScheme.errorContainer,
                            focusedContentColor = androidx.tv.material3.MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        androidx.tv.material3.Text(text = stringResource(R.string.Action_Exit))
                    }
                    */
                }
            }
        },
        content = {
            Box(Modifier
                .focusRestorer()
                .focusGroup()
            ) {
                content()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Stable
fun AnimatedBottomNavBar(
    navController: NavHostController,
    paletteColors: List<Color> = emptyList(),
    coverColorMode: Boolean = false,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current

    val orderedNavItems = AppearanceSettingsManager(context).bottomNavItemsFlow.collectAsState(
        initial = listOf(
            BottomNavItem(
                "Home", R.drawable.rounded_home_24, "home_screen"
            ), BottomNavItem(
                stringResource(R.string.Albums), R.drawable.rounded_library_music_24, "album_screen"
            ), BottomNavItem(
                stringResource(R.string.songs), R.drawable.round_music_note_24, "songs_screen"
            ), BottomNavItem(
                stringResource(R.string.Artists), R.drawable.rounded_artist_24, "artists_screen"
            ), BottomNavItem(
                stringResource(R.string.radios), R.drawable.rounded_radio, "radio_screen"
            ), BottomNavItem(
                stringResource(R.string.playlists), R.drawable.placeholder, "playlist_screen"
            )
        )
    ).value

    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
        // Same cover wash as the dock card, so both widths read identically.
        val useCoverWash = coverColorMode && paletteColors.isNotEmpty()
        Box(modifier = Modifier.fillMaxWidth()) {
            if (useCoverWash) {
                val washDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Box(
                    Modifier.matchParentSize().coverWash(
                        colors = rememberCoverWash(paletteColors),
                        layout = CoverWashLayout.COMPACT,
                        base = MaterialTheme.colorScheme.background,
                        overlay = coverWashScrim(paletteColors, themeDark = washDark)
                    )
                )
            }
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = if (useCoverWash) Color.Transparent
                                 else NavigationBarDefaults.containerColor,
                contentColor = MaterialTheme.colorScheme.onBackground,
                tonalElevation = 0.dp
            ) {
            orderedNavItems.forEachIndexed { _, item ->
                if (!item.enabled) return@forEachIndexed

                val icon = NavItems.iconFor(item.screenRoute)
                NavigationBarItem(
                    selected = item.screenRoute == backStackEntry?.destination?.route,
                    onClick = {
                        if (item.screenRoute == backStackEntry?.destination?.route) return@NavigationBarItem
                        navController.navigate(item.screenRoute) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    label = { Text(text = item.title) },
                    alwaysShowLabel = false,
                    icon = {
                        Icon(ImageVector.vectorResource(icon), contentDescription = null)
                    })
            }
            if (isWideLayout())
                NavigationBarItem(
                    selected = Screen.NowPlayingLandscape.route == backStackEntry?.destination?.route,
                    onClick = {
                        if (Screen.NowPlayingLandscape.route == backStackEntry?.destination?.route) return@NavigationBarItem
                        navController.navigate(Screen.NowPlayingLandscape.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    label = { Text(text = "Playing") },
                    alwaysShowLabel = false,
                    icon = {
                        Icon(
                            ImageVector.vectorResource(R.drawable.s_m_playback),
                            contentDescription = "Playing"
                        )
                    },
                )
            }
        }
    } else {
        val lazyColumnState = rememberLazyListState()
        NavigationRail {
            LazyColumn(
                state = lazyColumnState,
                modifier = Modifier
                    .weight(1f)
                    .verticalFadingEdges(
                        FadingEdgesContentType.Dynamic.Lazy.List(
                            FadingEdgesScrollConfig.Dynamic(), lazyColumnState
                        ), FadingEdgesGravity.All, 64.dp
                    )
            ) {
                items(orderedNavItems) { item ->
                    if (!item.enabled) return@items

                    val icon = NavItems.iconFor(item.screenRoute)
                    NavigationRailItem(
                        selected = item.screenRoute == backStackEntry?.destination?.route,
                        onClick = {
                            if (item.screenRoute == backStackEntry?.destination?.route) return@NavigationRailItem
                            navController.navigate(item.screenRoute) {
                                popUpTo(Screen.Home.route) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        label = { Text(text = item.title) },
                        alwaysShowLabel = false,
                        icon = {
                            Icon(ImageVector.vectorResource(icon), contentDescription = null)
                        },
                    )
                }
                item {
                    NavigationRailItem(
                        selected = Screen.NowPlayingLandscape.route == backStackEntry?.destination?.route,
                        onClick = {
                            if (Screen.NowPlayingLandscape.route == backStackEntry?.destination?.route) return@NavigationRailItem
                            navController.navigate(Screen.NowPlayingLandscape.route) {
                                popUpTo(Screen.Home.route) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        label = { Text(text = "Playing") },
                        alwaysShowLabel = false,
                        icon = {
                            Icon(
                                ImageVector.vectorResource(R.drawable.s_m_playback),
                                contentDescription = "Playing"
                            )
                        },
                    )
                }
            }
        }
    }
}

// Shared settle spec for the follow-the-finger player overlay (MainActivity + ChoraDock).
internal val PlayerSettleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 900f
)

@Composable
private fun PlayQueueSheetHost(
    viewModel: NowPlayingViewModel,
    mediaController: MediaController?
) {
    val isOpen by viewModel.playQueueOpen.collectAsStateWithLifecycle()
    val colors by viewModel.paletteColors.collectAsStateWithLifecycle()
    PlayQueueBottomSheet(
        isOpen = isOpen,
        onDismissRequest = { viewModel.setPlayQueueOpen(false) },
        mediaController = mediaController,
        colors = colors
    )
}

@Composable
private fun AddSongToPlaylistDialogHost() {
    // SINGLE owner of this dialog. It used to be hosted by four different
    // screens *and* by the always-composed NowPlayingContent, so tapping
    // "add to playlist" on an album/song row raised two Dialogs bound to the
    // same global flag.
    if (showAddSongToPlaylistDialog.value) {
        AddSongToPlaylist(setShowDialog = { showAddSongToPlaylistDialog.value = it })
    }
}

@Composable
private fun JukeboxSheetHost(
    viewModel: NowPlayingViewModel,
    mediaController: MediaController?
) {
    val isOpen by viewModel.jukeboxDialogOpen.collectAsStateWithLifecycle()
    if (isOpen) {
        JukeboxDeviceBottomSheet(
            mediaController = mediaController,
            onDismissRequest = { viewModel.setJukeboxDialogOpen(false) }
        )
    }
}

fun formatMilliseconds(seconds: Int): String {
    return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
}

fun Modifier.fadingEdge(brush: Brush) = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    }
