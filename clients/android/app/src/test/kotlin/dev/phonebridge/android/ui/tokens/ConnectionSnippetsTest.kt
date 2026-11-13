package dev.phonebridge.android.ui.tokens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSnippetsTest {
    private val token = "pb1.mmg42klgyqzwneiskrelxms3j4.abcdefghijkl.ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM"
    private val relay = "https://relay.example.com/mcp"

    @Test
    fun claudeCodeCommand() {
        assertEquals(
            "claude mcp add --transport http phone https://relay.example.com/mcp --header \"Authorization: Bearer $token\"",
            ConnectionSnippets.claudeCode(relay, token),
        )
    }

    @Test
    fun claudeCodeQuotesUnsafeUrls() {
        val cmd = ConnectionSnippets.claudeCode("http://[fd7a:115c:a1e0::1]:8765/mcp", token)
        assertTrue(cmd, cmd.contains(" 'http://[fd7a:115c:a1e0::1]:8765/mcp' "))
        assertEquals("'it'\\''s'", ConnectionSnippets.shellQuote("it's"))
        assertEquals("http://127.0.0.1:8765/mcp", ConnectionSnippets.shellQuote("http://127.0.0.1:8765/mcp"))
    }

    @Test
    fun mcpRemoteJsonStructure() {
        val json = Json.parseToJsonElement(ConnectionSnippets.mcpRemoteJson(relay, token)).jsonObject
        val server = json["mcpServers"]!!.jsonObject["phone"]!!.jsonObject
        assertEquals("npx", server["command"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("-y", "mcp-remote", relay, "--header", "Authorization:\${AUTH_HEADER}"),
            server["args"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("Bearer $token", server["env"]!!.jsonObject["AUTH_HEADER"]!!.jsonPrimitive.content)
    }

    @Test
    fun mcpRemoteAllowsHttpOnlyWhenNeeded() {
        fun args(url: String) = Json.parseToJsonElement(ConnectionSnippets.mcpRemoteJson(url, token)).jsonObject["mcpServers"]!!
            .jsonObject["phone"]!!.jsonObject["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("--allow-http" in args("http://192.168.1.20:8765/mcp"))
        assertFalse("--allow-http" in args("http://127.0.0.1:8765/mcp"))
        assertFalse("--allow-http" in args("http://localhost:8765/mcp"))
        assertFalse("--allow-http" in args(relay))
    }

    @Test
    fun mcpRemoteJsonEscapes() {
        val url = "https://relay.example.com/a\"b/mcp"
        val parsed = Json.parseToJsonElement(ConnectionSnippets.mcpRemoteJson(url, token)).jsonObject
        val args = parsed["mcpServers"]!!.jsonObject["phone"]!!.jsonObject["args"]!!.jsonArray
        assertEquals(url, args[2].jsonPrimitive.content)
    }

    @Test
    fun genericAndUsb() {
        val generic = ConnectionSnippets.generic(relay, token)
        assertTrue(generic.contains("URL: $relay"))
        assertTrue(generic.contains("Authorization: Bearer $token"))
        val usb = ConnectionSnippets.usbForward(8765)
        assertTrue(usb.text.startsWith("adb forward tcp:8765 tcp:8765\n"))
        assertTrue(usb.text.contains("http://127.0.0.1:8765/mcp"))
    }

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
    fun snippetsForEndpoints() {
        val relayEp = McpEndpoint(EndpointKind.RELAY, "Relay", relay)
        assertEquals(listOf("a", "b", "c"), ConnectionSnippets.forEndpoint(relayEp, token, 8765).map { it.id })
        val usbEp = McpEndpoint(EndpointKind.USB, "USB", ConnectionSnippets.usbUrl(8765))
        val usb = ConnectionSnippets.forEndpoint(usbEp, token, 8765)
        assertEquals(listOf("d", "a", "b", "c"), usb.map { it.id })
        assertTrue(usb[1].text.contains("http://127.0.0.1:8765/mcp"))
        usb.drop(1).forEach { assertTrue(it.id, it.text.contains(token)) }
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
