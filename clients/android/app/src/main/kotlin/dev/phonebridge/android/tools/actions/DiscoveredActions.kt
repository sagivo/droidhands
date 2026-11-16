package dev.phonebridge.android.tools.actions

import dev.phonebridge.core.plugins.ExtraSpec
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.ParamSpec
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.SensitiveWords
import dev.phonebridge.core.plugins.Template
import dev.phonebridge.core.plugins.TemplateException
import dev.phonebridge.core.plugins.Templates
import dev.phonebridge.core.plugins.ValueSpec
import dev.phonebridge.core.tools.Risk
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** An exported activity that handles a standard intent; [label] is the activity's own label, if any. */
data class IntentHandler(val className: String, val label: String? = null)

/** The first handler, per kind, of the standard intents one app handles (docs/PLUGINS.md §8). */
data class StandardHandlers(
    /** ACTION_SEND with text/plain. */
    val share: IntentHandler? = null,
    /** ACTION_PROCESS_TEXT with text/plain ("Translate" in the text selection menu). */
    val processText: IntentHandler? = null,
    /** MEDIA_PLAY_FROM_SEARCH. */
    val playFromSearch: IntentHandler? = null,
    /** ACTION_WEB_SEARCH. */
    val webSearch: IntentHandler? = null,
    /** ACTION_VIEW of a `geo:` link. */
    val geo: IntentHandler? = null,
    /** ACTION_SEARCH: the app's own search screen. */
    val search: IntentHandler? = null,
)

/**
 * Builds the discovered actions of one app (docs/PLUGINS.md §8) from what it declares (pure): `open`, the
 * standard-intent actions, App Actions capabilities and static launcher shortcuts, in that order, each id once.
 *
 * Discovered actions are not validated like plugins, so this keeps only what is safe to run: recipes are
 * intents pinned to the app (the platform pins them again), call actions and targets outside the app are
 * dropped, URLs must have a literal, allowed scheme, and declared values may not contain template braces.
 */
object DiscoveredActions {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    const val ACTION_SEARCH = "android.intent.action.SEARCH"
    const val ACTION_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val EXTRA_PROCESS_TEXT = "android.intent.extra.PROCESS_TEXT"
    const val EXTRA_PROCESS_TEXT_READONLY = "android.intent.extra.PROCESS_TEXT_READONLY"
    const val EXTRA_QUERY = "query"
    const val EXTRA_FOCUS = "android.intent.extra.focus"
    const val TEXT_PLAIN = "text/plain"

    const val SHORTCUT_PREFIX = "shortcut_"

    /** Action ids and parameter names are at most this long (§4, §5). */
    private const val MAX_ID = 40
    private const val QUERY_MAX = 500
    private const val TEXT_MAX = 5_000

    /** Words of capability names that mean messaging, calling, ordering, booking or paying (§8: risk sensitive). */
    private val SENSITIVE_CAPABILITY_WORDS = setOf(
        "message", "messages", "call", "calls", "order", "orders", "reservation", "reservations", "reserve", "book",
        "booking", "ride", "taxi", "pay", "payment", "payments", "money", "transfer", "transaction", "purchase",
        "buy", "checkout", "donation", "send", "post", "delete",
    )

    /**
     * Every discovered action of the app [packageName] (labelled [appLabel]): `open` when it has a
     * [launcherClass], then [handlers]' actions, then the actions of its manifest's intent filters ([manifest],
     * see [DeclaredActions]), then [declared]'s capabilities and shortcuts whose target activity [isExported] says
     * other apps may start.
     */
    fun build(
        appLabel: String,
        packageName: String,
        launcherClass: String?,
        handlers: StandardHandlers,
        declared: ShortcutsDeclaration?,
        manifest: DeclaredManifest? = null,
        isExported: (className: String) -> Boolean,
    ): List<PluginAction> {
        val out = LinkedHashMap<String, PluginAction>()
        fun add(action: PluginAction?) {
            if (action != null && PluginRules.ACTION_ID.matches(action.id)) out.putIfAbsent(action.id, action)
        }
        launcherClass?.let { add(open(appLabel, it)) }
        handlers.share?.let { add(shareText(appLabel, it)) }
        handlers.processText?.let { add(processText(appLabel, it)) }
        handlers.playFromSearch?.let { add(playFromSearch(appLabel, it)) }
        handlers.webSearch?.let { add(webSearch(appLabel, it)) }
        handlers.geo?.let { add(showOnMap(appLabel, it)) }
        handlers.search?.let { add(searchInApp(appLabel, it)) }
        manifest?.let { m -> DeclaredActions.build(appLabel, packageName, m, isExported).forEach(::add) }
        declared?.capabilities?.forEach { add(capability(appLabel, packageName, it, isExported)) }
        declared?.shortcuts?.forEach { add(shortcut(appLabel, packageName, it, isExported)) }
        return out.values.toList()
    }

