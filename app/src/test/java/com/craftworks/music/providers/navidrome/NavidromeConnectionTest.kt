package com.craftworks.music.providers.navidrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Subsonic envelope parser.
 *
 * The parser used to do `jsonObject["subsonic-response"]!!` and required
 * `version` / `type` / `serverVersion` / `openSubsonic`, so any schema deviation
 * (reverse-proxy 502 HTML page, captive portal, an older Subsonic/Airsonic
 * build that never sends `openSubsonic`) threw, and the caller swallowed it into
 * an empty list — the UI just showed "no data".
 */
class NavidromeConnectionTest {

    @Test
    fun `parses a minimal ok response`() {
        val json = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"chora",
            "serverVersion":"0.51.0","openSubsonic":true}}
        """.trimIndent()

        val parsed = parseSubsonicResponse(json)
        assertEquals("ok", parsed.status)
        assertEquals("1.16.1", parsed.version)
        assertTrue(parsed.openSubsonic)
    }

    @Test
    fun `tolerates a server that omits openSubsonic`() {
        // Airsonic / older Subsonic builds don't send the field at all.
        val json = """
            {"subsonic-response":{"status":"ok","version":"1.13.0","type":"chora",
            "serverVersion":"1.13.5"}}
        """.trimIndent()

        val parsed = parseSubsonicResponse(json)
        assertEquals("ok", parsed.status)
        assertFalse(parsed.openSubsonic)
    }

    @Test
    fun `tolerates a completely empty envelope`() {
        val parsed = parseSubsonicResponse("""{"subsonic-response":{}}""")
        // Defaults instead of a MissingFieldException.
        assertEquals("failed", parsed.status)
        assertEquals("", parsed.version)
    }

    @Test
    fun `surfaces a server error payload instead of throwing`() {
        val json = """
            {"subsonic-response":{"status":"failed","version":"1.16.1","type":"chora",
            "serverVersion":"0.51.0","openSubsonic":true,
            "error":{"code":40,"message":"Wrong username or password"}}}
        """.trimIndent()

        val parsed = parseSubsonicResponse(json)
        assertEquals("failed", parsed.status)
        assertEquals(40, parsed.error?.code)
    }

    @Test(expected = SubsonicParseException::class)
    fun `rejects an html error page from a reverse proxy`() {
        parseSubsonicResponse("<html><body>502 Bad Gateway</body></html>")
    }

    @Test(expected = SubsonicParseException::class)
    fun `rejects a login redirect body`() {
        parseSubsonicResponse("""{"token":"abc","expires":123}""")
    }

    @Test(expected = SubsonicParseException::class)
    fun `rejects a non-object json body`() {
        parseSubsonicResponse("""["not","an","object"]""")
    }
}
