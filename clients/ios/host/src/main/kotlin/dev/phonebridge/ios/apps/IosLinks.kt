package dev.phonebridge.ios.apps

import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.ios.inventory.IosApp
import dev.phonebridge.ios.inventory.arr
import dev.phonebridge.ios.inventory.strings
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.Locale

/** A link `url_open` may open: [url] normalized (scheme lowercased, `https://` added to bare hosts). */
data class CheckedUrl(val url: String, val scheme: String) {
    /** The host of an http(s) link, lowercased, without port; null for other links. */
    val host: String?
        get() = if (scheme != "http" && scheme != "https") {
            null
        } else {
            runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT)
                ?: url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                    .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
        }
}

/**
 * The links iOS apps declare and the links agents may open (pure; docs/IOS.md "Discovered actions"). An app opens a
 * custom scheme it lists in `CFBundleURLTypes`, and `https` links on the domains of its
 * `com.apple.developer.associated-domains` entitlement (`applinks:open.spotify.com`, `applinks:*.example.com`).
 * Schemes that run script, read local data or smuggle intents are refused everywhere.
 */
object IosLinks {
    /** Never opened (the same list as plugin URL templates, docs/PLUGINS.md §6.1). */
    val FORBIDDEN_SCHEMES: Set<String> = setOf("file", "content", "javascript", "data", "intent", "android-app", "about", "blob", "vbscript")

    const val MAX_LENGTH = 8_192
    const val ASSOCIATED_DOMAINS = "com.apple.developer.associated-domains"

    private val WEB = setOf("http", "https")
    private val SCHEME = Regex("^[a-z][a-z0-9+.\\-]*$")
    private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val BARE_HOST = Regex("^(localhost|[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})(:\\d{1,5})?([/?#].*)?$")
    private val DOMAIN = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /** What an app opens: custom [schemes] and the [domains] of its universal links (`*.` = any subdomain). */
    data class LinkForms(val schemes: List<String>, val domains: List<String>) {
        val isEmpty: Boolean get() = schemes.isEmpty() && domains.isEmpty()

        /** For descriptions: `spotify:` plus a star for each scheme, then `https://open.spotify.com/` plus a star for each domain. */
        val patterns: List<String> get() = schemes.map { "$it:*" } + domains.map { "https://$it/*" }
    }

    fun forms(app: IosApp): LinkForms = LinkForms(urlSchemes(app), appLinkDomains(app))

    /** The custom schemes of `CFBundleURLTypes`, lowercased (iOS compares schemes case-insensitively), in order. */
    fun urlSchemes(app: IosApp): List<String> = app.infoPlist.arr("CFBundleURLTypes")
        .flatMap { (it as? JsonObject)?.strings("CFBundleURLSchemes").orEmpty() }
        .map { it.lowercase(Locale.ROOT) }
        .filter { SCHEME.matches(it) && it !in FORBIDDEN_SCHEMES && it !in WEB }
        .distinct()

    /** The `applinks:` domains of the associated-domains entitlement, lowercased, `?mode=…` dropped. */
    fun appLinkDomains(app: IosApp): List<String> = app.entitlements.strings(ASSOCIATED_DOMAINS)
        .filter { it.startsWith("applinks:", ignoreCase = true) }
        .map { it.substringAfter(':').substringBefore('?').substringBefore(':').trim().lowercase(Locale.ROOT) }
        .filter { DOMAIN.matches(it) }
        .distinct()

    /** True if [host] is [domain], or a subdomain for a `*.` domain (Apple's rule for associated domains). */
    fun hostMatches(host: String, domain: String): Boolean =
        if (domain.startsWith("*.")) host.endsWith(domain.substring(1)) && host.length > domain.length - 1 else host == domain

    /** True if the installed [app] declares that it opens [link]: its scheme, or `https` on one of its domains. */
    fun declares(app: IosApp, link: CheckedUrl): Boolean = declares(forms(app), link)

    fun declares(forms: LinkForms, link: CheckedUrl): Boolean = when (link.scheme) {
        "https" -> link.host?.let { h -> forms.domains.any { hostMatches(h, it) } } == true
        "http" -> false
        else -> link.scheme in forms.schemes
    }

    /** Why [app] would not open [link], for refusals: names what the installed version does declare. */
    fun notDeclared(app: IosApp, link: CheckedUrl): String {
        val forms = forms(app)
        val declared = if (forms.isEmpty) {
            "it declares no links"
        } else {
            "it declares " + forms.patterns.take(8).joinToString(", ") + if (forms.patterns.size > 8) " and ${forms.patterns.size - 8} more" else ""
        }
        val what = when (link.scheme) {
            "http" -> "plain http links (iOS opens only https links in apps)"
            "https" -> "https links on ${link.host ?: "that site"}"
            else -> "'${link.scheme}:' links"
        }
        return "The installed ${app.name}${app.version?.let { " $it" } ?: ""} doesn't open $what: $declared."
    }

    /** Validates an agent's [raw] link. @throws ToolException with what to pass instead. */
    fun check(raw: String?): CheckedUrl {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) throw ToolException("Argument 'url' is required, e.g. https://example.com or maps://?q=coffee.")
        if (url.length > MAX_LENGTH) throw ToolException("URL is too long (max $MAX_LENGTH characters).")
        if (url.any { Character.isISOControl(it) }) throw ToolException("URL must not contain control characters (tabs, newlines).")
        if (BARE_HOST.matches(url)) return web("https://$url", "https")
        val m = SCHEME_PREFIX.find(url) ?: throw ToolException(
            "URL '${url.take(100)}' has no scheme. Use a full link such as https://example.com, mailto:..., or an app's own link (spotify:...).",
        )
        val scheme = m.groupValues[1].lowercase(Locale.ROOT)
        val normalized = scheme + url.substring(m.value.length - 1)
        if (scheme in FORBIDDEN_SCHEMES) {
            throw ToolException("'$scheme:' links are blocked for safety. Use an https link or an app's own link (mailto:, maps:, spotify:, …).")
        }
        return if (scheme in WEB) web(normalized, scheme) else CheckedUrl(normalized, scheme)
    }

    private fun web(url: String, scheme: String): CheckedUrl {
        val checked = CheckedUrl(url, scheme)
        if (checked.host.isNullOrEmpty()) throw ToolException("URL '${url.take(100)}' has no host. Use a link such as https://example.com/page.")
        return checked
    }

    /** The installed apps that declare [link], as iOS would consider them (a browser is never listed: none declares https). */
    fun handlers(apps: List<IosApp>, link: CheckedUrl): List<IosApp> = apps.filter { declares(it, link) }
}