    fun open(appLabel: String, launcherClass: String) = PluginAction(
        id = "open",
        title = "Open the app",
        description = "Opens $appLabel on its main screen, like tapping its icon on the home screen.",
        keywords = listOf("open", "launch", "start"),
        risk = Risk.ACTION,
        completes = true,
        android = IntentRecipe(action = ACTION_MAIN, categories = listOf(CATEGORY_LAUNCHER), className = launcherClass),
    )

    fun shareText(appLabel: String, handler: IntentHandler) = PluginAction(
        id = "share_text",
        title = "Share text",
        description = "Opens $appLabel's share screen with the text filled in. Nothing is posted or sent until someone " +
            "picks where it goes and taps send there.",
        keywords = listOf("share", "text", "send to", "post"),
        risk = Risk.ACTION,
        params = listOf(ParamSpec("text", "The text to share", required = true, maxLength = TEXT_MAX)),
        completes = false,
        android = IntentRecipe(
            action = ACTION_SEND,
            type = TEXT_PLAIN,
            className = handler.className,
            extras = linkedMapOf(EXTRA_TEXT to template("text")),
        ),
    )

    fun processText(appLabel: String, handler: IntentHandler): PluginAction {
        val name = handler.label?.takeIf { it.isNotBlank() && !it.equals(appLabel, ignoreCase = true) }
        return PluginAction(
            id = "process_text",
            title = name?.let { "$it text" }?.take(PluginRules.MAX_TITLE) ?: "Process text",
            description = "Hands the text to $appLabel's text action" + (name?.let { " \"$it\"" } ?: "") +
                " (the one Android offers in the text selection menu) and opens its result.",
            keywords = listOfNotNull("text", "selection", name?.lowercase(Locale.ROOT)),
            risk = Risk.ACTION,
            params = listOf(ParamSpec("text", "The text to process", required = true, maxLength = TEXT_MAX)),
            completes = false,
            android = IntentRecipe(
                action = ACTION_PROCESS_TEXT,
                type = TEXT_PLAIN,
                className = handler.className,
                extras = linkedMapOf(
                    EXTRA_PROCESS_TEXT to template("text"),
                    EXTRA_PROCESS_TEXT_READONLY to ExtraSpec(ValueSpec.Literal(JsonPrimitive(true))),
                ),
            ),
        )
    }

    fun playFromSearch(appLabel: String, handler: IntentHandler) = PluginAction(
        id = "play_from_search",
        title = "Play music",
        description = "Asks $appLabel to find and play what the query names (a song, artist, album, playlist or podcast). " +
            "Most media apps start playing the best match at once; some only show results.",
        keywords = listOf("play", "music", "song", "listen", "podcast", "playlist"),
        risk = Risk.ACTION,
        params = listOf(ParamSpec("query", "What to play, e.g. \"Bohemian Rhapsody\"", required = true, maxLength = QUERY_MAX)),
        completes = true,
        android = IntentRecipe(
            action = ACTION_PLAY_FROM_SEARCH,
            className = handler.className,
            extras = linkedMapOf(
                EXTRA_QUERY to template("query"),
                EXTRA_FOCUS to ExtraSpec(ValueSpec.Literal(JsonPrimitive("vnd.android.cursor.item/*"))),
            ),
        ),
    )

