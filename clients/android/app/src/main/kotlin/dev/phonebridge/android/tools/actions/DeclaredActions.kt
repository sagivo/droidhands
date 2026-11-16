package dev.phonebridge.android.tools.actions

import dev.phonebridge.core.plugins.ExtraSpec
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.ParamSpec
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.ToolRecipe
import dev.phonebridge.core.plugins.ValueSpec
import dev.phonebridge.core.tools.Risk
import java.util.Locale

/**
 * Actions built from the intent filters an app's own manifest declares ([ManifestXml]), so that what an app can do is
 * read from the installed version on this phone, not written down per app (pure):
 *
 * * `open_link`: every link the app says it opens (`ACTION_VIEW` filters: schemes, hosts, paths), listed in the
 *   description so the agent can build one; run through `url_open` pinned to the app, so Android itself refuses links
 *   the installed version doesn't handle.
 * * `compose_<scheme>`: `ACTION_SENDTO` filters (`mailto:`, `smsto:`, …), which open a pre-filled compose screen.
 *
 * Only activities Android says other apps may start ([startable]: exported, enabled, no permission) count. Uses only
 * platform contracts (intent actions and their standard extras), nothing app-specific.
 */
object DeclaredActions {
    const val OPEN_LINK = "open_link"
    const val COMPOSE_PREFIX = "compose_"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
    const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"

    /** The de-facto SMS body extra that SMS apps read besides EXTRA_TEXT. */
    const val EXTRA_SMS_BODY = "sms_body"

    /** At most this many link forms are listed in `open_link`'s description. */
    const val MAX_PATTERNS = 14

    private const val URL_MAX = 4_000
    private const val TEXT_MAX = 10_000

    /** Schemes no action may open (same list as URL templates, docs/PLUGINS.md §6.1). */
    private val FORBIDDEN = setOf("file", "content", "javascript", "data", "intent", "android-app", "about", "blob", "vbscript")
    private val WEB = setOf("http", "https")
    private val SMS = setOf("sms", "smsto", "mms", "mmsto")

    fun build(appLabel: String, packageName: String, manifest: DeclaredManifest, startable: (className: String) -> Boolean): List<PluginAction> {
        val activities = manifest.activities.filter { it.enabled && it.permission == null && it.effectivelyExported && startable(it.className) }
        return listOfNotNull(openLink(appLabel, packageName, activities)) + compose(appLabel, activities)
    }

    /** The link forms [activities] open, as `scheme://host/path` with `*` for "anything", most specific first. */
    fun linkPatterns(activities: List<DeclaredActivity>): LinkForms {
        val out = LinkedHashSet<String>()
        var anyWeb = false
        for (filter in activities.flatMap { it.filters }) {
            if (DiscoveredActions.ACTION_VIEW !in filter.actions) continue
            if (CATEGORY_BROWSABLE !in filter.categories && CATEGORY_DEFAULT !in filter.categories) continue
            for (scheme in filter.schemes) {
                if (scheme in FORBIDDEN || !SCHEME.matches(scheme)) continue
                if (filter.hosts.isEmpty()) {
                    if (scheme in WEB) anyWeb = true else out += "$scheme:*"
                    continue
                }
                for (host in filter.hosts) {
                    val paths = filter.paths.ifEmpty { listOf("*") }
                    for (path in paths) out += "$scheme://$host" + displayPath(path)
                }
            }
        }
        return LinkForms(out.toList(), anyWeb)
    }

    /** What [linkPatterns] found: the forms, and whether the app opens any web link (a browser). */
    data class LinkForms(val patterns: List<String>, val anyWebLink: Boolean) {
        val isEmpty: Boolean get() = patterns.isEmpty() && !anyWebLink
    }

