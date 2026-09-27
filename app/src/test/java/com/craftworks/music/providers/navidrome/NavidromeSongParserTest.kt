package com.craftworks.music.providers.navidrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the single-song (`/rest/getSong.view`) parser.
 *
 * Playback Handoff resolves the session's `songId` through
 * `SongRepository.getSong`, which routes into the endpoint router in
 * `NavidromeDataSource`. That router had **no** `getSong` branch, so the call
 * fell through to the generic handler and every takeover got `null` — the
 * "song not found" toast, with no hint that the payload had been discarded.
 */
class NavidromeSongParserTest {

    private val getSongJson = """
        {"subsonic-response":{
          "status":"ok","version":"1.16.1","type":"navidrome",
          "serverVersion":"0.51.0","openSubsonic":true,
          "song":{
            "id":"f8b62a0a62a94b0d9b1e0f2b3c4d5e6f","parent":"al0000000000000001",
            "isDir":false,"title":"Night Drive","album":"Neon Hours",
            "artist":"The Canaries","artists":[{"id":"ar1","name":"The Canaries"}],
            "track":3,"year":1981,"genre":"Synthwave",
            "genres":[{"title":"Synthwave"}],
            "coverArt":"al1","size":26148300,"contentType":"audio/flac",
            "suffix":"flac","duration":222,"bitRate":940,
            "path":"The Canaries/Neon Hours/03 Night Drive.flac",
            "playCount":7,"discNumber":1,"created":"2024-05-01T10:00:00Z",
            "albumId":"al1","artistId":"ar1","type":"audio","mediaType":"song",
            "isVideo":false,"bpm":118,"replayGain":{"trackGain":-0.42},
            "userRating":4,"explicitStatus":"explicit"
          }
        }}
    """.trimIndent()

    @Test
    fun `getSong payload decodes into a song with every handoff field intact`() {
        val song = parseSubsonicResponse(getSongJson).song

        assertNotNull("getSong.view must decode its single 'song' object", song)
        assertEquals("f8b62a0a62a94b0d9b1e0f2b3c4d5e6f", song!!.navidromeID)
        assertEquals("Night Drive", song.title)
        assertEquals("The Canaries", song.artist)
        // Handoff inherits these two directly from the payload.
        assertEquals(222, song.duration)
        assertEquals(4, song.userRating)
    }

    @Test
    fun `stream and cover urls are signed for this install`() {
        // `parseNavidromeSongJSON` ends in `toMediaItem()`, which needs a live
        // android.net.Uri (stubbed to null under plain unit tests). The signing
        // step is what a takeover actually depends on.
        val song = checkNotNull(
            signNavidromeSong(
                getSongJson,
                "http://10.0.0.5:4533",
                "liubo",
                "secret"
            )
        ) { "takeover would have nothing to play" }

        val media = song.media!!
        assertTrue(media.startsWith("http://10.0.0.5:4533/rest/stream.view"))
        assertTrue(media.contains("id=f8b62a0a62a94b0d9b1e0f2b3c4d5e6f"))
        assertTrue(media.contains("u=liubo"))
        // t = md5(password + salt); the raw password must never reach the URL.
        assertTrue(media.contains("t=") && media.contains("s="))
        assertFalse("password leaked into the stream URL", media.contains("secret"))
        assertTrue(song.imageUrl!!.contains("getCoverArt.view"))
    }

    @Test
    fun `a server-side error yields null instead of throwing`() {
        // A song the user cannot see (or a purged id) answers with `failed`.
        val error = """
            {"subsonic-response":{"status":"failed","version":"1.16.1",
             "error":{"code":70,"message":"Not found"}}}
        """.trimIndent()

        assertNull(signNavidromeSong(error, "http://10.0.0.5:4533", "liubo", "secret"))
    }

    @Test
    fun `an ok envelope without a song yields null`() {
        val empty = """
            {"subsonic-response":{"status":"ok","version":"1.16.1",
             "type":"navidrome","serverVersion":"0.51.0","openSubsonic":true}}
        """.trimIndent()

        assertNull(signNavidromeSong(empty, "http://10.0.0.5:4533", "liubo", "secret"))
    }
}