    fun webSearch(appLabel: String, handler: IntentHandler) = PluginAction(
        id = "web_search",
        title = "Search",
        description = "Opens $appLabel's search results for the query.",
        keywords = listOf("search", "find", "look up", "web"),
        risk = Risk.ACTION,
        params = listOf(ParamSpec("query", "What to search for", required = true, maxLength = QUERY_MAX)),
        completes = false,
        android = IntentRecipe(action = ACTION_WEB_SEARCH, className = handler.className, extras = linkedMapOf(EXTRA_QUERY to template("query"))),
    )

    fun searchInApp(appLabel: String, handler: IntentHandler) = PluginAction(
        id = "search",
        title = "Search in the app",
        description = "Opens $appLabel's own search with the query and shows its results (the app's search screen, " +
            "not the web).",
        keywords = listOf("search", "find", "look up", "look for"),
        risk = Risk.ACTION,
        params = listOf(ParamSpec("query", "What to search for", required = true, maxLength = QUERY_MAX)),
        completes = false,
        android = IntentRecipe(action = ACTION_SEARCH, className = handler.className, extras = linkedMapOf(EXTRA_QUERY to template("query"))),
    )

    fun showOnMap(appLabel: String, handler: IntentHandler) = PluginAction(
        id = "show_on_map",
        title = "Show on map",
        description = "Shows a place or address in $appLabel (a geo: link). It does not start navigation.",
        keywords = listOf("map", "maps", "place", "address", "location", "where"),
        risk = Risk.ACTION,
        params = listOf(ParamSpec("query", "A place name or address, e.g. \"Eiffel Tower\"", required = true, maxLength = QUERY_MAX)),
        completes = false,
        android = IntentRecipe(action = ACTION_VIEW, data = "geo:0,0?q={{query}}", className = handler.className),
    )

    /**
     * `shortcut_<id>` for a static shortcut that is enabled and starts an exported activity of the app with a
     * literal, safe intent; null otherwise. Risk `action`, or `sensitive` when its id or label says it sends,
     * pays, orders, calls or deletes (opening such a screen is still worth a look by the user).
     */
    fun shortcut(appLabel: String, packageName: String, shortcut: DeclaredShortcut, isExported: (String) -> Boolean): PluginAction? {
        if (!shortcut.enabled) return null
        val intent = shortcut.intent ?: return null
        val recipe = targetRecipe(packageName, intent, isExported, requireClass = true) ?: return null
        val suffix = sanitizeId(shortcut.id) ?: return null
        val id = (SHORTCUT_PREFIX + suffix).take(MAX_ID).trimEnd('_')
        val label = (shortcut.shortLabel ?: shortcut.longLabel)?.let(::oneLine) ?: humanize(suffix)
        val longer = shortcut.longLabel?.let(::oneLine)?.takeIf { !it.equals(label, ignoreCase = true) }
        val sensitive = SensitiveWords.find("$suffix $label ${longer.orEmpty()}") != null
        return PluginAction(
            id = id,
            title = label.take(PluginRules.MAX_TITLE),
            description = ("Opens $appLabel's \"$label\" shortcut (a launcher shortcut the app declares)." +
                (longer?.let { " The app describes it as \"$it\"." } ?: "")).take(PluginRules.MAX_ACTION_DESCRIPTION),
            keywords = listOf("shortcut") + words(label),
            risk = if (sensitive) Risk.SENSITIVE else Risk.ACTION,
            completes = false,
            android = recipe,
        )
    }

