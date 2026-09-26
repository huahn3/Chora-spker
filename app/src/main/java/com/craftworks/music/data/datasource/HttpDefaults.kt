package com.craftworks.music.data.datasource

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

/**
 * Header names whose values must never reach logcat.
 */
private val SensitiveHeaders = setOf(
    HttpHeaders.Authorization,
    HttpHeaders.Cookie,
    HttpHeaders.SetCookie,
    "X-Api-Key"
)

/**
 * Ktor's `Logger.SIMPLE` prints the full request line, and every Subsonic call
 * carries `t=<auth token>&s=<salt>&u=<user>` in the query string — so the
 * previous `LogLevel.ALL` wrote a working session token for the account into
 * logcat on every single request. `LogLevel.HEADERS` plus `sanitizeHeader` keeps
 * the method/status/headers and drops the credentials.
 */
object SafeKtorLogger : Logger {
    private const val TAG = "HTTP"
    override fun log(message: String) {
        android.util.Log.d(TAG, message.take(MAX_MESSAGE))
    }

    private const val MAX_MESSAGE = 1_000
}

/**
 * Shared hardening for every data source HTTP client:
 *
 * - timeouts — none of the five clients had any, so a black-holed proxy or a
 *   hanging server left `getRequest` suspended forever with no recovery
 * - bounded retry with exponential backoff, only for idempotent methods, and
 *   never for cancellation
 * - credential redaction in the HTTP log
 *
 * Content negotiation and HTTP caching stay per-client (their configuration
 * differs per data source). Call inside `HttpClient(OkHttp) { ... }`.
 */
fun <T : io.ktor.client.engine.HttpClientEngineConfig> HttpClientConfig<T>.installHttpDefaults() {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 8_000
        socketTimeoutMillis = 20_000
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        retryOnExceptionIf(maxRetries = 2) { _, cause ->
            cause !is CancellationException
        }
        exponentialDelay(base = 2.0, maxDelayMs = 4_000)
    }
    install(Logging) {
        level = LogLevel.HEADERS
        logger = SafeKtorLogger
        sanitizeHeader { header -> header in SensitiveHeaders }
    }
}

/**
 * Strips the credential query parameters (`t` = auth token, `s` = salt,
 * `u` = username) from a Subsonic URL before logging it. Those three together
 * are enough to authenticate as the user.
 */
fun redactSubsonicUrl(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val kept = url.substring(queryStart + 1)
        .split('&')
        .filter { part ->
            val key = part.substringBefore('=')
            key !in setOf("t", "s", "u", "p", "a")
        }
        .joinToString("&")
    return url.substring(0, queryStart) + if (kept.isEmpty()) "" else "?$kept"
}
