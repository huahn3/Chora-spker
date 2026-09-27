package com.craftworks.music.data.datasource.navidrome

import com.craftworks.music.data.datasource.installHttpDefaults
import android.annotation.SuppressLint
import android.util.Log
import com.craftworks.music.data.NavidromeProvider
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.Companion.CLIENT_UNIQUE_ID_HEADER
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.Companion.appContextRef
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource.Companion.getOrCreateClientUniqueId
import com.craftworks.music.data.model.JukeboxControlRequest
import com.craftworks.music.data.model.JukeboxDevicesResponse
import com.craftworks.music.data.model.JukeboxPlayRequest
import com.craftworks.music.data.model.JukeboxSelectRequest
import com.craftworks.music.data.model.JukeboxStatusResponse
import com.craftworks.music.data.model.LyricsTranslationRequest
import com.craftworks.music.data.model.LyricsTranslationResponse
import com.craftworks.music.data.model.PlaybackHandoffEvent
import com.craftworks.music.data.model.PlaybackSessionDto
import com.craftworks.music.data.model.PlaybackSessionsResponse
import com.craftworks.music.data.model.TakeoverRequest
import com.craftworks.music.data.model.TakeoverResponse
import com.craftworks.music.managers.NavidromeManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
private data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
private data class LoginResponse(
    val token: String? = null,
    val id: String? = null,
    val name: String? = null,
    val isAdmin: Boolean = false
)

/** Result of a takeover POST, including the fork's 403 refusal. */
data class TakeoverOutcome(
    val ok: Boolean,
    val forbidden: Boolean = false,
    val error: String? = null,
    val response: TakeoverResponse? = null
)

/** Who the stored credentials belong to, as reported by `/auth/login`. */
data class LoginIdentity(
    val userId: String = "",
    val userName: String = "",
    val isAdmin: Boolean = false
)

/**
 * Mirrors the fork's `canTakeOverSession`: an admin may take over anyone's
 * session, an ordinary user only their own. Returns true when we cannot tell
 * (not logged in yet) so the UI stays permissive until the real answer is known.
 */
fun canTakeOverSessionOnServer(identity: LoginIdentity?, targetUserId: String?): Boolean {
    if (identity == null || identity.userId.isBlank()) return true
    if (identity.isAdmin) return true
    return !targetUserId.isNullOrBlank() && targetUserId == identity.userId
}

@Singleton
class NavidromeNativeApi @Inject constructor() {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val tokenCache = ConcurrentHashMap<String, String>()

    /**
     * Identity of the account we last logged in as, per server. `id` and
     * `isAdmin` come straight from `/auth/login` and were being parsed and
     * thrown away; the fork needs both to decide whether a playback takeover is
     * even permitted (see [canTakeOverSessionOnServer]).
     */
    private val identityCache = ConcurrentHashMap<String, LoginIdentity>()

