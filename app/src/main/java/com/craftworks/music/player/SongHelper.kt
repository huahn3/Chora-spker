@file:OptIn(UnstableApi::class) package com.craftworks.music.player

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SongHelper {
    companion object{
        suspend fun play(mediaItems: List<MediaItem>, index: Int, mediaController: MediaController?) {
            if (mediaItems.isEmpty() || mediaController == null)
                return

            withContext(Dispatchers.Main) {
                val targetItem = mediaItems.getOrNull(index)
                val currentItem = mediaController.currentMediaItem

                // 1. If tapping the currently playing song, avoid re-preparing and resetting stream
                if (targetItem != null && currentItem?.mediaId == targetItem.mediaId) {
                    if (!mediaController.isPlaying) {
                        mediaController.play()
                    }
                    return@withContext
                }

                // 2. If current queue matches this list, seekToDefaultPosition instead of recreating player pipeline
                val isSameQueue = mediaController.mediaItemCount == mediaItems.size &&
                        mediaController.mediaItemCount > 0 &&
                        mediaController.getMediaItemAt(0).mediaId == mediaItems[0].mediaId

                if (isSameQueue) {
                    mediaController.seekToDefaultPosition(index)
                    mediaController.play()
                    return@withContext
                }

                // 3. New queue: set items and prepare
                mediaController.setMediaItems(mediaItems, index, 0)
                mediaController.prepare()
                mediaController.play()
            }
        }
    }
}