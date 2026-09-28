package com.craftworks.music.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.craftworks.music.BuildConfig

/**
 * Debug-only poor-man's sampling profiler.
 *
 * The device is not rooted, so `debuggerd`/`kill -3` and ART's own sampling
 * profiler (whose output needs `dmtracedump`, absent here) are unavailable. This
 * samples the main thread's own stack on a timer and reports a histogram, which
 * is enough to attribute continuous CPU to a specific call path.
 *
 * Strictly debug: [enabled] is a compile-time-false in release, so R8 drops the
 * whole thing.
 */
object MainThreadSampler {
    private const val TAG = "MTSAMPLE"

    val enabled: Boolean = BuildConfig.DEBUG

    fun start(seconds: Int = 12, intervalMs: Long = 40L) {
        if (!enabled) return
        Thread {
            val main = Handler(Looper.getMainLooper()).looper.thread
            val counts = HashMap<String, Int>()
            val self = HashMap<String, Int>()
            val samples = (seconds * 1000L / intervalMs).toInt()
            repeat(samples) {
                val st = main.stackTrace
                if (st.isNotEmpty()) {
                    val leaf = st[0]
                    val key = "${leaf.className.substringAfterLast('.')}.${leaf.methodName}"
                    counts[key] = (counts[key] ?: 0) + 1
                    // Deepest frame that belongs to this app, for attribution.
                    val appFrame = st.firstOrNull {
                        it.className.startsWith("com.craftworks.music")
                    }
                    val akey = appFrame?.let {
                        "${it.className.substringAfterLast('.')}.${it.methodName}"
                    } ?: "(no app frame)"
                    self[akey] = (self[akey] ?: 0) + 1
                }
                Thread.sleep(intervalMs)
            }
            Log.w(TAG, "=== $samples samples ===")
            dump("LEAF", counts, samples)
            dump("APP", self, samples)
        }.apply { isDaemon = true; name = "mtsampler" }.start()
    }

    private fun dump(label: String, m: Map<String, Int>, total: Int) {
        Log.w(TAG, "--- top $label frames ---")
        m.entries.sortedByDescending { it.value }.take(18).forEach { (k, v) ->
            val pct = if (total == 0) 0 else (v * 100 / total)
            Log.w(TAG, String.format("%3d%%  %5d  %s", pct, v, k))
        }
    }
}
