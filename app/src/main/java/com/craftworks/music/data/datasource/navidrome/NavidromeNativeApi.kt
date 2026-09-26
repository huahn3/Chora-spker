package com.craftworks.music.data.datasource.navidrome

import com.craftworks.music.data.datasource.installHttpDefaults
import android.annotation.SuppressLint
import android.util.Log
import com.craftworks.music.data.NavidromeProvider
import com.craftworks.music.data.model.JukeboxControlRequest
import com.craftworks.music.data.model.JukeboxDevicesResponse
import com.craftworks.music.data.model.JukeboxPlayRequest
import com.craftworks.music.data.model.JukeboxSelectRequest
import com.craftworks.music.data.model.JukeboxStatusResponse
import com.craftworks.music.data.model.LyricsTranslationRequest
import com.craftworks.music.data.model.LyricsTranslationResponse
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
    val name: String? = null
)

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
}
