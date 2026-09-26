package com.craftworks.music.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Subsonic call carries `t=<auth token>&s=<salt>&u=<user>` in the query
 * string, and those three together authenticate as the user. They were being
 * written to logcat in full (`LogLevel.ALL` plus hand-built `"...for URL: $url"`
 * messages), so any app with READ_LOGS — or plain `adb logcat` — could hijack
 * the account.
 */
class RedactSubsonicUrlTest {

    @Test
    fun `strips token salt and username`() {
        val url = "https://music.example.com/rest/getAlbumList.view" +
                "?u=alice&t=deadbeef&s=Ab3xYz&v=1.16.1&c=Chora&f=json"

        val redacted = redactSubsonicUrl(url)

        assertFalse(redacted.contains("deadbeef"))
        assertFalse(redacted.contains("Ab3xYz"))
        assertFalse(redacted.contains("alice"))
        assertTrue(redacted.startsWith("https://music.example.com/rest/getAlbumList.view"))
    }

    @Test
    fun `keeps non-sensitive parameters`() {
        val url = "https://host/rest/getAlbum.view?u=bob&t=tok&s=slt&album=42&v=1.16.1"

        val redacted = redactSubsonicUrl(url)

        assertTrue(redacted.contains("album=42"))
        assertTrue(redacted.contains("v=1.16.1"))
    }

    @Test
    fun `leaves a url without a query untouched`() {
        val url = "https://host/api/jukebox/select"
        assertEquals(url, redactSubsonicUrl(url))
    }

    @Test
    fun `drops the query entirely when it only held credentials`() {
        val url = "https://host/rest/ping.view?u=bob&t=tok&s=slt"
        assertEquals("https://host/rest/ping.view", redactSubsonicUrl(url))
    }
}
