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
        NavidromeManager.init(this)
        LocalProviderManager.init(this)
        com.craftworks.music.managers.DownloadedSongsManager.init(this)
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
