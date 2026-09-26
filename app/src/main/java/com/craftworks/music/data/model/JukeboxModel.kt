package com.craftworks.music.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JukeboxDevicesResponse(
    val devices: List<JukeboxDevice> = emptyList(),
    val selected: String = "browser"
)

@Serializable
data class JukeboxDevice(
    val id: String,
    val name: String,
    val type: String // "browser", "mpd", "dlna", "xiaomi"
)

@Serializable
data class JukeboxSelectRequest(
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class JukeboxPlayRequest(
    @SerialName("song_id") val songId: String,
    val position: Long = 0L,
    @SerialName("stream_url") val streamUrl: String? = null
)

@Serializable
data class JukeboxControlRequest(
    val action: String, // "pause", "resume", "stop", "seek", "volume"
    val value: Long? = null
)

@Serializable
data class JukeboxStatusResponse(
    val status: String = "stopped", // "playing", "paused", "stopped"
    val currentTime: Long = 0L,
    val duration: Long = 0L,
    val volume: Int = 0,
    val deviceId: String = "browser",
    val deviceType: String = "browser"
)

