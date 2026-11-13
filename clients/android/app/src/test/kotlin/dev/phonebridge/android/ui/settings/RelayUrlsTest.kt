package dev.phonebridge.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlsTest {
    private fun assertInvalid(url: String) =
        assertTrue("$url -> ${RelayUrls.check(url)}", RelayUrls.check(url) is RelayUrlCheck.Invalid)

    @Test
    fun emptyAndWhitespace() {
        assertEquals(RelayUrlCheck.Empty, RelayUrls.check(""))
        assertEquals(RelayUrlCheck.Empty, RelayUrls.check("   "))
        assertInvalid("https://relay example.com")
    }

    @Test
    fun httpsIsOkAndNormalized() {
        assertEquals(RelayUrlCheck.Ok("https://relay.example.com"), RelayUrls.check("  https://relay.example.com/  "))
        assertEquals(RelayUrlCheck.Ok("https://relay.example.com:8443/pb"), RelayUrls.check("https://relay.example.com:8443/pb//"))
        assertEquals(RelayUrlCheck.Ok("HTTPS://Relay.Example.com"), RelayUrls.check("HTTPS://Relay.Example.com"))
    }

    @Test
    fun plainHttpToTrustedHostsIsOk() {
        listOf(
            "http://localhost:8080", "http://127.0.0.1:8080", "http://10.0.2.2:8080", "http://192.168.1.5:8080",
            "http://100.70.1.2:8080", "http://my-pc.tail1234.ts.net:8080", "http://[::1]:8080", "http://raspberrypi:8080",
        ).forEach { assertTrue(it, RelayUrls.check(it) is RelayUrlCheck.Ok) }
    }

    @Test
    fun plainHttpToPublicHostWarns() {
        val c = RelayUrls.check("http://relay.example.com")
        assertTrue(c is RelayUrlCheck.Insecure)
        assertEquals("http://relay.example.com", (c as RelayUrlCheck.Insecure).normalized)
        assertTrue(RelayUrls.check("http://203.0.113.7:8080") is RelayUrlCheck.Insecure)
    }

    @Test
    fun invalidInputs() {
        assertInvalid("relay.example.com")
        assertInvalid("wss://relay.example.com")
        assertInvalid("ftp://relay.example.com")
        assertInvalid("https://user:pass@relay.example.com")
        assertInvalid("https://relay.example.com/?x=1")
        assertInvalid("https://relay.example.com/#frag")
        assertInvalid("https://relay.example.com/mcp")
        assertInvalid("https://relay.example.com/v1/device/")
        assertInvalid("https://")
        assertInvalid("https://bad_host.example.com")
        assertInvalid("https://relay.example.com:99999")
    }

    @Test
    fun mcpUrl() {
        assertEquals("https://relay.example.com/mcp", RelayUrls.mcpUrl("https://relay.example.com/"))
        assertEquals("https://relay.example.com/prefix/mcp", RelayUrls.mcpUrl(" https://relay.example.com/prefix "))
        assertEquals("http://relay.example.com/mcp", RelayUrls.mcpUrl("http://relay.example.com"))
        assertNull(RelayUrls.mcpUrl(""))
        assertNull(RelayUrls.mcpUrl("nope"))
    }
}