    /**
     * The action of an App Actions capability (`actions.intent.GET_THING` → `get_thing`), from its first intent
     * that makes a safe recipe: a URL template (RFC 6570 `{var}` and `{?a,b}` forms) or an explicit activity of
     * the app that receives the parameters as extras. Null if none does.
     */
    fun capability(appLabel: String, packageName: String, capability: DeclaredCapability, isExported: (String) -> Boolean): PluginAction? {
        val segment = capability.name.substringAfterLast('.')
        val id = sanitizeId(segment)?.take(MAX_ID)?.trimEnd('_') ?: return null
        for (intent in capability.intents) {
            val params = capabilityParams(intent)
            val names = params.associate { (key, spec) -> key to spec.name }
            val recipe = capabilityRecipe(packageName, intent, names, isExported) ?: continue
            val title = humanize(id)
            val known = params.joinToString(", ") { (key, spec) -> spec.name + (if (spec.name != key) " ($key)" else "") }
            return PluginAction(
                id = id,
                title = title.take(PluginRules.MAX_TITLE),
                description = ("Runs $appLabel's \"$title\" App Action (${capability.name}), as Google Assistant would." +
                    (if (known.isNotEmpty()) " Parameters: $known." else "")).take(PluginRules.MAX_ACTION_DESCRIPTION),
                keywords = words(title),
                risk = if (isSensitiveCapability(capability.name)) Risk.SENSITIVE else Risk.ACTION,
                params = params.map { it.second },
                completes = false,
                android = recipe,
            )
        }
        return null
    }

    /** True for capabilities that message, call, order, book or pay (or otherwise act for the user, by name). */
    fun isSensitiveCapability(name: String): Boolean {
        val words = name.lowercase(Locale.ROOT).split(NON_ALNUM).filter { it.isNotEmpty() }
        return words.any { it in SENSITIVE_CAPABILITY_WORDS } || SensitiveWords.find(words.joinToString(" ")) != null
    }

