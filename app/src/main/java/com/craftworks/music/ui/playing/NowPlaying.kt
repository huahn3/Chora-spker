package com.craftworks.music.ui.playing

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaMetadata
import androidx.media3.common.StarRating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.craftworks.music.R
import com.craftworks.music.managers.settings.OLEDProtectionMode
import com.craftworks.music.player.ChoraMediaLibraryService
import com.craftworks.music.ui.elements.dialogs.AddSongToPlaylist
import com.craftworks.music.ui.elements.dialogs.JukeboxDeviceBottomSheet
import com.craftworks.music.ui.elements.dialogs.RatingDialog
import com.craftworks.music.ui.elements.dialogs.showAddSongToPlaylistDialog
import com.craftworks.music.ui.elements.dialogs.songToAddToPlaylist
import com.craftworks.music.ui.playing.tv.TvNowPlaying

enum class NowPlayingAlignment {
    LEFT, CENTER, RIGHT
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun NowPlayingContent(
    mediaController: MediaController? = null,
    metadata: MediaMetadata? = null,
    viewModel: NowPlayingViewModel = viewModel(),
    showInternalQueue: Boolean = true,
    showJukeboxSheet: Boolean = true,
    /**
     * False while the player is collapsed into the dock. Stops the full-screen
     * background shader and the lyrics tickers from running behind the scenes —
     * this subtree is only ever translated off-screen, never unmounted, so
     * without the flag every screen paid for a 60 fps gradient + 2 Hz lyric
     * recomposition for the whole session.
     */
    active: Boolean = true
) {
    val backgroundStyle by viewModel.backgroundStyle.collectAsStateWithLifecycle(NowPlayingBackground.STATIC_BLUR)
    val backgroundDarkMode by viewModel.isBackgroundDark.collectAsStateWithLifecycle()
    val oledProtectionMode by viewModel.oledProtectionMode.collectAsStateWithLifecycle(OLEDProtectionMode.OFF)
    val playQueueOpen by viewModel.playQueueOpen.collectAsStateWithLifecycle()
    val detailsOpen by viewModel.detailsOpen.collectAsStateWithLifecycle()
    var showRatingDialog by remember { mutableStateOf(false) }
    val sleepTimerOpen by viewModel.sleepTimerDialogOpen.collectAsStateWithLifecycle()
    val sleepTimerMinutes by ChoraMediaLibraryService.getInstance()?.sleepTimerRemainingTime
        ?.collectAsStateWithLifecycle(initialValue = 0)
        ?: remember { mutableIntStateOf(0) }
    val colors by viewModel.paletteColors.collectAsStateWithLifecycle()
    val iconTextColor by viewModel.iconTextColor.collectAsStateWithLifecycle()
    val isStarred by viewModel.isStarred.collectAsStateWithLifecycle()
    val jukeboxDialogOpen by viewModel.jukeboxDialogOpen.collectAsStateWithLifecycle()

    val isSystemDark = if (oledProtectionMode != OLEDProtectionMode.OFF) true
        else isSystemInDarkTheme()

    LaunchedEffect(metadata?.artworkUri, backgroundStyle) {
        viewModel.updatePaletteFromUri(metadata?.artworkUri, backgroundStyle, isSystemDark)
    }

    LaunchedEffect(metadata?.extras?.getString("navidromeID")) {
        viewModel.updateStarredStatus(metadata)
    }

    val targetOverlayColor = when {
        oledProtectionMode != OLEDProtectionMode.OFF -> Color.Black.copy(0.7f)
        backgroundStyle == NowPlayingBackground.ANIMATED_BLUR -> {
            if (backgroundDarkMode) Color.Black.copy(0.2f) else Color.White.copy(0.2f)
        }
        else -> Color.Transparent
    }

    NowPlaying_Background(colors, backgroundStyle, targetOverlayColor, active = active)

    if (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION) {
        TvNowPlaying(
            mediaController,
            iconTextColor,
            metadata
        )
    } else if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        NowPlayingLandscape(
            mediaController = mediaController,
            metadata = metadata,
            iconColor = iconTextColor,
            sleepTimerMinutes = sleepTimerMinutes,
            onOpenSleepTimer = { viewModel.setSleepTimerDialogOpen(true) },
            onOpenJukebox = { viewModel.setJukeboxDialogOpen(true) },
            onToggleQueue = { viewModel.setPlayQueueOpen(!playQueueOpen) },
            onToggleTranslation = { viewModel.toggleLyricsTranslation() },
            onForceRetranslate = { viewModel.forceRetranslateLyrics() },
            onRefreshLyrics = { viewModel.refreshLyrics(metadata) },
            active = active
        )
    } else {
        NowPlayingPortrait(
            mediaController = mediaController,
            metadata = metadata,
            iconColor = iconTextColor,
            isStarred = isStarred,
            sleepTimerMinutes = sleepTimerMinutes,
            onToggleFavorite = { viewModel.toggleStar(metadata) },
            onAddToPlaylist = {
                val item = mediaController?.currentMediaItem
                if (item != null) {
                    songToAddToPlaylist.value = item
                    showAddSongToPlaylistDialog.value = true
                }
            },
            onToggleQueue = { viewModel.setPlayQueueOpen(!playQueueOpen) },
            onToggleDetails = { viewModel.setDetailsOpen(!detailsOpen) },
            onOpenSleepTimer = { viewModel.setSleepTimerDialogOpen(true) },
            onOpenJukebox = { viewModel.setJukeboxDialogOpen(true) },
            onToggleTranslation = { viewModel.toggleLyricsTranslation() },
            onForceRetranslate = { viewModel.forceRetranslateLyrics() },
            onRefreshLyrics = { viewModel.refreshLyrics(metadata) },
            active = active
        )
    }


