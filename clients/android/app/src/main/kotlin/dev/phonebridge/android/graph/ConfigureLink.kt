package dev.phonebridge.android.graph

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A validated `phonebridge://configure?relay=<http(s) url>&key=<access key>` link
 * (docs/PROTOCOL.md §5.5), opened from the relay's /setup page, a QR code or `scripts/setup-phone.sh`.
 * Pure Kotlin, unit-tested.
 *
 * Links come from outside the app (MainActivity is exported for them), so the UI must ask
 * "Connect to relay <[relayHost]>?" before [BridgeGraph.applyConfigureLink] saves anything.
 */
class ConfigureLink private constructor(
    /** Normalized relay base URL ([RelayUrls.normalize]), already validated. */
    val relayUrl: String,
    /** Relay access key; empty when the relay is open. */
    val accessKey: String,
) {
    /** "relay.example.com" or "192.168.1.5:8080", for the confirmation dialog. */
    val relayHost: String
        get() = URI(relayUrl).let { uri -> if (uri.port == -1) uri.host else "${uri.host}:${uri.port}" }

    /** False for plain http:// relays (fine on a LAN or Tailscale, risky over the internet). */
    val isHttps: Boolean get() = relayUrl.startsWith("https://", ignoreCase = true)

    override fun equals(other: Any?): Boolean =
        other is ConfigureLink && other.relayUrl == relayUrl && other.accessKey == accessKey

    override fun hashCode(): Int = 31 * relayUrl.hashCode() + accessKey.hashCode()

    /** Never prints the access key. */
    override fun toString(): String = "ConfigureLink(relayUrl=$relayUrl, accessKey=${if (accessKey.isEmpty()) "none" else "***"})"

    /** Outcome of [parseOrError]. */
    sealed interface Parsed {
        data class Valid(val link: ConfigureLink) : Parsed

        /** [message] is shown to the user as is. */
        data class Invalid(val message: String) : Parsed
    }

    companion object {
        const val SCHEME = "phonebridge"
        const val HOST = "configure"
        const val PARAM_RELAY = "relay"
        const val PARAM_KEY = "key"

        private const val MAX_LINK_LENGTH = 4096
        private const val MAX_KEY_LENGTH = 512

        /** The link, or null if [uri] is not a valid configure link. */
        fun parse(uri: String?): ConfigureLink? = (parseOrError(uri) as? Parsed.Valid)?.link

        /**
         * Validates [uri] (e.g. `intent.dataString`). Rejects other schemes and hosts, user info,
         * ports, paths, fragments, repeated parameters (so `relay=a&relay=b` can't be ambiguous),
         * malformed percent-escapes, control characters and relay URLs that [RelayUrls.validate]
         * rejects. Unknown parameters are ignored (forward compatibility). `+` is kept literally,
         * because access keys may contain it.
         */
        fun parseOrError(uri: String?): Parsed {
            val text = uri?.trim().orEmpty()
            if (text.isEmpty()) return invalid("The link is empty.")
            if (text.length > MAX_LINK_LENGTH) return invalid("The link is too long.")
            val parsed = try {
                URI(text)
            } catch (e: URISyntaxException) {
                return invalid("The link is malformed.")
            }
            if (!SCHEME.equals(parsed.scheme, ignoreCase = true) || !HOST.equals(parsed.host, ignoreCase = true)) {
                return invalid("This is not a PhoneBridge setup link.")
            }
            if (parsed.rawUserInfo != null || parsed.port != -1 || parsed.rawFragment != null ||
                !(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/")
            ) {
                return invalid("This PhoneBridge setup link has unexpected parts. Copy it again from your relay's setup page.")
            }

            val params = when (val query = parseQuery(parsed.rawQuery.orEmpty())) {
                null -> return invalid("This PhoneBridge setup link is malformed. Copy it again from your relay's setup page.")
                else -> query
            }
            val relay = params[PARAM_RELAY]?.trim().orEmpty()
            if (relay.isEmpty()) return invalid("The setup link doesn't say which relay to use.")
            RelayUrls.validate(relay)?.let { return invalid("The relay address in the link is not usable: $it") }

            val key = params[PARAM_KEY]?.trim().orEmpty()
            if (key.length > MAX_KEY_LENGTH) return invalid("The access key in the link is too long.")
            if (key.any { it.isISOControl() }) return invalid("The access key in the link contains invalid characters.")

            return Parsed.Valid(ConfigureLink(RelayUrls.normalize(relay), key))
        }

        /** Decoded parameters, or null if a parameter repeats or has a malformed escape or control characters. */
        private fun parseQuery(rawQuery: String): Map<String, String>? {
            val result = HashMap<String, String>()
            for (part in rawQuery.split('&')) {
                if (part.isEmpty()) continue
                val name = decode(part.substringBefore('=')) ?: return null
                val value = decode(if ('=' in part) part.substringAfter('=') else "") ?: return null
                if (name.any { it.isISOControl() } || value.any { it.isISOControl() }) return null
                if (result.put(name, value) != null) return null
            }
            return result
        }

        /** RFC 3986 percent-decoding (UTF-8); unlike form decoding, `+` stays a plus sign. */
        private fun decode(raw: String): String? = try {
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun invalid(message: String) = Parsed.Invalid(message)
    }
}
