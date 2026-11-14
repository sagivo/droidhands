package dev.phonebridge.android.ui.deeplink

import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.android.ui.settings.RelayUrlCheck
import dev.phonebridge.android.ui.settings.RelayUrls
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A validated `phonebridge://configure?relay=…&key=…` setup link (docs/PROTOCOL.md §5.5), waiting
 * for the user to confirm. [accessKey] is a secret: [toString] and [displayLink] never include it.
 */
class ConfigureRequest(
    /** Normalized relay base URL, e.g. `https://relay.example.com`. */
    val relayUrl: String,
    /** RELAY_ACCESS_KEY from the link, or null if the link has none. */
    val accessKey: String?,
    /** Plain http:// to a public host: traffic (and the key) would be readable in transit. */
    val insecure: Boolean,
) {
    /** Host (and port, if any) of [relayUrl], for "Connect to relay <host>?". */
    val host: String = ConfigureLinks.hostOf(relayUrl) ?: relayUrl

    /** The link as the user should see it: the full relay URL, the key masked. */
    val displayLink: String
        get() = "phonebridge://configure?relay=$relayUrl" + if (accessKey != null) "&key=${ConfigureLinks.MASK}" else ""

    override fun toString(): String = "ConfigureRequest(relayUrl=$relayUrl, accessKey=${if (accessKey == null) "absent" else "***"})"

    override fun equals(other: Any?): Boolean =
        other is ConfigureRequest && other.relayUrl == relayUrl && other.accessKey == accessKey && other.insecure == insecure

    override fun hashCode(): Int = 31 * relayUrl.hashCode() + (accessKey?.hashCode() ?: 0)
}

/** Outcome of reading a setup link. */
sealed interface ConfigureLinkResult {
    data class Valid(val request: ConfigureRequest) : ConfigureLinkResult

    /** [message] tells the user what is wrong with the link. */
    data class Invalid(val message: String) : ConfigureLinkResult
}

/** Pure parsing and application of setup links (unit-tested; uses java.net, not android.net). */
object ConfigureLinks {
    const val SCHEME = "phonebridge"
    const val HOST = "configure"
    const val MASK = "••••••"

    /** Access keys longer than this are rejected (a sane bound for a pasted secret). */
    private const val MAX_KEY_LENGTH = 512

    /** True if [uri] is a setup link at all (scheme and host match), valid or not. */
    fun isConfigureLink(uri: String?): Boolean {
        val parsed = parseUri(uri ?: return false) ?: return false
        return parsed.scheme.equals(SCHEME, ignoreCase = true) && parsed.host.equals(HOST, ignoreCase = true)
    }

    fun parse(uri: String): ConfigureLinkResult {
        val parsed = parseUri(uri) ?: return ConfigureLinkResult.Invalid("This setup link is malformed.")
        if (!parsed.scheme.equals(SCHEME, ignoreCase = true) || !parsed.host.equals(HOST, ignoreCase = true)) {
            return ConfigureLinkResult.Invalid("This isn't a PhoneBridge setup link.")
        }
        val params = queryParameters(parsed.rawQuery)
        val relay = params["relay"]?.trim().orEmpty()
        if (relay.isEmpty()) return ConfigureLinkResult.Invalid("The setup link doesn't contain a relay address.")
        val key = params["key"]?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && (key.length > MAX_KEY_LENGTH || key.any { it.isISOControl() || it.isWhitespace() })) {
            return ConfigureLinkResult.Invalid("The access key in the setup link is not valid.")
        }
        return when (val check = RelayUrls.check(relay)) {
            is RelayUrlCheck.Ok -> ConfigureLinkResult.Valid(ConfigureRequest(check.normalized, key, insecure = false))
            is RelayUrlCheck.Insecure -> ConfigureLinkResult.Valid(ConfigureRequest(check.normalized, key, insecure = true))
            is RelayUrlCheck.Invalid -> ConfigureLinkResult.Invalid("The relay address in the setup link is not valid: ${check.message}")
            RelayUrlCheck.Empty -> ConfigureLinkResult.Invalid("The setup link doesn't contain a relay address.")
        }
    }

    /**
     * Settings after accepting [request]: relay on, URL and key from the link. A link without a key
     * keeps the stored key only when it points at the relay already configured (the key may have
     * been entered by hand); for another relay the old key is meaningless and is cleared. The user
     * chose this relay, so it is no longer the build's default one.
     */
    fun applyTo(settings: BridgeSettings, request: ConfigureRequest): BridgeSettings {
        val sameRelay = RelayUrls.normalize(settings.relayUrl).equals(request.relayUrl, ignoreCase = true)
        val key = request.accessKey ?: if (sameRelay) settings.relayAccessKey else ""
        return settings.copy(relayEnabled = true, relayUrl = request.relayUrl, relayAccessKey = key, relayFromDefault = false)
    }

    /** True if accepting [request] would change nothing. */
    fun isAlreadyApplied(settings: BridgeSettings, request: ConfigureRequest): Boolean = applyTo(settings, request) == settings

    internal fun hostOf(url: String): String? = try {
        val uri = URI(url)
        uri.host?.let { if (uri.port != -1) "$it:${uri.port}" else it }
    } catch (e: URISyntaxException) {
        null
    }

    private fun parseUri(uri: String): URI? = try {
        URI(uri.trim())
    } catch (e: URISyntaxException) {
        null
    }

    /**
     * First value of each parameter, percent-decoded. A literal '+' stays a '+' (it is common in
     * base64 keys), unlike form decoding.
     */
    private fun queryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val name = decode(pair.substringBefore('='))
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
            if (name != null && value != null && name !in out) out[name] = value
        }
        return out
    }

    private fun decode(text: String): String? = try {
        URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }
}
