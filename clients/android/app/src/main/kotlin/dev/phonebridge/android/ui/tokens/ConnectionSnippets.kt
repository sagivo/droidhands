package dev.phonebridge.android.ui.tokens

import dev.phonebridge.android.ui.format.Hosts
import dev.phonebridge.android.ui.settings.RelayUrls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** How an MCP client reaches this phone. */
enum class EndpointKind { RELAY, NETWORK, USB }

/** One agent-facing MCP URL (`.../mcp`) plus a label for the picker. */
data class McpEndpoint(val kind: EndpointKind, val label: String, val url: String)

/** A copyable block of text for setting up one kind of MCP client. */
data class ConnectionSnippet(
    /** Stable id (a, b, c, d), handy for tests and list keys. */
    val id: String,
    val title: String,
    val hint: String,
    val text: String,
)

/**
 * Generates the endpoint list and client-configuration snippets shown after a token is created.
 * Pure Kotlin (no Android), unit-tested. The token appears in clear text in every snippet, so the
 * UI must treat each snippet as a secret.
 */
object ConnectionSnippets {
    /** MCP server name used in client configs. */
    const val SERVER_NAME = "phone"

    private val prettyJson = Json { prettyPrint = true }

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

    /** Snippets for [endpoint]: for USB the port-forward step comes first. */
    fun forEndpoint(endpoint: McpEndpoint, token: String, localServerPort: Int): List<ConnectionSnippet> = buildList {
        if (endpoint.kind == EndpointKind.USB) add(usbForward(localServerPort))
        add(
            ConnectionSnippet(
                id = "a",
                title = "Claude Code",
                hint = "Run in a terminal on the computer where Claude Code runs.",
                text = claudeCode(endpoint.url, token),
            ),
        )
        add(
            ConnectionSnippet(
                id = "b",
                title = "Claude Desktop, Cursor and others (mcp-remote)",
                hint = "Merge into the client's MCP config file (e.g. claude_desktop_config.json). Needs Node.js (npx).",
                text = mcpRemoteJson(endpoint.url, token),
            ),
        )
        add(
            ConnectionSnippet(
                id = "c",
                title = "Any MCP client",
                hint = "Streamable HTTP endpoint with a bearer token.",
                text = generic(endpoint.url, token),
            ),
        )
    }

    /** `claude mcp add --transport http phone <url> --header "Authorization: Bearer <token>"`. */
    fun claudeCode(url: String, token: String): String =
        "claude mcp add --transport http $SERVER_NAME ${shellQuote(url)} --header \"Authorization: Bearer $token\""

    /**
     * mcp-remote stdio bridge config. The header value goes through an env var (no space after
     * "Authorization:") so it survives argument splitting on Windows. mcp-remote refuses plain
     * http:// to anything but localhost unless `--allow-http` is given.
     */
    fun mcpRemoteJson(url: String, token: String): String {
        val args = buildList {
            add("-y")
            add("mcp-remote")
            add(url)
            if (needsAllowHttp(url)) add("--allow-http")
            add("--header")
            add("Authorization:\${AUTH_HEADER}")
        }
        val root = buildJsonObject {
            put(
                "mcpServers",
                buildJsonObject {
                    put(
                        SERVER_NAME,
                        buildJsonObject {
                            put("command", "npx")
                            put("args", JsonArray(args.map(::JsonPrimitive)))
                            put("env", buildJsonObject { put("AUTH_HEADER", "Bearer $token") })
                        },
                    )
                },
            )
        }
        return prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    /** URL + header, for clients configured by hand. */
    fun generic(url: String, token: String): String =
        "URL: $url\nTransport: Streamable HTTP\nHeader: Authorization: Bearer $token"

    /** Step for USB testing: forward the phone's local port to the computer. */
    fun usbForward(port: Int): ConnectionSnippet = ConnectionSnippet(
        id = "d",
        title = "USB: forward the port first",
        hint = "With the phone connected over USB (USB debugging on), run on the computer, then use the commands below.",
        text = "adb forward tcp:$port tcp:$port\n# MCP endpoint on the computer: ${usbUrl(port)}",
    )

    fun usbUrl(port: Int): String = "http://127.0.0.1:$port/mcp"

    /** True for plain http:// URLs that cross the public internet (token readable in transit). */
    fun isInsecure(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(url) ?: return true
        return !Hosts.isTrustedForPlainHttp(host)
    }

    /** True for plain http:// URLs whose host mcp-remote doesn't treat as localhost. */
    fun needsAllowHttp(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(url)?.let(Hosts::normalize) ?: return true
        return host != "localhost" && host != "127.0.0.1"
    }

    /**
     * POSIX-shell quoting: leaves URL-safe words alone, otherwise wraps in single quotes. Brackets
     * (IPv6 literals) are quoted because zsh treats them as glob patterns.
     */
    fun shellQuote(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() && it.code < 128 || it in SHELL_SAFE }) {
            value
        } else {
            "'" + value.replace("'", "'\\''") + "'"
        }

    private const val SHELL_SAFE = "._~:/@%+=,-"

    private fun hostOf(url: String): String? = try {
        URI(url).host?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