    private val loginMutex = Mutex()

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
        installHttpDefaults()
        }
    }

    private val insecureClient: HttpClient by lazy { buildInsecureClient() }

    private fun buildInsecureClient(): HttpClient {
        val trustAllCerts = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        return HttpClient(OkHttp.create {
            config {
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }) {
            install(ContentNegotiation) {
                json(json)
            }
        installHttpDefaults()
        }
    }

    private fun getHttpClient(server: NavidromeProvider): HttpClient {
        return if (server.allowSelfSignedCert == true) insecureClient else client
    }

    /** Identity from the last successful login for [server], if any. */
    fun loginIdentity(server: NavidromeProvider?): LoginIdentity? =
        server?.let { identityCache[it.id] }

    suspend fun getBearerToken(server: NavidromeProvider, forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val serverId = server.id
            if (!forceRefresh) {
                tokenCache[serverId]?.let { return@withContext it }
            }

            loginMutex.withLock {
                if (!forceRefresh) {
                    tokenCache[serverId]?.let { return@withLock it }
                }

                try {
                    val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
                    val loginUrl = "${baseUrl}/auth/login"
                    val httpClient = getHttpClient(server)

                    val reqBody = json.encodeToString(
                        LoginRequest.serializer(),
                        LoginRequest(server.username, server.password)
                    )

                    val response = httpClient.post(loginUrl) {
                        contentType(ContentType.Application.Json)
                        setBody(reqBody)
                    }

                    if (response.status == HttpStatusCode.OK) {
                        val bodyText = response.bodyAsText()
                        val loginResp = json.decodeFromString(LoginResponse.serializer(), bodyText)
                        val token = loginResp.token
                        if (!token.isNullOrBlank()) {
                            tokenCache[serverId] = token
                            identityCache[serverId] = LoginIdentity(
                                userId = loginResp.id.orEmpty(),
                                userName = loginResp.name.orEmpty(),
                                isAdmin = loginResp.isAdmin
                            )
                            Log.d("NAVIDROME_NATIVE", "Authenticated successfully for server $serverId")
                            return@withLock token
                        }
                    } else {
                        Log.w("NAVIDROME_NATIVE", "Login failed: HTTP ${response.status}")
                    }
                } catch (e: Exception) {
                    Log.e("NAVIDROME_NATIVE", "Failed to login to Navidrome Native API", e)
                }
                null
            }
        }

    // ==========================================
    // 歌词翻译 (Lyrics Translation API)
    // ==========================================

    suspend fun getCachedLyricsTranslation(
        songId: String,
        lang: String = "zh-CN"
    ): LyricsTranslationResponse? = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext null
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext null
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/lyrics/translate/${songId}?lang=${lang}"

        try {
            var response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext null
                response = httpClient.get(url) {
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return@withContext json.decodeFromString(LyricsTranslationResponse.serializer(), body)
            } else if (response.status == HttpStatusCode.NotFound) {
                Log.d("LYRICS_TRANSLATE", "No cached translation for song $songId (404)")
            } else {
                Log.w("LYRICS_TRANSLATE", "Get translation cache failed: HTTP ${response.status}")
            }
        } catch (e: Exception) {
            Log.e("LYRICS_TRANSLATE", "Error fetching cached translation", e)
        }
        null
    }

    suspend fun translateLyrics(
        songId: String,
        lang: String = "zh-CN",
        force: Boolean = false
    ): LyricsTranslationResponse? = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext null
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext null
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/lyrics/translate"
        val reqPayload = json.encodeToString(
            LyricsTranslationRequest.serializer(),
            LyricsTranslationRequest(songId = songId, targetLang = lang, force = force)
        )

        try {
            var response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
                setBody(reqPayload)
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext null
                response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                    setBody(reqPayload)
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return@withContext json.decodeFromString(LyricsTranslationResponse.serializer(), body)
            } else {
                Log.w("LYRICS_TRANSLATE", "Translate failed: HTTP ${response.status} - ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            Log.e("LYRICS_TRANSLATE", "Error triggering translation", e)
        }
        null
    }

    // ==========================================
    // 多输出设备 (Jukebox API)
    // ==========================================

    suspend fun getJukeboxDevices(): JukeboxDevicesResponse? = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext null
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext null
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/jukebox/devices"

        try {
            var response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext null
                response = httpClient.get(url) {
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return@withContext json.decodeFromString(JukeboxDevicesResponse.serializer(), body)
            }
        } catch (e: Exception) {
            Log.e("JUKEBOX", "Error getting jukebox devices", e)
        }
        null
    }

    suspend fun selectJukeboxDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext false
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext false
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/jukebox/select"
        val payload = json.encodeToString(
            JukeboxSelectRequest.serializer(),
            JukeboxSelectRequest(deviceId = deviceId)
        )
        Log.d("JUKEBOX", "selectJukeboxDevice: sending POST $url, payload=$payload")

        try {
            var response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
                setBody(payload)
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext false
                response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                    setBody(payload)
                }
            }

            val body = response.bodyAsText()
            Log.d("JUKEBOX", "selectJukeboxDevice($deviceId) -> status=${response.status}, body=$body")
            return@withContext response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            Log.e("JUKEBOX", "Error selecting jukebox device $deviceId", e)
            false
        }
    }

    suspend fun playJukebox(songId: String, positionSec: Long = 0L): Boolean = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext false
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext false
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/jukebox/play"
        val payload = json.encodeToString(
            JukeboxPlayRequest.serializer(),
            JukeboxPlayRequest(songId = songId, position = positionSec)
        )
        Log.d("JUKEBOX", "playJukebox: sending POST $url, payload=$payload")

        try {
            var response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
                setBody(payload)
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext false
                response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                    setBody(payload)
                }
            }

            val body = response.bodyAsText()
            Log.d("JUKEBOX", "playJukebox($songId, pos=$positionSec) -> status=${response.status}, body=$body")
            return@withContext response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            Log.e("JUKEBOX", "Error playing jukebox song $songId", e)
            false
        }
    }

    suspend fun controlJukebox(action: String, value: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext false
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext false
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/jukebox/control"
        val payload = json.encodeToString(
            JukeboxControlRequest.serializer(),
            JukeboxControlRequest(action = action, value = value)
        )
        Log.d("JUKEBOX", "controlJukebox: sending POST $url, payload=$payload")

        try {
            var response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
                setBody(payload)
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext false
                response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                    setBody(payload)
                }
            }

            val body = response.bodyAsText()
            Log.d("JUKEBOX", "controlJukebox($action, val=$value) -> status=${response.status}, body=$body")
            return@withContext response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            Log.e("JUKEBOX", "Error controlling jukebox action $action", e)
            false
        }
    }

    suspend fun getJukeboxStatus(): JukeboxStatusResponse? = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext null
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext null
        val httpClient = getHttpClient(server)

        val url = "${baseUrl}/api/jukebox/status"

        try {
            var response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
                header("X-ND-Authorization", "Bearer $token")
            }

            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true) ?: return@withContext null
                response = httpClient.get(url) {
                    header("Authorization", "Bearer $newToken")
                    header("X-ND-Authorization", "Bearer $newToken")
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                return@withContext json.decodeFromString(JukeboxStatusResponse.serializer(), body)
            } else {
                Log.w("JUKEBOX", "getJukeboxStatus failed: HTTP ${response.status} - ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            Log.e("JUKEBOX", "Error getting jukebox status", e)
        }
        null
    }

    // ==========================================
    // 多端同步接管 (Playback Handoff & Takeover)
    // Native auth: ONLY `X-ND-Authorization` is honoured server-side;
    // the standard `Authorization` header 401s (verified 2026-09-27).
    // Every call also sends `X-ND-Client-Unique-Id` so `isCurrentSession`
    // and SSE `targetSessionId` filtering line up with our own session.
    // ==========================================

    fun myClientUniqueId(): String? =
        appContextRef?.let { getOrCreateClientUniqueId(it) }

    private fun nativeAuthHeaders(
        builder: io.ktor.client.request.HttpRequestBuilder,
        token: String
    ) {
        // Keep the dual header (reverse-proxy compat) — server only reads X-ND.
        builder.header("Authorization", "Bearer $token")
        builder.header("X-ND-Authorization", "Bearer $token")
        myClientUniqueId()?.let { builder.header(CLIENT_UNIQUE_ID_HEADER, it) }
    }

    suspend fun getPlaybackSessions(): PlaybackSessionsResponse? = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer() ?: return@withContext null
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server) ?: return@withContext null
        val httpClient = getHttpClient(server)

        suspend fun fetch(t: String): io.ktor.client.statement.HttpResponse =
            httpClient.get("${baseUrl}/api/playback/sessions") {
                nativeAuthHeaders(this, t)
            }

        try {
            var response = fetch(token)
            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true)
                    ?: return@withContext null
                response = fetch(newToken)
            }
            if (response.status == HttpStatusCode.OK) {
                return@withContext json.decodeFromString(
                    PlaybackSessionsResponse.serializer(),
                    response.bodyAsText()
                )
            }
            Log.w("HANDOFF", "getPlaybackSessions failed: HTTP ${response.status}")
        } catch (e: Exception) {
            Log.e("HANDOFF", "Error getting playback sessions", e)
        }
        null
    }

    suspend fun getPlaybackSession(sessionId: String): PlaybackSessionDto? =
        withContext(Dispatchers.IO) {
            val server = NavidromeManager.getCurrentServer() ?: return@withContext null
            val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
            val token = getBearerToken(server) ?: return@withContext null
            val httpClient = getHttpClient(server)

            suspend fun fetch(t: String): io.ktor.client.statement.HttpResponse =
                httpClient.get("${baseUrl}/api/playback/sessions/$sessionId") {
                    nativeAuthHeaders(this, t)
                }

            try {
                var response = fetch(token)
                if (response.status == HttpStatusCode.Unauthorized) {
                    val newToken = getBearerToken(server, forceRefresh = true)
                        ?: return@withContext null
                    response = fetch(newToken)
                }
                if (response.status == HttpStatusCode.OK) {
                    return@withContext json.decodeFromString(
                        PlaybackSessionDto.serializer(),
                        response.bodyAsText()
                    )
                }
                // 404 = session expired/gone; not an error for takeover flow.
                if (response.status != HttpStatusCode.NotFound) {
                    Log.w("HANDOFF", "getPlaybackSession($sessionId) -> HTTP ${response.status}")
                }
            } catch (e: Exception) {
                Log.e("HANDOFF", "Error getting playback session $sessionId", e)
            }
            null
        }

    /**
     * Notify the server that we took over [targetSessionId]'s playback.
     * Always 200 (missing target => `session` absent) — never throw for that.
     * Only `pause`/`stop` are legal actions; anything else is a 400.
     */
    suspend fun takeoverSession(
        targetSessionId: String,
        action: String = "pause",
        newPlayerName: String? = "Chora (手机端)",
        targetOutput: String? = null
    ): TakeoverOutcome = withContext(Dispatchers.IO) {
        val server = NavidromeManager.getCurrentServer()
            ?: return@withContext TakeoverOutcome(ok = false, error = "no server")
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        val token = getBearerToken(server)
            ?: return@withContext TakeoverOutcome(ok = false, error = "not authenticated")
        val httpClient = getHttpClient(server)
        val payload = json.encodeToString(
            TakeoverRequest.serializer(),
            TakeoverRequest(
                action = if (action == "stop") "stop" else "pause",
                sourceSessionId = myClientUniqueId(),
                newPlayerName = newPlayerName,
                targetOutput = targetOutput
            )
        )

        suspend fun post(t: String): io.ktor.client.statement.HttpResponse =
            httpClient.post("${baseUrl}/api/playback/sessions/$targetSessionId/takeover") {
                contentType(ContentType.Application.Json)
                nativeAuthHeaders(this, t)
                setBody(payload)
            }

        try {
            var response = post(token)
            if (response.status == HttpStatusCode.Unauthorized) {
                val newToken = getBearerToken(server, forceRefresh = true)
                    ?: return@withContext TakeoverOutcome(ok = false, error = "re-auth failed")
                response = post(newToken)
            }
            if (response.status == HttpStatusCode.OK) {
                return@withContext TakeoverOutcome(
                    ok = true,
                    response = json.decodeFromString(
                        TakeoverResponse.serializer(),
                        response.bodyAsText()
                    )
                )
            }
            // The fork refuses a cross-user takeover for non-admins
            // ("not allowed to take over another user's session"). Silently
            // logging this left the UI claiming success while the other device
            // kept playing.
            if (response.status == HttpStatusCode.Forbidden) {
                return@withContext TakeoverOutcome(
                    ok = false,
                    forbidden = true,
                    error = runCatching { response.bodyAsText() }.getOrNull()
                )
            }
            Log.w("HANDOFF", "takeover($targetSessionId) -> HTTP ${response.status}")
            return@withContext TakeoverOutcome(ok = false, error = "HTTP ${response.status}")
        } catch (e: Exception) {
            Log.e("HANDOFF", "Error taking over session $targetSessionId", e)
        }
        TakeoverOutcome(ok = false, error = "request failed")
    }

    /**
     * SSE listener for `/api/events?jwt=`. EventSource can't set headers so
     * the token MUST go in the query string. Uses a raw OkHttp stream
     * (Ktor 3 buffers `response.body()`, so it can never stream SSE).
     * Calls [onHandoff] for every `playbackHandoff` event; returns only when
     * [isActive] flips false or the coroutine is cancelled — the caller owns
     * the 5s reconnect loop.
     */
    suspend fun streamPlaybackHandoffEvents(
        isActive: () -> Boolean,
        onHandoff: suspend (PlaybackHandoffEvent) -> Unit,
        onNowPlayingChanged: (suspend () -> Unit)? = null
    ) {
        val server = NavidromeManager.getCurrentServer() ?: return
        val baseUrl = NavidromeManager.resolveActiveServerUrl(server)
        // Cached token first: forcing a login on every 5s reconnect hammered
        // the server when the SSE route wasn't mounted (DevActivityPanel=off).
        var token = getBearerToken(server) ?: return
        val url = "$baseUrl/api/events?jwt=$token"
        withContext(Dispatchers.IO) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Cache-Control", "no-cache")
                myClientUniqueId()?.let { conn.setRequestProperty(CLIENT_UNIQUE_ID_HEADER, it) }
                conn.connectTimeout = 15_000
                conn.readTimeout = 0 // infinite stream; socket breaks on server close
                conn.connect()
                var code = conn.responseCode
                if (code == 401) {
                    // Expired JWT: refresh once and reopen the stream.
                    token = getBearerToken(server, forceRefresh = true) ?: return@withContext
                    try { conn.disconnect() } catch (e: Exception) { }
                    conn = (java.net.URL("$baseUrl/api/events?jwt=$token")
                        .openConnection() as java.net.HttpURLConnection).apply {
                        setRequestProperty("Accept", "text/event-stream")
                        setRequestProperty("Cache-Control", "no-cache")
                        myClientUniqueId()?.let { setRequestProperty(CLIENT_UNIQUE_ID_HEADER, it) }
                        connectTimeout = 15_000
                        readTimeout = 0
                        connect()
                    }
                    code = conn.responseCode
                    if (code == 401) {
                        Log.w("HANDOFF", "SSE stream unauthorized even after refresh")
                        return@withContext
                    }
                }
                if (code != 200) {
                    Log.w("HANDOFF", "SSE stream failed: HTTP $code")
                    return@withContext
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var currentEvent = ""
                    while (isActive()) {
                        val line = try {
                            withContext(Dispatchers.IO) { reader.readLine() }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("HANDOFF", "SSE read error: ${e.message}")
                            break
                        } ?: break // EOF: server closed / DevActivityPanel off
                        val trimmed = line.trimEnd('\r').trim()
                        when {
                            trimmed.startsWith("event:") ->
                                currentEvent = trimmed.removePrefix("event:").trim()
                            trimmed.startsWith("data:") -> {
                                val data = trimmed.removePrefix("data:").trim()
                                when {
                                    currentEvent == "playbackHandoff" && data.isNotEmpty() -> {
                                        try {
                                            onHandoff(json.decodeFromString(PlaybackHandoffEvent.serializer(), data))
                                        } catch (e: Exception) {
                                            Log.w("HANDOFF", "Bad handoff payload: ${e.message}")
                                        }
                                    }
                                    // The fork broadcasts this on every playback
                                    // report from any client, so it is a free
                                    // "some device started/stopped" signal. Use it
                                    // to refresh now instead of waiting out the
                                    // ambient poll interval.
                                    currentEvent == "nowPlayingCount" -> {
                                        try { onNowPlayingChanged?.invoke() } catch (e: Exception) { }
                                    }
                                }
                            }
                            trimmed.isEmpty() -> currentEvent = ""
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("HANDOFF", "SSE stream error: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { }
            }
        }
    }
}
