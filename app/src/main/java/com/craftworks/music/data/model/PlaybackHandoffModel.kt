package com.craftworks.music.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Navidrome fork (navidrome2all) Playback Handoff / Takeover DTOs.
 *
 * Server contract (see docs/handoff-client-integration.md):
 * - Native endpoints use `X-ND-Authorization: Bearer <JWT>` (NOT standard
 *   `Authorization`), or `?jwt=<JWT>` query fallback.
 * - Identity header `X-ND-Client-Unique-Id: <stableId>` decides `sessionId`.
 * - `volume=0` is treated as "not sent" server-side; omit volume to keep old value.
 * - `bilingual=false` is dropped from JSON via `omitempty` -> treat missing as false.
 * - `takeover` always returns 200 (missing target => `session` absent).
 */
@Serializable
data class PlaybackSessionsResponse(
    val count: Int = 0,
    val sessions: List<PlaybackSessionDto> = emptyList()
)

@Serializable
data class PlaybackSessionDto(
    val sessionId: String,
    val userId: String = "",
    val username: String = "",
    val playerName: String? = null,
    val songId: String,
    val title: String = "",
    val artist: String = "",
    @SerialName("artistId") val artistId: String? = null,
    val album: String = "",
    @SerialName("albumId") val albumId: String? = null,
    val duration: Int = 0,
    val positionMs: Long = 0L,
    val positionSec: Double = 0.0,
    val state: String = "paused",
    val playbackRate: Double = 1.0,
    val coverArtId: String? = null,
    /**
     * Ready-to-load `getCoverArt.view` URL, filled in client-side by
     * `PlaybackHandoffManager.refreshSessions` (the fork only reports ids, and
     * the Subsonic art endpoint needs a salted token). Not part of the wire
     * format. The salt changes on every poll, so image caching must be keyed on
     * `coverArtId` and never on this URL.
     */
    @Transient
    val coverArtUrl: String? = null,
    val lastReport: String? = null,
    val isCurrentSession: Boolean = false,
    val outputDevice: String = "browser",
    // `omitempty` on server: missing => keep old value. Default to null here
    // so we can distinguish "unknown" from "0" (which server maps to 100).
    val volume: Int? = null,
    val playMode: String? = null,
    // `omitempty` bool: missing => false.
    val bilingual: Boolean = false
) {
    /** Effective volume 0..100, defaulting to 100 when server omits it. */
    val effectiveVolume: Int get() = volume?.takeIf { it in 1..100 } ?: 100
}

@Serializable
data class TakeoverRequest(
    val action: String = "pause", // "pause" | "stop"
    val sourceSessionId: String? = null,
    val newPlayerName: String? = null,
    val targetOutput: String? = null // null = inherit victim's outputDevice
)

@Serializable
data class TakeoverResponse(
    val status: String = "",
    val action: String = "pause",
    val takenOverSessionId: String = "",
    val session: PlaybackSessionDto? = null
)

/** SSE `playbackHandoff` event payload broadcast to ALL clients. */
@Serializable
data class PlaybackHandoffEvent(
    val targetSessionId: String,
    val sourceSessionId: String? = null,
    val action: String = "pause",
    val songId: String? = null,
    val positionMs: Long = 0L,
    val newPlayerName: String? = null,
    val outputDevice: String? = null,
    val volume: Int? = null,
    val playMode: String? = null
)
