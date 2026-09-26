package com.craftworks.music.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Singleton class that manages a MediaController instance.
 *
 * This class observes the Remember lifecycle to release the MediaController when it's no longer needed.
 */
@Stable
class MediaControllerManager private constructor(context: Context) : RememberObserver {
    private val appContext = context.applicationContext
    private var factory: ListenableFuture<MediaController>? = null

    private val _currentMetadata = MutableStateFlow<MediaMetadata?>(null)
    val currentMetadata: StateFlow<MediaMetadata?> = _currentMetadata.asStateFlow()

    var controller = mutableStateOf<MediaController?>(null)

    init { initialize() }

    private fun publishCurrentMetadata(controller: MediaController?) {
        _currentMetadata.value = controller?.currentMediaItem?.mediaMetadata
    }

    /**
     * Initializes the MediaController.
     *
     * Idempotent: repeated ON_START events must not stack up futures or
     * listeners. Previously every foreground transition built a fresh future
     * (the old one was never released) and added another listener, leaking one
     * binder connection + one listener per app switch.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun initialize() {
        val existing = factory
        if (existing != null && !existing.isDone) {
            // Still connecting — nothing to do, its listener will publish.
            return
        }
        if (existing != null) {
            // Already connected: keep the same controller, just re-publish.
            publishCurrentMetadata(existing.get())
            return
        }

        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, ChoraMediaLibraryService::class.java))
        ).buildAsync()
        factory = future

        future.addListener(
            {
                // MediaController is available here with future.get()
                val value = if (future.isDone) runCatching { future.get() }.getOrNull() else null
                if (factory !== future) return@addListener
                controller.value = value
                publishCurrentMetadata(value)
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * Releases the MediaController.
     *
     * This method will release the MediaController and set the controller state to null.
     */
    internal fun release() {
        factory?.let {
            MediaController.releaseFuture(it)
            controller.value = null
            _currentMetadata.value = null
        }
        factory = null
    }

    // Lifecycle methods for the RememberObserver interface.
    override fun onAbandoned() { }
    override fun onForgotten() { release() }
    override fun onRemembered() {}

    companion object {
        @Volatile
        private var instance: MediaControllerManager? = null

        /**
         * Returns the Singleton instance of the MediaControllerManager.
         *
         * @param context The context to use when creating the MediaControllerManager.
         * @return The Singleton instance of the MediaControllerManager.
         */
        fun getInstance(context: Context): MediaControllerManager {
            return instance ?: synchronized(this) {
                instance ?: MediaControllerManager(context).also { instance = it }
            }
        }
    }
}



/**
 * A Composable function that provides a managed MediaController instance.
 *
 * @param lifecycle The lifecycle of the owner of this MediaController. Defaults to the lifecycle of the LocalLifecycleOwner.
 * @return A State object containing the MediaController instance. The Composable will automatically re-compose whenever the state changes.
 */
@Composable
fun rememberManagedMediaController(
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle
): State<MediaController?> {
    // Application context is used to prevent memory leaks
    val appContext = LocalContext.current.applicationContext
    val controllerManager = remember { MediaControllerManager.getInstance(appContext) }

    // Observe the lifecycle to initialize and release the MediaController at the appropriate times.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controllerManager.initialize()
                Lifecycle.Event.ON_DESTROY -> controllerManager.release()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    return controllerManager.controller
}

@Composable
fun rememberCurrentMetadata(
    lifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle
): State<MediaMetadata?> {
    val appContext = LocalContext.current.applicationContext
    val manager = remember { MediaControllerManager.getInstance(appContext) }

    // Lifecycle‑aware init/release – unchanged
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> manager.initialize()
                Lifecycle.Event.ON_DESTROY -> manager.release()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Collect the metadata flow once and expose it as a State
    return manager.currentMetadata.collectAsStateWithLifecycle()
}