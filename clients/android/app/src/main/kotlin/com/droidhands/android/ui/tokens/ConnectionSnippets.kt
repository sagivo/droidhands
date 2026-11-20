package com.droidhands.android.ui.tokens

import com.droidhands.android.ui.connect.AgentPrompt
import com.droidhands.android.ui.format.Hosts
import com.droidhands.android.ui.settings.RelayUrls
import java.net.URI

/** How an MCP client reaches this phone. */
enum class EndpointKind { RELAY, NETWORK, USB }

/** One agent-facing MCP URL (`.../mcp`) plus a label for the picker. */
data class McpEndpoint(val kind: EndpointKind, val label: String, val url: String)

/**
 * The endpoint list and the message for the agent ([AgentPrompt.withToken]) shown after a token is created.
 * Pure Kotlin (no Android), unit-tested. The token is in clear text in the message, so the UI must treat it as
 * a secret.
 */
object ConnectionSnippets {
    /**
     * Endpoints in display order: relay (when configured), LAN/Tailscale URLs reported by the
     * running local server, then USB via `adb forward` (when the local server is enabled).
     *
     * @param relayConnectedUrl `RelayStatus.Connected.mcpUrl`, if connected (authoritative: it comes from the relay).
     * @param localUrls `LocalServerStatus.Running.urls`, empty if not running.
     */
    fun endpoints(
        relayEnabled: Boolean,
        relayUrl: String,
        relayConnectedUrl: String?,
        localServerEnabled: Boolean,
        localServerPort: Int,
        localUrls: List<String>,
    ): List<McpEndpoint> = buildList {
        val relay = relayConnectedUrl?.takeIf { it.isNotBlank() }
            ?: if (relayEnabled) RelayUrls.mcpUrl(relayUrl) else null
        if (relay != null) add(McpEndpoint(EndpointKind.RELAY, "Relay", relay))

        localUrls.distinct().forEach { url ->
            val host = hostOf(url) ?: return@forEach
            if (Hosts.isLoopback(host)) return@forEach
            val network = if (Hosts.isTailscale(host)) "Tailscale" else "Local network"
            add(McpEndpoint(EndpointKind.NETWORK, "$network ($host)", url))
        }

        if (localServerEnabled) {
            add(McpEndpoint(EndpointKind.USB, "USB (adb forward)", usbUrl(localServerPort)))
        }
    }

    /** The message that adds [endpoint] with [token] to any agent; over USB it forwards the port first. */
    fun prompt(endpoint: McpEndpoint, token: String, localServerPort: Int): String = AgentPrompt.withToken(
        endpoint.url,
        token,
        extraStep = if (endpoint.kind == EndpointKind.USB) {
            "The phone is plugged into this computer over USB: run adb forward tcp:$localServerPort tcp:$localServerPort first."
        } else {
            null
        },
    )

    fun usbUrl(port: Int): String = "http://127.0.0.1:$port/mcp"

    /** True for plain http:// URLs that cross the public internet (token readable in transit). */
    fun isInsecure(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(url) ?: return true
        return !Hosts.isTrustedForPlainHttp(host)
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
