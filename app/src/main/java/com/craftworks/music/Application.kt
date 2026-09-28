package com.craftworks.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.craftworks.music.managers.LocalProviderManager
import com.craftworks.music.managers.NavidromeManager
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class ChoraApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.initPersistentSalt(this)
        // Stable handoff identity must exist before any Subsonic call fires.
        com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.appContextRef = applicationContext
        com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.getOrCreateClientUniqueId(this)
        NavidromeManager.init(this)
        LocalProviderManager.init(this)
        com.craftworks.music.managers.DownloadedSongsManager.init(this)
        com.craftworks.music.managers.CoverThemeManager.init(this)
        // Cross-device handoff: stay subscribed to `/api/events` for
        // `playbackHandoff` so another device taking over pauses us instantly.
        com.craftworks.music.managers.PlaybackHandoffManager.startSseListener(this)
        // Feed the dock's output-device chip with the newest other-device
        // progress so its ring is meaningful without opening the device sheet.
        com.craftworks.music.managers.PlaybackHandoffManager.startAmbientPolling()
        // TEMP(debug only): attribute the playing-state CPU to a call path.
        // R8 strips this in release because `enabled` is a compile-time false.
        if (com.craftworks.music.util.MainThreadSampler.enabled) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.craftworks.music.util.MainThreadSampler.start(seconds = 12)
            }, 20_000L)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val dispatcher = Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 6
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 429) {
                    android.util.Log.w("COIL", "429 Too Many Requests received for ${chain.request().url}")
                }
                response
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
