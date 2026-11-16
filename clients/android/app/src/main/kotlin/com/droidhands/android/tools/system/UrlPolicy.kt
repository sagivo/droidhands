package com.droidhands.android.tools.system

import com.droidhands.core.tools.ToolException
import java.util.Locale

/**
 * Validation for `url_open` (pure). Web links and app deep links are allowed; schemes that can
 * smuggle arbitrary intents, read local files/providers or run script are rejected.
 */
object UrlPolicy {
    /**
     * `intent:` and `android-app:` are parsed into arbitrary intents by browsers; `file:`/`content:`
     * expose local data; `javascript:`/`data:` run or render attacker-chosen content.
     */
    val BLOCKED_SCHEMES: Set<String> = setOf("intent", "android-app", "file", "content", "javascript", "data")

    const val MAX_LENGTH = 8_192

    data class CheckedUrl(
        /** The URL to open: scheme lowercased, `https://` added to bare host names. */
        val url: String,
        /** Lowercase scheme, e.g. "https", "geo", "whatsapp". */
        val scheme: String,
    )

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    /** "example.com", "www.example.com/path?q=1", "localhost:8080". */
    private val BARE_HOST = Regex("^(localhost|[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})(:\\d{1,5})?([/?#].*)?$")

    fun check(raw: String?): CheckedUrl {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) throw ToolException("Argument 'url' is required, e.g. https://example.com or geo:0,0?q=coffee.")
        if (url.length > MAX_LENGTH) throw ToolException("URL is too long (max $MAX_LENGTH characters).")
        if (url.any { Character.isISOControl(it) }) throw ToolException("URL must not contain control characters (tabs, newlines).")

        // Checked first so "example.com:8080/x" is read as host:port rather than as scheme "example.com".
        if (BARE_HOST.matches(url)) return checked("https://$url", "https")
        val schemeMatch = SCHEME.find(url) ?: throw ToolException(
            "URL '${truncate(url, 100)}' has no scheme. Use a full link such as https://example.com, geo:..., mailto:... or an app deep link.",
        )
        val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
        return checked(scheme + url.substring(schemeMatch.value.length - 1), scheme)
    }

    private fun checked(url: String, scheme: String): CheckedUrl {
        if (scheme in BLOCKED_SCHEMES) {
            throw ToolException(
                "'$scheme:' URLs are blocked for safety. Use an http(s) link or an app deep link " +
                    "(geo:, mailto:, tel:, market:, whatsapp:, …).",
            )
        }
        if (scheme == "http" || scheme == "https") {
            val rest = url.substring(scheme.length + 1)
            val host = rest.removePrefix("//").takeIf { rest.startsWith("//") }
                ?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')
                ?.substringAfterLast('@')?.substringBefore(':')
            if (host.isNullOrEmpty()) throw ToolException("URL '${truncate(url, 100)}' has no host. Use a link such as https://example.com/page.")
        }
        return CheckedUrl(url, scheme)
    }
}