    fun openLink(appLabel: String, packageName: String, activities: List<DeclaredActivity>): PluginAction? {
        val forms = linkPatterns(activities)
        if (forms.isEmpty) return null
        val shown = forms.patterns.take(MAX_PATTERNS)
        val list = buildString {
            if (forms.anyWebLink) append("any http(s) web link")
            if (shown.isNotEmpty()) {
                if (forms.anyWebLink) append(", and ")
                append(shown.joinToString(", "))
            }
            if (forms.patterns.size > shown.size) append(" and ${forms.patterns.size - shown.size} more")
        }
        val description = ("Opens a link inside $appLabel. The installed version declares that it opens: $list " +
            "(* = anything). Build a link of one of these forms from what you know about $appLabel's links; Android " +
            "refuses links this version doesn't handle.").take(PluginRules.MAX_ACTION_DESCRIPTION)
        val example = shown.firstOrNull()?.replace("*", "…") ?: "https://example.com"
        return PluginAction(
            id = OPEN_LINK,
            title = "Open a link",
            description = description,
            keywords = (listOf("link", "url", "deep link", "open", "go to") + hostWords(forms.patterns)).distinct().take(PluginRules.MAX_KEYWORDS),
            params = listOf(ParamSpec("url", "The link to open, e.g. $example".take(PluginRules.MAX_PARAM_DESCRIPTION), required = true, maxLength = URL_MAX)),
            completes = false,
            android = ToolRecipe(
                name = "url_open",
                args = linkedMapOf("url" to ValueSpec.Template("{{url}}"), "package" to ValueSpec.Template(packageName)),
            ),
        )
    }

    /** One `compose_<scheme>` per `ACTION_SENDTO` scheme, started in the first activity that declares it. */
    fun compose(appLabel: String, activities: List<DeclaredActivity>): List<PluginAction> {
        val byScheme = LinkedHashMap<String, String>()
        for (activity in activities) {
            for (filter in activity.filters) {
                if (ACTION_SENDTO !in filter.actions) continue
                for (scheme in filter.schemes) {
                    if (scheme in FORBIDDEN || scheme in WEB || !SCHEME.matches(scheme)) continue
                    byScheme.putIfAbsent(scheme, activity.className)
                }
            }
        }
        return byScheme.mapNotNull { (scheme, className) -> composeAction(appLabel, scheme, className) }
    }

    private fun composeAction(appLabel: String, scheme: String, className: String): PluginAction? {
        val suffix = DiscoveredActions.sanitizeId(scheme) ?: return null
        val id = (COMPOSE_PREFIX + suffix).take(40).trimEnd('_')
        val email = scheme == "mailto"
        val sms = scheme in SMS
        val to = when {
            email -> ParamSpec("to", "Email address(es), comma-separated, e.g. dana@example.com", required = true, maxLength = 2_000)
            sms -> ParamSpec("to", "Phone number, e.g. +15551234567", required = true, maxLength = 40)
            else -> ParamSpec("to", "The recipient, as $appLabel's $scheme: links name it", required = true, maxLength = 500)
        }
        val params = buildList {
            add(to)
            if (email) add(ParamSpec("subject", "Subject line", maxLength = 500))
            add(ParamSpec("text", "The message text to fill in", maxLength = TEXT_MAX))
        }
        val extras = linkedMapOf(DiscoveredActions.EXTRA_TEXT to template("text"))
        if (email) extras[EXTRA_SUBJECT] = template("subject")
        if (sms) extras[EXTRA_SMS_BODY] = template("text")
        val what = when {
            email -> "an email"
            sms -> "a text message"
            else -> "a message"
        }
        return PluginAction(
            id = id,
            title = (if (email) "Write an email" else "Write ${what.removePrefix("a ")}").replaceFirstChar { it.titlecase(Locale.ROOT) }.take(PluginRules.MAX_TITLE),
            description = ("Opens $appLabel's compose screen for $what to the recipient ($scheme: link), with the text filled " +
                "in. Nothing is sent: finish with the screen tools (screen_observe, then tap Send and check it went out) or " +
                "ask the user to.").take(PluginRules.MAX_ACTION_DESCRIPTION),
            keywords = listOf("write", "compose", "message", "draft", scheme) + if (email) listOf("email", "mail") else if (sms) listOf("sms", "text") else emptyList(),
            risk = Risk.ACTION,
            params = params,
            completes = false,
            android = IntentRecipe(action = ACTION_SENDTO, data = "$scheme:{{to}}", className = className, extras = extras),
        )
    }

    private fun template(param: String) = ExtraSpec(ValueSpec.Template("{{$param}}"))

    /** A declared path for display: `/x` stays, `.*` and other pattern wildcards become `*`. */
    private fun displayPath(path: String): String {
        val shown = path.replace(".*", "*").replace(Regex("\\*+"), "*")
        return when {
            shown == "*" -> "/*"
            shown.startsWith("/") || shown.startsWith("*") -> shown
            else -> "/$shown"
        }
    }

    private fun hostWords(patterns: List<String>): List<String> =
        patterns.mapNotNull { p -> p.substringAfter("://", "").substringBefore('/').substringBefore(':').takeIf { it.isNotEmpty() && !it.startsWith("*") } }
            .distinct()
            .take(6)

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*$")
}
