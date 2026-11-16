package com.droidhands.android.graph

import com.droidhands.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayUrlsTest {
    @Test
    fun acceptsHttpAndHttpsBaseUrls() {
        listOf(
            "https://relay.example.com",
            "https://relay.example.com/",
            "http://192.168.1.5:8080",
            "https://relay.example.com/droidhands/",
            "  https://relay.example.com  ",
            "HTTPS://Relay.Example.com",
            "http://[::1]:8080",
            "http://my-pc.tailnet-abc.ts.net:3000",
        ).forEach { assertNull("expected valid: '$it'", RelayUrls.validate(it)) }
    }

    @Test
    fun rejectsUnusableUrls() {
        listOf(
            "",
            "   ",
            "relay.example.com",
            "ftp://relay.example.com",
            "wss://relay.example.com",
            "https://",
            "https:relay.example.com",
            "https://relay.example.com/?key=1",
            "https://relay.example.com/#frag",
            "https://user:secret@relay.example.com",
            "https://exa mple.com",
        ).forEach { assertNotNull("expected invalid: '$it'", RelayUrls.validate(it)) }
    }

    @Test
    fun errorMessagesAreSpecific() {
        assertEquals("Remove the query string (?…) from the relay URL", RelayUrls.validate("https://r.example.com?x=1"))
        assertEquals(
            "The relay URL must start with https:// (or http:// for a LAN/dev relay)",
            RelayUrls.validate("ws://r.example.com"),
        )
    }

    @Test
    fun normalizeTrimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://relay.example.com/pb", RelayUrls.normalize(" https://relay.example.com/pb// "))
        assertEquals("https://relay.example.com", RelayUrls.normalize("https://relay.example.com"))
    }

    @Test
    fun mcpUrlIsDerivedFromBaseUrl() {
        assertEquals("https://relay.example.com/mcp", RelayUrls.mcpUrlFor("https://relay.example.com/"))
        assertEquals("http://10.0.0.2:8080/pb/mcp", RelayUrls.mcpUrlFor("http://10.0.0.2:8080/pb"))
        assertNull(RelayUrls.mcpUrlFor("not a url"))
    }

    @Test
    fun publicMcpUrlPrefersWhatTheRelayAnnounced() {
        val connected = RelayStatus.Connected("https://public.example.com/mcp")
        assertEquals("https://public.example.com/mcp", RelayUrls.publicMcpUrl(connected, "http://10.0.0.2:8080"))
    }

    @Test
    fun publicMcpUrlFallsBackToConfiguredUrl() {
        assertEquals("https://relay.example.com/mcp", RelayUrls.publicMcpUrl(RelayStatus.Connecting, "https://relay.example.com/"))
        assertEquals("https://relay.example.com/mcp", RelayUrls.publicMcpUrl(RelayStatus.Connected(""), "https://relay.example.com"))
        assertEquals(
            "https://relay.example.com/mcp",
            RelayUrls.publicMcpUrl(RelayStatus.Error("offline", 1000), "https://relay.example.com"),
        )
    }

    @Test
    fun publicMcpUrlIsNullWithoutAUsableUrl() {
        assertNull(RelayUrls.publicMcpUrl(RelayStatus.Stopped, ""))
        assertNull(RelayUrls.publicMcpUrl(RelayStatus.Stopped, "ftp://x"))
    }
}
