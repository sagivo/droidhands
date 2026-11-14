// Adapted from T3 Code (MIT, Copyright (c) 2026 T3 Tools Inc.), apps/mobile/src/features/connection/pairing.ts
package dev.phonebridge.android.ui.deeplink

import dev.phonebridge.android.ui.settings.RelayUrlCheck
import dev.phonebridge.android.ui.settings.RelayUrls
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Reads a scanned QR code (or pasted text) as a relay setup link, tolerantly (pure, unit-tested). Accepted:
 *
 * * the relay's setup page, `https://relay.example.com/setup#key=…` (the QR code the relay prints and its
 *   setup page shows; the key is optional and travels in the fragment), also under a path prefix;
 * * the `phonebridge://configure?relay=…&key=…` link the setup page opens (or its `intent://configure?…`
 *   form);
 * * a bare relay URL, `https://relay.example.com` (or without the scheme: https, or http for an IP address).
 *
 * Surrounding whitespace and the scheme's case don't matter. Everything else (other paths, other schemes such
 * as `javascript:`, an agent's `…/mcp` connection URL, plain text) is refused with a message for the user.
 * The result goes through [ConfigureLinks.parse], so a scanned code is checked exactly like a setup link and,
 * like one, is only applied after the user confirms ("Connect to relay?").
 */
object SetupQrPayload {
    /** Longer payloads are refused (a setup link is far shorter). */
    private const val MAX_LENGTH = 4096

    private const val SETUP_PATH = "/setup"

    const val NOT_A_SETUP_LINK =
        "This QR code isn't a PhoneBridge relay setup link. Scan the code your relay prints when it starts, or the one " +
            "on its setup page (its address ends in /setup)."

    const val AGENT_URL =
        "This is the address agents connect to, not a relay setup link. To connect this phone, scan the QR code your " +
            "relay prints when it starts, or the one on its setup page."

    fun parse(payload: String?): ConfigureLinkResult {
        val text = payload?.trim().orEmpty()
        if (text.isEmpty()) return ConfigureLinkResult.Invalid("The QR code is empty.")
        if (text.length > MAX_LENGTH) return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        if (text.any { it.isWhitespace() || it.isISOControl() }) return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        return when (scheme) {
            ConfigureLinks.SCHEME -> ConfigureLinks.parse(text)
            "intent" -> fromIntentUri(text)
            "http", "https" -> fromWebUrl(text)
            null -> schemeless(text)
            else -> ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        }
    }

    /**
     * True if [text] is a usable setup link rather than a plain relay URL (the setup page with its key, or a
     * `phonebridge://` link): pasted into the relay URL field, it goes to the "Connect to relay?" dialog.
     */
    fun isSetupLink(text: String): Boolean {
        if (parse(text) !is ConfigureLinkResult.Valid) return false
        val plain = RelayUrls.check(text)
        return plain !is RelayUrlCheck.Ok && plain !is RelayUrlCheck.Insecure
    }

    /** "relay.example.com/setup#key=…" or "192.168.1.5:8080": https, or http for an IP address (as T3 Code's buildPairingUrl). */
    private fun schemeless(text: String): ConfigureLinkResult {
        val authority = text.substringBefore('/').substringBefore('#').substringBefore('?')
        val ip = isIpLiteral(authority)
        // Plain words ("hello") are not host names.
        if (!ip && '.' !in authority.substringBefore(':')) return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        return fromWebUrl("${if (ip) "http" else "https"}://$text")
    }

    /** `intent://configure?<query>#Intent;scheme=phonebridge;…;end`, the setup page's Android link. */
    private fun fromIntentUri(text: String): ConfigureLinkResult {
        val fragment = text.substringAfter('#', "")
        val targetsUs = fragment.split(';').any { it.equals("scheme=${ConfigureLinks.SCHEME}", ignoreCase = true) }
        if (!targetsUs) return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        return ConfigureLinks.parse("${ConfigureLinks.SCHEME}:" + text.substringBefore('#').substringAfter(':'))
    }

    /** The setup page (`<base>/setup[#key=…]`) or a bare relay URL. */
    private fun fromWebUrl(text: String): ConfigureLinkResult {
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        }
        if (uri.isOpaque || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val base = when {
            path.isEmpty() -> ""
            path.endsWith(SETUP_PATH, ignoreCase = true) -> path.dropLast(SETUP_PATH.length)
            path.endsWith("/mcp", ignoreCase = true) -> return ConfigureLinkResult.Invalid(AGENT_URL)
            else -> return ConfigureLinkResult.Invalid(NOT_A_SETUP_LINK)
        }
        val origin = "${uri.scheme.lowercase()}://${uri.rawAuthority}$base"
        // The relay puts the key in the fragment (never sent to a server); accept it in the query too, as T3 Code does.
        val key = param(uri.rawFragment, "key") ?: param(uri.rawQuery, "key")
        val link = "${ConfigureLinks.SCHEME}://${ConfigureLinks.HOST}?relay=${encode(origin)}" + (key?.let { "&key=${encode(it)}" } ?: "")
        return ConfigureLinks.parse(link)
    }

    /** First value of [name] in a raw `a=b&c=d` string, percent-decoded with `+` kept literally; null if absent or empty. */
    private fun param(raw: String?, name: String): String? {
        if (raw.isNullOrEmpty()) return null
        for (pair in raw.split('&')) {
            if (pair.substringBefore('=') != name || '=' !in pair) continue
            return try {
                URLDecoder.decode(pair.substringAfter('=').replace("+", "%2B"), StandardCharsets.UTF_8).takeIf { it.isNotEmpty() }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    /** An IPv4 or bracketed IPv6 address, with or without a port (T3 Code's `isIpLiteral`). */
    internal fun isIpLiteral(hostAndPort: String): Boolean {
        if (hostAndPort.startsWith("[")) return hostAndPort.contains("]")
        val octets = hostAndPort.substringBefore(':').split('.')
        return octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
    }

    /** A URI scheme at the start (RFC 3986), e.g. "HTTPS:" or "javascript:". "host:8080" is a host and port, not a scheme. */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(?!\\d+(?:[/#?]|$))")
}
