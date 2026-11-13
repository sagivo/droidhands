package dev.phonebridge.android.ui.settings

import dev.phonebridge.android.ui.format.Hosts
import java.net.URI
import java.net.URISyntaxException

/** Result of checking the relay base URL typed in Settings. */
sealed interface RelayUrlCheck {
    data object Empty : RelayUrlCheck

    /** Not usable; [message] tells the user how to fix it. */
    data class Invalid(val message: String) : RelayUrlCheck

    /** Usable, but plain http:// to a public host: [message] explains the risk. */
    data class Insecure(val normalized: String, val message: String) : RelayUrlCheck

    data class Ok(val normalized: String) : RelayUrlCheck
}

/** Validation and normalization of relay base URLs (pure; uses java.net.URI, not android.net.Uri). */
object RelayUrls {
    const val INSECURE_WARNING =
        "This is unencrypted http:// over the internet: anyone on the network path can read your access key " +
            "and every agent request, including tokens. Use https://, or http:// only on localhost, a private " +
            "network or Tailscale."

    /** Trims whitespace and trailing slashes. */
    fun normalize(input: String): String = input.trim().trimEnd('/')

    fun check(input: String): RelayUrlCheck {
        val text = normalize(input)
        if (text.isEmpty()) return RelayUrlCheck.Empty
        if (text.any { it.isWhitespace() }) return RelayUrlCheck.Invalid("The URL can't contain spaces.")
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return RelayUrlCheck.Invalid("Not a valid URL. Example: https://relay.example.com")
        }
        val scheme = uri.scheme?.lowercase()
            ?: return RelayUrlCheck.Invalid("Start with https:// (for example https://relay.example.com).")
        when (scheme) {
            "http", "https" -> Unit
            "ws", "wss" -> return RelayUrlCheck.Invalid(
                "Use https:// (or http://). PhoneBridge switches to a WebSocket by itself.",
            )
            else -> return RelayUrlCheck.Invalid("Only https:// and http:// URLs are supported.")
        }
        if (uri.rawUserInfo != null) {
            return RelayUrlCheck.Invalid("Don't put credentials in the URL. Use the access key field instead.")
        }
        val host = uri.host
        if (host.isNullOrEmpty()) return RelayUrlCheck.Invalid("Missing or invalid host name.")
        if (uri.port != -1 && uri.port !in 1..65535) return RelayUrlCheck.Invalid("The port must be between 1 and 65535.")
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return RelayUrlCheck.Invalid("Remove the ?query or #fragment part.")
        }
        val path = uri.rawPath.orEmpty().lowercase()
        if (path.endsWith("/mcp") || path.endsWith("/v1/device")) {
            return RelayUrlCheck.Invalid("Enter the relay's base URL, without /mcp or /v1/device (e.g. https://relay.example.com).")
        }
        return if (scheme == "http" && !Hosts.isTrustedForPlainHttp(host)) {
            RelayUrlCheck.Insecure(text, INSECURE_WARNING)
        } else {
            RelayUrlCheck.Ok(text)
        }
    }

    /** The agent-facing endpoint for a relay base URL (`<base>/mcp`), or null if the URL is unusable. */
    fun mcpUrl(relayUrl: String): String? = when (val c = check(relayUrl)) {
        is RelayUrlCheck.Ok -> c.normalized + "/mcp"
        is RelayUrlCheck.Insecure -> c.normalized + "/mcp"
        else -> null
    }
}
