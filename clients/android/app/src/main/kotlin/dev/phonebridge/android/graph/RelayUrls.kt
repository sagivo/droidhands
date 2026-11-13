package dev.phonebridge.android.graph

import dev.phonebridge.core.transport.RelayStatus
import java.net.URI
import java.net.URISyntaxException

/**
 * Relay URL helpers (pure Kotlin, unit-tested). A relay base URL is `http(s)://host[:port][/prefix]`;
 * the device connects to `ws(s)://…/v1/device` and agents use `…/mcp` (docs/PROTOCOL.md).
 */
object RelayUrls {
    /**
     * Returns null if [raw] is a usable relay base URL, otherwise a short message for the user
     * (shown under the URL field and in the relay status).
     */
    fun validate(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return "Enter the relay URL, e.g. https://relay.example.com"
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return "Not a valid URL (check for spaces or unusual characters)"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "The relay URL must start with https:// (or http:// for a LAN/dev relay)"
        if (uri.isOpaque || uri.host.isNullOrBlank()) return "The relay URL needs a host name, e.g. https://relay.example.com"
        if (uri.rawUserInfo != null) return "Remove the user name/password from the relay URL; use the access key field instead"
        if (uri.port != -1 && uri.port !in 1..65535) return "The relay port must be between 1 and 65535"
        if (uri.rawQuery != null) return "Remove the query string (?…) from the relay URL"
        if (uri.rawFragment != null) return "Remove the fragment (#…) from the relay URL"
        return null
    }

    fun isValid(raw: String): Boolean = validate(raw) == null

    /** Trimmed, without trailing slashes, e.g. "https://relay.example.com/pb". Does not validate. */
    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /** The agent-facing MCP endpoint for a relay base URL, or null if [relayUrl] is not valid. */
    fun mcpUrlFor(relayUrl: String): String? = if (isValid(relayUrl)) normalize(relayUrl) + "/mcp" else null

    /**
     * The public MCP URL to show users and paste into agent configs: what the relay announced in
     * `welcome.mcpUrl` when connected (authoritative behind reverse proxies), else derived from the
     * configured [relayUrl]. Null when neither is available.
     */
    fun publicMcpUrl(status: RelayStatus, relayUrl: String): String? =
        (status as? RelayStatus.Connected)?.mcpUrl?.takeIf { it.isNotBlank() } ?: mcpUrlFor(relayUrl)
}