    val detailsSheetState = rememberModalBottomSheetState()
    val timePickerState = rememberTimePickerState(initialHour = 0, initialMinute = 0, is24Hour = true)

    if (showInternalQueue && playQueueOpen) {
        PlayQueueBottomSheet(
            isOpen = playQueueOpen,
            onDismissRequest = { viewModel.setPlayQueueOpen(false) },
            mediaController = mediaController,
            colors = colors
        )
    }

    if (detailsOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setDetailsOpen(false) },
            sheetState = detailsSheetState,
        ) {
            NowPlayingDetails(
                isStarred = isStarred,
                currentRating = (metadata?.userRating as? StarRating)?.starRating?.toInt() ?: 0,
                onOpenRating = { showRatingDialog = true }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (sleepTimerOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.setSleepTimerDialogOpen(false) },
            title = { Text(stringResource(R.string.Dialog_SetSleepTimer)) },
            text = {
                TimePicker(
                    state = timePickerState,
                )
            },
            confirmButton = {
                Button (
                    onClick = {
                        ChoraMediaLibraryService.getInstance()?.setSleepTimer(timePickerState.hour * 60 + timePickerState.minute)
                        viewModel.setSleepTimerDialogOpen(false)
                    }
                ) {
                    Text(stringResource(R.string.Action_Done))
                }
            }
        )
    }

    // `metadata?.userRating as StarRating` was a hard cast on a nullable field:
    // it threw ClassCastException for local files / radio items and NPE'd when
    // metadata was null, so opening the rating dialog could crash the player.
    val currentUserRating = (metadata?.userRating as? StarRating)?.starRating?.toInt() ?: 0
    if (showRatingDialog && metadata != null && metadata.userRating is StarRating)
        RatingDialog(
            currentRating = currentUserRating,
            onDismiss = { showRatingDialog = false },
            onSetRating = { rating ->
                mediaController?.setRating(StarRating(5, rating.toFloat()))
            }
        )

    // Hosted by MainActivity when rendered inside the BottomSheetScaffold sheet:
    // a nested ModalBottomSheet inside sheetContent desyncs the parent sheet state.
    if (showJukeboxSheet && jukeboxDialogOpen) {
        JukeboxDeviceBottomSheet(
            mediaController = mediaController,
            onDismissRequest = { viewModel.setJukeboxDialogOpen(false) }
        )
    }
}

@Composable
fun dpToPx(dp: Int): Int {
    return with(LocalDensity.current) { dp.dp.toPx() }.toInt()
}