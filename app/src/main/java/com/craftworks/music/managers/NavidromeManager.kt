package com.craftworks.music.managers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.craftworks.music.data.NavidromeLibrary
import com.craftworks.music.data.NavidromeProvider
import com.craftworks.music.data.datasource.navidrome.NavidromeDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NavidromeManager {
    // Read from IO/loader threads (redirect resolution, playback data spec) and
    // written from the main thread (add/remove server, library toggles). A plain
    // LinkedHashMap could hand out a torn view or throw
    // ConcurrentModificationException while saveServers() encoded it.
    private val servers = ConcurrentHashMap<String, NavidromeProvider>()

    private var _currentServerId = MutableStateFlow<String?>(null)
    val currentServerId: StateFlow<String?> = _currentServerId.asStateFlow()

    private val _allServers = MutableStateFlow<List<NavidromeProvider>>(emptyList())
    val allServers: StateFlow<List<NavidromeProvider>> = _allServers.asStateFlow()

    private var _libraries = MutableStateFlow<List<Pair<NavidromeLibrary, Boolean>>>(emptyList())
    val libraries: StateFlow<List<Pair<NavidromeLibrary, Boolean>>> =
        _libraries
            .stateIn(
                CoroutineScope(Dispatchers.Main.immediate),
                SharingStarted.Eagerly,
                emptyList()
            )

    private val _syncStatus = MutableStateFlow(false)

    suspend fun resolveActiveServerUrl(server: NavidromeProvider, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!forceRefresh && !server.activeBaseUrl.isNullOrBlank()) {
            return@withContext server.activeBaseUrl!!
        }

        try {
            val urlObj = URI(server.url).toURL()
            var conn = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                if (this is HttpsURLConnection && server.allowSelfSignedCert == true) {
                    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })
                    val sc = SSLContext.getInstance("TLS")
                    sc.init(null, trustAll, SecureRandom())
                    sslSocketFactory = sc.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }
            conn.connect()
            var code = conn.responseCode
            if (code == 405) {
                conn.disconnect()
                conn = (urlObj.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    if (this is HttpsURLConnection && server.allowSelfSignedCert == true) {
                        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        })
                        val sc = SSLContext.getInstance("TLS")
                        sc.init(null, trustAll, SecureRandom())
                        sslSocketFactory = sc.socketFactory
                        hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    }
                }
                conn.connect()
                code = conn.responseCode
            }

            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val targetUri = URI(location)
                    val resolvedUri = if (targetUri.isAbsolute) targetUri else urlObj.toURI().resolve(targetUri)
                    val portPart = if (resolvedUri.port != -1) ":${resolvedUri.port}" else ""
                    val resolvedOrigin = "${resolvedUri.scheme}://${resolvedUri.host}$portPart".trimEnd('/')
                    Log.d("NAVIDROME", "Resolved dynamic redirect URL: $resolvedOrigin (entry was ${server.url})")
                    server.activeBaseUrl = resolvedOrigin
                    withContext(Dispatchers.Main) {
                        saveServers()
                    }
                    conn.disconnect()
                    return@withContext resolvedOrigin
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("NAVIDROME", "Could not probe redirect for ${server.url}: ${e.message}")
        }

        server.activeBaseUrl = server.url.trimEnd('/')
        return@withContext server.activeBaseUrl!!
    }

    suspend fun addServer(server: NavidromeProvider, isPing: Boolean = false) {
        Log.d("NAVIDROME", "Added server $server")
        server.url = if (!server.url.trim().startsWith("http"))
            "http://" + server.url.trim().trimEnd('/')
        else
            server.url.trim().trimEnd('/')

        resolveActiveServerUrl(server, forceRefresh = true)

        servers[server.id] = server
        _currentServerId.value = server.id

        if (isPing)
            return

        val fetchedLibraries = NavidromeDataSource().getNavidromeLibraries().map { Pair(it, true) }

        setServerLibraries(server.id, fetchedLibraries)
        if (server.id == _currentServerId.value) {
            _libraries.value = fetchedLibraries
        }

        updateServersFlow()
        saveServersAndInvalidateData()
    }

    fun setServerLibraries(serverId: String, libraries: List<Pair<NavidromeLibrary, Boolean>>) {
        servers[serverId]?.libraryIds = libraries
        if (serverId == _currentServerId.value) {
            _libraries.value = libraries
        }
        saveServers()
    }

    fun toggleServerLibraryEnabled(serverId: String, libraryId: Int, isEnabled: Boolean) {
        servers[serverId]?.let { server ->
            val updatedLibraries = server.libraryIds.map { (library, currentEnabled) ->
                if (library.id == libraryId) {
                    Pair(library, isEnabled)
                } else {
                    Pair(library, currentEnabled)
                }
            }
            server.libraryIds = updatedLibraries
            if (serverId == _currentServerId.value) {
                if (_libraries.value != updatedLibraries) {
                    _libraries.value = updatedLibraries
                }
            }
            saveServers()
        }
    }

    fun removeServer(id: String, isPing: Boolean = false) {
        servers.remove(id)
        if (_currentServerId.value == id) {
            _currentServerId.value = servers.keys.firstOrNull()
            _libraries.value = _currentServerId.value?.let { servers[it]?.libraryIds } ?: emptyList()
        }
        if (isPing)
            return

        updateServersFlow()
        saveServersAndInvalidateData()
    }

    fun checkActiveServers(): Boolean {
        return servers.keys.isNotEmpty() && _currentServerId.value != null
    }

    fun getAllServers(): List<NavidromeProvider> = servers.values.toList()
    fun getCurrentServer(): NavidromeProvider? = _currentServerId.value?.let { servers[it] }

    fun setCurrentServer(serverId: String?) {
        _currentServerId.value = serverId
        _libraries.value = serverId?.let { servers[it]?.libraryIds } ?: emptyList()
        // Switching servers changes which library the user is looking at.
        saveServersAndInvalidateData()
        getCurrentServer()?.let { server ->
            CoroutineScope(Dispatchers.IO).launch {
                resolveActiveServerUrl(server, forceRefresh = true)
            }
        }
    }

    private fun updateServersFlow() {
        _allServers.value = servers.values.toList()
    }

    fun setSyncingStatus(status: Boolean) { _syncStatus.value = status }

    // Save and load navidrome servers.
    private lateinit var sharedPreferences: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private const val PREF_SERVERS = "navidrome_servers"
    private const val PREF_CURRENT_SERVER = "current_server_id"

    fun init(context: Context) {
        setSyncingStatus(true)
        sharedPreferences = context.getSharedPreferences("NavidromePrefs", Context.MODE_PRIVATE)
        loadServers()
        setSyncingStatus(false)
    }

    /**
     * Persists the server list. Pure persistence: it does NOT invalidate the
     * music caches. Persisting and invalidating used to be fused, so a runtime
     * probe writing `activeBaseUrl` (which happens on every playback stream that
     * hits a 302) made all six screen ViewModels re-fetch their whole library
     * with `ignoreCachedResponse = true`.
     */
    fun saveServers() {
        val serversJson = json.encodeToString(servers as Map<String, NavidromeProvider>)
        sharedPreferences.edit {
            putString(PREF_SERVERS, serversJson)
            putString(PREF_CURRENT_SERVER, _currentServerId.value)
        }
    }

    /** For callers whose change genuinely alters what the servers expose. */
    fun saveServersAndInvalidateData() {
        DataRefreshManager.notifyDataSourcesChanged()
        saveServers()
    }

    private fun loadServers() {
        _currentServerId.value = sharedPreferences.getString(PREF_CURRENT_SERVER, null)
        val serversJson = sharedPreferences.getString(PREF_SERVERS, null)
        if (serversJson != null) {
            val loadedServers: Map<String, NavidromeProvider> = json.decodeFromString(serversJson)
            servers.putAll(loadedServers)
        }
        _libraries.value = _currentServerId.value?.let { servers[it]?.libraryIds } ?: emptyList()
        updateServersFlow()

        getCurrentServer()?.let { server ->
            CoroutineScope(Dispatchers.IO).launch {
                resolveActiveServerUrl(server, forceRefresh = false)
            }
        }
    }

    fun getEnabledLibraryIdsForCurrentServer(): List<Int> {
        return _currentServerId.value?.let { serverId ->
            servers[serverId]?.libraryIds
                ?.filter { it.second }
                ?.map { it.first.id }
        } ?: emptyList()
    }
}