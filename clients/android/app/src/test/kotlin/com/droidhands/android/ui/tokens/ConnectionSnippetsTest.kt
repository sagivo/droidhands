package com.droidhands.android.ui.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSnippetsTest {
    private val token = "pb1.mmg42klgyqzwneiskrelxms3j4.abcdefghijkl.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM"
    private val relay = "https://relay.example.com/mcp"

    @Test
    fun endpointsPreferConnectedRelayUrl() {
        val eps = ConnectionSnippets.endpoints(
            relayEnabled = true, relayUrl = "https://typed.example.com", relayConnectedUrl = "https://relay.example.com/mcp",
            localServerEnabled = false, localServerPort = 8765, localUrls = emptyList(),
        )
        assertEquals(listOf(McpEndpoint(EndpointKind.RELAY, "Relay", "https://relay.example.com/mcp")), eps)
    }

    @Test
    fun endpointsFallBackToConfiguredRelayUrl() {
        val eps = ConnectionSnippets.endpoints(true, " https://relay.example.com/ ", null, false, 8765, emptyList())
        assertEquals("https://relay.example.com/mcp", eps.single().url)
        assertTrue(ConnectionSnippets.endpoints(false, "https://relay.example.com", null, false, 8765, emptyList()).isEmpty())
        assertTrue(ConnectionSnippets.endpoints(true, "not a url", null, false, 8765, emptyList()).isEmpty())
    }

    @Test
    fun endpointsIncludeNetworkAndUsb() {
        val eps = ConnectionSnippets.endpoints(
            relayEnabled = false, relayUrl = "", relayConnectedUrl = null,
            localServerEnabled = true, localServerPort = 9000,
            localUrls = listOf("http://127.0.0.1:9000/mcp", "http://192.168.1.20:9000/mcp", "http://100.80.1.2:9000/mcp"),
        )
        assertEquals(listOf(EndpointKind.NETWORK, EndpointKind.NETWORK, EndpointKind.USB), eps.map { it.kind })
        assertEquals("Local network (192.168.1.20)", eps[0].label)
        assertEquals("Tailscale (100.80.1.2)", eps[1].label)
        assertEquals("http://127.0.0.1:9000/mcp", eps[2].url)
    }

    @Test
    fun promptForEndpoints() {
        val relayPrompt = ConnectionSnippets.prompt(McpEndpoint(EndpointKind.RELAY, "Relay", relay), token, 8765)
        assertTrue(relayPrompt.contains("with the URL $relay and"))
        assertTrue(relayPrompt.endsWith("bearer token: $token"))
        val usbEp = McpEndpoint(EndpointKind.USB, "USB", ConnectionSnippets.usbUrl(8765))
        val usb = ConnectionSnippets.prompt(usbEp, token, 8765)
        assertTrue(usb.contains("with the URL http://127.0.0.1:8765/mcp and"))
        assertTrue(usb.contains(token))
        assertTrue(usb.endsWith("run adb forward tcp:8765 tcp:8765 first."))
    }

    @Test
    fun insecureUrls() {
        assertFalse(ConnectionSnippets.isInsecure(relay))
        assertFalse(ConnectionSnippets.isInsecure("http://127.0.0.1:8765/mcp"))
        assertFalse(ConnectionSnippets.isInsecure("http://192.168.1.20:8765/mcp"))
        assertFalse(ConnectionSnippets.isInsecure("http://100.80.1.2:8765/mcp"))
        assertTrue(ConnectionSnippets.isInsecure("http://relay.example.com/mcp"))
        assertTrue(ConnectionSnippets.isInsecure("HTTP://203.0.113.7/mcp"))
    }
}
