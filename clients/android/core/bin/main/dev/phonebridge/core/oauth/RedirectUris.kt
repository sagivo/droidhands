package dev.phonebridge.core.oauth

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Redirect URI rules for dynamically registered public clients, the same as the relay's
 * (relay/src/oauth/redirectUris.ts, docs/PROTOCOL.md §5.2). Pure.
 *
 * Accepted:
 * * `https://<host>/…`
 * * loopback `http://localhost|127.0.0.1|[::1][:<port>]/…` (native apps, RFC 8252 §7.3). The port is
 *   ignored when matching an authorization request against the registration ([matches]).
 * * private-use schemes, e.g. `com.example.app:/cb` (RFC 8252 §7.1) or `cursor://anysphere.cursor-mcp/cb`
 *
 * Never: fragments, credentials, other `http://` hosts, or schemes that execute, read local data or
 * hand off to other apps/protocols ([DENIED_SCHEMES]: `javascript:`, `data:`, `file:`, `intent:`, …).
 */
object RedirectUris {
    /** Longest accepted redirect URI. */
    const val MAX_LENGTH = 512

    enum class Kind { HTTPS, LOOPBACK, PRIVATE_USE }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    /** Schemes that could run script, read local data or reach other handlers/protocols. */
    private val DENIED_SCHEMES = setOf(
        "javascript", "vbscript", "data", "file", "blob", "about", "filesystem", "view-source", "jar",
        "intent", "android-app", "content", "chrome", "chrome-extension", "moz-extension", "resource",
        "ws", "wss", "ftp", "ftps", "sftp", "ssh", "telnet", "smb", "mailto", "tel", "sms", "mms",
        "geo", "market", "itms", "itms-apps", "ms-settings",
    )

    /** RFC 3986 scheme syntax (lowercased). */
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*$")

    /** Whitespace and control characters, which URL parsers silently strip or encode. */
    private val UNSAFE_CHARS = Regex("[\\s\\x00-\\x1f\\x7f]")

    /** Why [uri] is not an acceptable redirect URI (phrased for an `error_description`), or null. */
    fun problem(uri: String): String? = classify(uri).second

    /** The kind of [uri], or null when it is not acceptable. */
    fun kind(uri: String): Kind? = classify(uri).first

    private fun classify(uri: String): Pair<Kind?, String?> {
        fun fail(problem: String): Pair<Kind?, String?> = null to problem
        if (uri.isEmpty() || uri.length > MAX_LENGTH) return fail("redirect URIs must be 1-$MAX_LENGTH characters")
        if (UNSAFE_CHARS.containsMatchIn(uri)) return fail("redirect URIs must not contain whitespace or control characters")
        if ('#' in uri) return fail("redirect URIs must not contain a fragment")
        val parsed = parse(uri) ?: return fail("not an absolute URI: $uri")
        if (parsed.rawUserInfo != null) return fail("redirect URIs must not contain credentials")
        return when (val scheme = parsed.scheme.lowercase()) {
            "https" -> if (parsed.host.isNullOrEmpty()) fail("https redirect URIs need a host") else Kind.HTTPS to null
            "http" -> if (parsed.host?.lowercase() in LOOPBACK_HOSTS) {
                Kind.LOOPBACK to null
            } else {
                fail("http redirect URIs are only allowed for localhost, 127.0.0.1 or [::1]; use https")
            }
            else -> if (!SCHEME.matches(scheme) || scheme in DENIED_SCHEMES) {
                fail("custom-scheme redirect URIs must use the app's own scheme, such as com.example.app:/callback")
            } else {
                Kind.PRIVATE_USE to null
            }
        }
    }

    /**
     * True when [requested] may be used by a client that registered [registered]: an exact match, or
     * for loopback URIs the same host, path and query on any port (or none). Native apps bind a free
     * port per run, so RFC 8252 §7.3 requires ignoring it.
     */
    fun matches(registered: List<String>, requested: String): Boolean {
        if (requested in registered) return true
        val wanted = loopbackWithoutPort(requested) ?: return false
        return registered.any { loopbackWithoutPort(it) == wanted }
    }

    /**
     * What the phone shows as "returns to": the host for https, host:port for loopback, the scheme
     * for private-use URIs. [uri] must be a valid redirect URI.
     */
    fun displayHost(uri: String): String {
        val parsed = parse(uri) ?: return ""
        return when (kind(uri)) {
            Kind.HTTPS -> parsed.host.lowercase()
            Kind.LOOPBACK -> parsed.host.lowercase() + if (parsed.port >= 0) ":${parsed.port}" else ""
            else -> parsed.scheme.lowercase()
        }
    }

    /**
     * Appends authorization-response parameters to [redirectUri], keeping its own query (RFC 6749
     * §3.1.2). Parameters with a null value are omitted. [redirectUri] has no fragment (see [problem]).
     */
    fun withParams(redirectUri: String, params: List<Pair<String, String?>>): String {
        val out = StringBuilder(redirectUri)
        var separator = if ('?' in redirectUri) (if (redirectUri.endsWith('?') || redirectUri.endsWith('&')) "" else "&") else "?"
        for ((name, value) in params) {
            if (value == null) continue
            out.append(separator).append(encode(name)).append('=').append(encode(value))
            separator = "&"
        }
        return out.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** A valid loopback redirect URI with its port removed, or null for any other URI. */
    private fun loopbackWithoutPort(uri: String): String? {
        if (kind(uri) != Kind.LOOPBACK) return null
        val parsed = parse(uri) ?: return null
        val path = parsed.rawPath?.ifEmpty { "/" } ?: "/"
        return "http://${parsed.host.lowercase()}$path" + (parsed.rawQuery?.let { "?$it" } ?: "")
    }

    private fun parse(uri: String): URI? = try {
        URI(uri).takeIf { it.isAbsolute }
    } catch (e: URISyntaxException) {
        null
    }
}
