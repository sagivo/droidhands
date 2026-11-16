package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer
import java.util.Locale

/**
 * A `plugin.yaml` draft for one app, made from what PhoneBridge discovered about it on the phone
 * (docs/PLUGINS.md §8, §10: Apps → (app) → Create plugin draft). Each discovered action (share target,
 * text action, media search, web search, geo links, launcher shortcuts, App Actions capabilities) becomes
 * an `intent` recipe with a `# TODO` comment saying where it came from and what to check, and, where its
 * recipe renders, an example (§7) computed with [RecipeRenderer], so the draft's examples pass as written.
 *
 * The draft is valid as generated (it can be imported as a local plugin right away); the TODO markers are
 * the parts a contributor fills in before opening a pull request: the description, the category, the
 * titles and descriptions of the actions, and the app's own deep links. Pure.
 */
object PluginDraft {
    /** What a draft is made from. */
    data class Input(
        /** The app's name as the launcher shows it, e.g. "Spotify". */
        val label: String,
        val packageName: String,
        /** The app's discovered actions (§8), e.g. from [AppActionsPlatform.discoveredActions]. */
        val discovered: List<PluginAction>,
        /** A §3 category guess; anything that is not a category becomes `other`. */
        val category: String = "other",
        val versionName: String? = null,
        /** The bundled plugin that already covers the app, if any: the draft adds to it instead of repeating it. */
        val existing: AppPlugin? = null,
    )

    /** Discovered action ids (§8) and where they come from, for the TODO comments. */
    private val ORIGINS = mapOf(
        "open" to "the app's launcher screen",
        "share_text" to "the app's share target (ACTION_SEND, text/plain)",
        "process_text" to "the app's text selection action (ACTION_PROCESS_TEXT)",
        "play_from_search" to "the app's media search (MEDIA_PLAY_FROM_SEARCH)",
        "web_search" to "the app's search handler (ACTION_WEB_SEARCH)",
        "show_on_map" to "the app's geo: link handler",
    )

    private const val SHORTCUT_PREFIX = "shortcut_"
    private const val OPEN = "open"

    /** The draft's text. */
    fun yaml(input: Input): String {
        val pkg = input.packageName.trim()
        val label = oneLine(input.label).ifEmpty { pkg }.take(PluginRules.MAX_NAME).trim()
        val existing = input.existing
        val id = existing?.id ?: pluginId(label, pkg)
        val category = input.category.takeIf { it in PluginRules.CATEGORIES } ?: "other"
        val taken = existing?.actions?.mapTo(HashSet()) { it.id }.orEmpty()
        val usable = input.discovered
            .filter { PluginRules.ACTION_ID.matches(it.id) && (it.android is IntentRecipe || it.android is UrlRecipe) && it.id !in taken }
            .distinctBy { it.id }
        val others = usable.filter { it.id != OPEN }
        // `open` is discovered for every app anyway; it is only written out when there is nothing else to start from.
        val candidates = others.ifEmpty { usable }
        val actions = candidates.take(PluginRules.MAX_ACTIONS)
        val left = candidates.drop(PluginRules.MAX_ACTIONS)

        val out = YamlOut()
        out.line("# yaml-language-server: \$schema=../plugin.schema.json")
        out.line("#")
        val version = input.versionName?.let(::oneLine)?.takeIf { it.isNotEmpty() }
        out.line("# Draft plugin for $label ($pkg${version?.let { ", version $it" } ?: ""}), made by PhoneBridge from what the app")
        out.line("# declares on this phone. To finish it (docs/PLUGINS.md, CONTRIBUTING.md):")
        out.line("#   1. Save it as plugins/$id/plugin.yaml and fill in every TODO.")
        out.line("#   2. Keep the actions that are useful, give them clear titles and descriptions, and add the app's own")
        out.line("#      deep links (./scripts/plugin-scaffold.sh $pkg lists the links it opens).")
        out.line("#   3. Run ./scripts/plugin-check.sh plugins/$id, then try it on the phone (Settings > Developer > Local plugins).")
        if (existing != null) {
            out.line("#")
            out.line("# PhoneBridge already has a plugin for this app: plugins/${existing.id}/plugin.yaml. Add the actions you")
            out.line("# want from this draft to that file (its own actions are left out here).")
        }
        if (others.isNotEmpty()) {
            out.line("#")
            out.line("# Agents get \"open\" and every discovered action without a plugin; a plugin makes them clearer and adds more.")
        }
        out.line("id: $id")
        out.line("name: ${scalar(label)}")
        out.line("description: ${quoted("TODO: one sentence about $label.")}")
        out.line("category: $category${" ".repeat((22 - category.length).coerceAtLeast(1))}# TODO: check it (the categories are listed in docs/PLUGINS.md §3)")
        out.line("# homepage: https://...         # TODO: the app's website (optional)")
        out.line("status: experimental")
        out.line("# maintainers: [your-github-handle]")
        if (version != null) {
            out.line("# tested:                       # TODO: once you ran every action on a phone")
            out.line("#   - {app_version: ${quoted(version)}, android: \"14\", date: \"YYYY-MM-DD\", by: your-github-handle}")
        }
        out.line("android:")
        out.line("  packages: [${scalar(pkg)}]")
        out.line("")
        out.line("actions:")
        if (actions.isEmpty()) {
            // Only reachable when not even `open` is known (the app has no launcher screen PhoneBridge could see).
            placeholderAction(out, label)
        }
        actions.forEachIndexed { i, action ->
            if (i > 0) out.line("")
            action(out, action, pkg)
        }
        if (left.isNotEmpty()) {
            out.line("")
            out.line("  # TODO: ${left.size} more discovered ${if (left.size == 1) "action was" else "actions were"} left out (a plugin has at most " +
                "${PluginRules.MAX_ACTIONS}): ${left.joinToString(", ") { it.id }}.")
        }
        out.line("")
        out.line("  # TODO: add the app's own deep links, for example:")
        out.line("  # - id: search")
        out.line("  #   title: Search")
        out.line("  #   description: Opens $label's search results for a query.")
        out.line("  #   risk: action")
        out.line("  #   params:")
        out.line("  #     query: {description: What to search for, required: true, max_length: 200}")
        out.line("  #   android:")
        out.line("  #     url: \"https://example.com/search?q={{query}}\"")
        return out.text()
    }

