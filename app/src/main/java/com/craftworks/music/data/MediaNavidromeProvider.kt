package com.craftworks.music.data

import kotlinx.serialization.Serializable

@Serializable
data class NavidromeProvider (
    val id: String = "0",
    var url:String,
    var username:String,
    val password:String,
    val enabled:Boolean? = true,
    var allowSelfSignedCert: Boolean? = false,
    // List of library folders and if they're enabled or not.
    var libraryIds: List<Pair<NavidromeLibrary, Boolean>> = listOf(Pair(NavidromeLibrary(0, "Media Library"), true)),
    var activeBaseUrl: String? = null
) {
    fun getEffectiveUrl(): String = activeBaseUrl ?: url

    /**
     * The server list is logged (and used in error messages) all over the app.
     * The generated `toString()` used to expose the plaintext password to
     * logcat, so redact it.
     */
    override fun toString(): String =
        "NavidromeProvider(id=$id, url=$url, username=$username, password=***, " +
                "enabled=$enabled, allowSelfSignedCert=$allowSelfSignedCert, " +
                "libraryIds=${libraryIds.map { it.first.id }}, activeBaseUrl=$activeBaseUrl)"
}

@Serializable
data class NavidromeLibrary (
    val id: Int = 0,
    var name:String,
)