    /** Parameters of a capability intent, keyed by the app's key (de-duplicated, at most [PluginRules.MAX_PARAMS]). */
    private fun capabilityParams(intent: DeclaredIntent): List<Pair<String, ParamSpec>> {
        val out = ArrayList<Pair<String, ParamSpec>>()
        val keys = intent.parameters.map { it.key }.toMutableList()
        // URL template variables the capability did not declare as parameters still need a value.
        intent.urlTemplate?.let { t -> templateVariables(t).filter { it !in keys }.forEach(keys::add) }
        val taken = HashSet<String>()
        for (key in keys.distinct()) {
            if (out.size >= PluginRules.MAX_PARAMS) break
            val base = sanitizeId(key)?.take(MAX_ID - 2) ?: continue
            val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else "${base}_$n" }.first { it !in taken }
            taken += name
            val declared = intent.parameters.firstOrNull { it.key == key }
            val what = declared?.name?.let { "the $it value" } ?: "the '$key' value"
            out += key to ParamSpec(name, "Value for $what of this App Action", required = declared?.required == true, maxLength = QUERY_MAX)
        }
        return out
    }

    private fun capabilityRecipe(packageName: String, intent: DeclaredIntent, names: Map<String, String>, isExported: (String) -> Boolean): IntentRecipe? {
        val template = intent.urlTemplate
        if (template != null) {
            val data = urlTemplate(template) { names[it] } ?: return null
            if (!safeUrlTemplate(data)) return null
            val base = targetRecipe(packageName, intent.copy(data = null, urlTemplate = null), isExported, requireClass = false, defaultAction = ACTION_VIEW)
                ?: return null
            return base.copy(data = data)
        }
        val base = targetRecipe(packageName, intent, isExported, requireClass = true, defaultAction = ACTION_VIEW) ?: return null
        val extras = LinkedHashMap(base.extras)
        for ((key, name) in names) if (literal(key)) extras.putIfAbsent(key, template(name))
        return base.copy(extras = extras)
    }

    /**
     * A recipe for [intent] pinned to [packageName]: no call actions, no other app's targets, an exported
     * target class (required when [requireClass]), and a literal, safe data URL and extras. Null when unsafe.
     */
    private fun targetRecipe(
        packageName: String,
        intent: DeclaredIntent,
        isExported: (String) -> Boolean,
        requireClass: Boolean,
        defaultAction: String? = null,
    ): IntentRecipe? {
        val action = intent.action ?: defaultAction ?: return null
        if (!literal(action) || PluginRules.isCallAction(action)) return null
        if (intent.targetPackage != null && intent.targetPackage != packageName) return null
        val className = intent.targetClass?.let { if (it.startsWith(".")) packageName + it else it }
        if (className == null && requireClass) return null
        if (className != null && (!literal(className) || !isExported(className))) return null
        val data = intent.data
        if (data != null && !safeUrlTemplate(data)) return null
        if (intent.mimeType != null && !literal(intent.mimeType)) return null
        if (intent.categories.any { !literal(it) }) return null
        val extras = LinkedHashMap<String, ExtraSpec>()
        for ((key, value) in intent.extras) {
            if (!literal(key) || !literal(value)) return null
            extras[key] = ExtraSpec(ValueSpec.Template(value))
        }
        return IntentRecipe(
            action = action,
            type = intent.mimeType,
            data = data,
            categories = intent.categories,
            className = className,
            extras = extras,
        )
    }

    /**
     * Converts an RFC 6570 URL template to a PhoneBridge template: `{var}` → `{{param}}` and `{?a,b}` (or
     * `{&a,b}`) → `?a={{a}}&b={{b}}`, with [paramFor] naming each variable's parameter. Null for any other
     * form (`{+var}`, `{#var}`, `{/var}`, ...) or an unknown variable.
     */
    fun urlTemplate(template: String, paramFor: (String) -> String?): String? {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            when (val c = template[i]) {
                '{' -> {
                    val end = template.indexOf('}', i)
                    if (end < 0) return null
                    val expression = template.substring(i + 1, end)
                    val op = expression.firstOrNull() ?: return null
                    if (op == '?' || op == '&') {
                        val names = expression.substring(1).split(',').map { it.trim() }
                        if (names.isEmpty() || names.any { !VARIABLE.matches(it) }) return null
                        names.forEachIndexed { k, name ->
                            val param = paramFor(name) ?: return null
                            out.append(if (k == 0) op else '&').append(name).append("={{").append(param).append("}}")
                        }
                    } else {
                        if (!VARIABLE.matches(expression)) return null
                        out.append("{{").append(paramFor(expression) ?: return null).append("}}")
                    }
                    i = end + 1
                }
                '}' -> return null
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The variable names of an RFC 6570 template's `{var}` and `{?a,b}` / `{&a,b}` expressions. */
    fun templateVariables(template: String): List<String> =
        EXPRESSION.findAll(template).flatMap { m ->
            m.groupValues[1].trimStart('?', '&').split(',').map { it.trim() }.filter { VARIABLE.matches(it) }
        }.distinct().toList()

    /**
     * `newMessage` / `com.app.NEW-message` → `new_message`: lowercase letters, digits and single underscores,
     * starting with a letter, at most 40 characters. Null if nothing is left.
     */
    fun sanitizeId(raw: String): String? {
        val snake = raw.trim().replace(CAMEL, "$1_$2").lowercase(Locale.ROOT).replace(NON_ALNUM, "_").trim('_')
        val start = snake.indexOfFirst { it in 'a'..'z' }
        if (start < 0) return null
        return snake.substring(start).take(MAX_ID).trimEnd('_').takeIf { PluginRules.ACTION_ID.matches(it) }
    }

    /** `get_thing` → `Get thing`. */
    fun humanize(id: String): String = id.replace('_', ' ').trim().replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun template(param: String) = ExtraSpec(ValueSpec.Template("{{$param}}"))

    /** A URL-context template that renders: it parses, its scheme is literal and allowed. */
    private fun safeUrlTemplate(text: String): Boolean = try {
        Template.parse(text)
        Templates.urlTemplateProblem(text) == null
    } catch (e: TemplateException) {
        false
    }

    /** Declared values are used literally, so they may not look like templates. */
    private fun literal(text: String): Boolean = !text.contains("{{") && !text.contains("}}")

    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(NON_ALNUM).filter { it.length >= 3 }.distinct().take(PluginRules.MAX_KEYWORDS - 1)

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val VARIABLE = Regex("^[A-Za-z0-9_][A-Za-z0-9_.]*$")
    private val EXPRESSION = Regex("\\{([^{}]*)\\}")
}