    /**
     * A plugin id (§3) for the app: its label as lowercase words joined by `_` ("Google Maps" → `google_maps`),
     * or, when the label has no usable letters (e.g. a non-Latin name), the meaningful parts of its package
     * (`org.telegram.messenger` → `telegram_messenger`).
     */
    fun pluginId(label: String, packageName: String): String {
        slug(label)?.let { return it }
        val parts = packageName.lowercase(Locale.ROOT).split('.').filter { it.isNotEmpty() && it !in PACKAGE_NOISE }
        return slug(parts.joinToString("_"))
            ?: slug(packageName.substringAfterLast('.'))
            ?: "myapp"
    }

    private val PACKAGE_NOISE = setOf("com", "org", "net", "io", "co", "de", "me", "www", "android", "app", "apps", "mobile")

    private fun slug(text: String): String? {
        val ascii = Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)
        val snake = ascii.replace(NON_ALNUM, "_").trim('_')
        val start = snake.indexOfFirst { it in 'a'..'z' }
        if (start < 0) return null
        return snake.substring(start).take(40).trimEnd('_').takeIf { PluginRules.PLUGIN_ID.matches(it) }
    }

    private fun placeholderAction(out: YamlOut, label: String) {
        out.line("  # TODO: PhoneBridge found nothing to start from in $label. Replace this with a real action.")
        out.line("  - id: search")
        out.line("    title: Search")
        out.line("    description: ${quoted("TODO: opens $label's search results for a query.")}")
        out.line("    risk: action")
        out.line("    params:")
        out.line("      query: {description: What to search for, required: true, max_length: 200}")
        out.line("    android:")
        out.line("      url: \"https://example.com/search?q={{query}}\"")
    }

    private fun action(out: YamlOut, action: PluginAction, pkg: String) {
        val origin = ORIGINS[action.id] ?: if (action.id.startsWith(SHORTCUT_PREFIX)) {
            "a launcher shortcut in the app's shortcuts.xml"
        } else {
            "an App Actions capability in the app's shortcuts.xml"
        }
        out.line("  # TODO: found from $origin. Check the title and description, and the risk.")
        val recipe = action.android
        val intent = recipe as? IntentRecipe
        val unnamedClass = intent?.className?.takeIf { relativeClass(it, pkg) == null }
        if (unnamedClass != null) {
            out.line("  # The app handles it in $unnamedClass, which a plugin can't name (docs/PLUGINS.md §6.2);")
            out.line("  # Android picks the app's matching screen instead.")
        }
        out.line("  - id: ${action.id}")
        out.line("    title: ${scalar(oneLine(action.title).take(PluginRules.MAX_TITLE))}")
        out.line("    description: ${quoted(oneLine(action.description).take(PluginRules.MAX_ACTION_DESCRIPTION))}")
        val keywords = action.keywords.map(::oneLine).filter { it.isNotEmpty() && it.length <= PluginRules.MAX_KEYWORD }.distinct()
            .take(PluginRules.MAX_KEYWORDS)
        if (keywords.isNotEmpty()) out.line("    keywords: [${keywords.joinToString(", ") { scalar(it) }}]")
        out.line("    risk: ${riskOf(action).name.lowercase(Locale.ROOT)}")
        action.scope?.let { out.line("    scope: $it") }
        val params = action.params.take(PluginRules.MAX_PARAMS)
        if (params.isNotEmpty()) {
            out.line("    params:")
            params.forEach { out.line("      ${it.name}: ${paramSpec(it)}") }
        }
        action.completes?.takeIf { it != PluginAction.completesByDefault(recipe) }?.let { out.line("    completes: $it") }
        out.line("    android:")
        val written = when (recipe) {
            is IntentRecipe -> recipe.copy(className = recipe.className?.let { relativeClass(it, pkg) })
            else -> recipe
        }
        when (written) {
            is IntentRecipe -> intentRecipe(out, written, "      ")
            is UrlRecipe -> out.line("      url: ${quoted(written.url)}")
            else -> Unit
        }
        examples(out, action.copy(params = params, android = written), pkg)
    }

    /** At least the discovered risk, and never below the validator's floors (§9). */
    private fun riskOf(action: PluginAction): Risk {
        val declared = action.risk ?: Risk.SENSITIVE
        val words = listOf(action.id, action.title).any { SensitiveWords.find(it) != null }
        return when {
            declared == Risk.SENSITIVE || words -> Risk.SENSITIVE
            declared == Risk.READ -> Risk.ACTION // intent and url recipes open the app: at least an action
            else -> declared
        }
    }

    /** `.Relative` for a class in [pkg], or null if a plugin can't name it (§6.2: outside the plugin's packages). */
    private fun relativeClass(className: String, pkg: String): String? = when {
        className.startsWith(".") -> className
        className.startsWith("$pkg.") -> className.removePrefix(pkg)
        else -> null
    }

    private fun intentRecipe(out: YamlOut, recipe: IntentRecipe, indent: String) {
        out.line("${indent}intent:")
        val inner = "$indent  "
        out.line("${inner}action: ${scalar(recipe.action)}")
        recipe.type?.let { out.line("${inner}type: ${scalar(it)}") }
        recipe.data?.let { out.line("${inner}data: ${quoted(it)}") }
        if (recipe.categories.isNotEmpty()) out.line("${inner}categories: [${recipe.categories.joinToString(", ") { scalar(it) }}]")
        recipe.className?.let { out.line("${inner}class: ${scalar(it)}") }
        if (recipe.extras.isNotEmpty()) {
            out.line("${inner}extras:")
            for ((key, extra) in recipe.extras) {
                val value = value(extra.value)
                val written = extra.type?.let { "{type: ${it.id}, value: $value}" } ?: value
                out.line("$inner  ${key(key)}: $written")
            }
        }
    }

    private fun value(spec: ValueSpec): String = when (spec) {
        is ValueSpec.Template -> quoted(spec.template)
        is ValueSpec.Literal -> primitive(spec.value)
        is ValueSpec.Items -> "[" + spec.items.joinToString(", ") { value(it) } + "]"
    }

    private fun paramSpec(p: ParamSpec): String {
        val fields = ArrayList<String>()
        if (p.type != ParamType.STRING) fields += "type: ${p.type.id}"
        fields += "description: ${scalar(oneLine(p.description).take(PluginRules.MAX_PARAM_DESCRIPTION))}"
        if (p.required) fields += "required: true"
        p.enum?.takeIf { it.isNotEmpty() }?.let { values -> fields += "enum: [${values.joinToString(", ") { primitive(it) }}]" }
        p.default?.let { fields += "default: ${primitive(it)}" }
        p.minLength?.let { fields += "min_length: $it" }
        p.maxLength?.let { fields += "max_length: ${it.coerceAtMost(ParamSpec.MAX_MAX_LENGTH)}" }
        p.minimum?.let { fields += "minimum: ${number(it)}" }
        p.maximum?.let { fields += "maximum: ${number(it)}" }
        p.pattern?.let { fields += "pattern: ${quoted(it)}" }
        return "{" + fields.joinToString(", ") + "}"
    }

    /**
     * Examples (§7) for [action]: one with sample values for the required parameters, its expectation
     * computed by rendering the recipe (so it passes), and one leaving the first required parameter out.
     */
    private fun examples(out: YamlOut, action: PluginAction, pkg: String) {
        val recipe = action.android ?: return
        val values = LinkedHashMap<String, JsonPrimitive>()
        for (p in action.params.filter { it.required }) values[p.name] = sampleValue(p) ?: return
        val sample = JsonObject(values)
        val rendered = try {
            RecipeRenderer(pkg).render(recipe, ParamValues.validate(action.params, sample))
        } catch (e: Exception) {
            return // A recipe that needs more than sample values: the contributor writes its examples.
        }
        val expect = when (rendered) {
            is RenderedIntent -> intentExpectation(rendered, pkg)
            is RenderedUrl -> "{url: ${quoted(rendered.url)}}"
            else -> return
        }
        val required = action.params.firstOrNull { it.required }
        out.line("    examples:")
        out.line("      - params: {${values.entries.joinToString(", ") { (k, v) -> "$k: ${primitive(v)}" }}}")
        out.line("        expect: $expect")
        if (required != null) {
            out.line("      - params: {}")
            out.line("        expect: {error: ${required.name}}")
        }
    }

    private fun intentExpectation(intent: RenderedIntent, pkg: String): String {
        val fields = ArrayList<String>()
        fields += "action: ${scalar(intent.action)}"
        fields += "package: ${scalar(pkg)}"
        intent.className?.let { c -> relativeClass(c, pkg)?.let { fields += "class: ${scalar(it)}" } }
        intent.data?.let { fields += "data: ${quoted(it)}" }
        intent.type?.let { fields += "type: ${scalar(it)}" }
        if (intent.extras.isNotEmpty()) {
            val extras = intent.extras.entries.joinToString(", ") { (k, v) -> "${key(k)}: ${extraValue(v)}" }
            fields += "extras: {$extras}"
        }
        return "{intent: {" + fields.joinToString(", ") + "}}"
    }

    private fun extraValue(value: ExtraValue): String = when (value) {
        is ExtraValue.StringValue -> quoted(value.value)
        is ExtraValue.IntValue -> value.value.toString()
        is ExtraValue.LongValue -> value.value.toString()
        is ExtraValue.DoubleValue -> number(value.value)
        is ExtraValue.BooleanValue -> value.value.toString()
        is ExtraValue.StringArrayValue -> "[" + value.values.joinToString(", ") { quoted(it) } + "]"
    }

    private fun sampleValue(p: ParamSpec): JsonPrimitive? {
        p.enum?.firstOrNull()?.let { return it }
        return when (p.type) {
            ParamType.BOOLEAN -> JsonPrimitive(true)
            ParamType.INTEGER -> JsonPrimitive(kotlin.math.ceil(p.minimum ?: 1.0).toLong())
            ParamType.NUMBER -> JsonPrimitive(p.minimum ?: 1.0)
            // A pattern needs a value only the contributor knows.
            ParamType.STRING -> if (p.pattern != null) null else JsonPrimitive("hello world".take(p.effectiveMaxLength).padEnd(p.minLength ?: 0, 'x'))
        }
    }

    // ---- YAML text ----

    private class YamlOut {
        private val sb = StringBuilder()

        fun line(text: String) {
            sb.append(text.trimEnd()).append('\n')
        }

        fun text(): String = sb.toString()
    }

    /** A plain scalar when it can't be misread (no YAML indicators, not a number, boolean or null), else [quoted]. */
    fun scalar(text: String): String =
        if (PLAIN.matches(text) && text.lowercase(Locale.ROOT) !in RESERVED && !NUMBERISH.containsMatchIn(text)) text else quoted(text)

    /** Mapping keys: plain when safe, else quoted. */
    private fun key(text: String): String = scalar(text)

    /** A YAML double-quoted string: backslash, quote and control characters escaped. */
    fun quoted(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\t' -> sb.append("\\t")
                c == '\r' -> sb.append("\\r")
                c < ' ' || c == '\u007f' || c == '\u0085' || c == ' ' || c == ' ' || c == '﻿' ->
                    sb.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun primitive(value: JsonPrimitive): String = when {
        value.isString -> quoted(value.content)
        value.booleanOrNull != null -> value.content
        value.longOrNull != null -> value.content
        value.doubleOrNull != null -> number(value.doubleOrNull!!)
        else -> quoted(value.content)
    }

    private fun number(value: Double): String =
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** Words, ids and dotted names; a leading `.` is fine (relative class names). No `:`, `#`, quotes or flow indicators. */
    private val PLAIN = Regex("^[A-Za-z.][A-Za-z0-9 _./+\\-]*[A-Za-z0-9_*]$|^[A-Za-z]$")
    private val RESERVED = setOf("true", "false", "null", "yes", "no", "on", "off", "y", "n", "~")

    /** YAML floats a plain scalar starting with `.` could be read as: `.5`, `.inf`, `.NaN`. */
    private val NUMBERISH = Regex("^\\.([0-9]|inf$|nan$)", RegexOption.IGNORE_CASE)
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
}
