package dev.phonebridge.core.plugins

import dev.phonebridge.core.mcp.CallStoppedException
import dev.phonebridge.core.mcp.ToolText
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Dispatch
import dev.phonebridge.core.tools.DispatchTool
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolNames
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The app-action tools of docs/TOOLS.md, built in core and registered by the platform: `app_actions_list`
 * (what can I do in this app?) and `app_action_run` (do it). See docs/PLUGINS.md.
 */
fun appActionTools(catalog: AppActionCatalog): List<Tool> = listOf(appActionsList(catalog), AppActionRunTool(catalog))

private const val DEFAULT_QUERY_LIMIT = 10
private const val DEFAULT_APP_LIMIT = 50
private const val MAX_LIMIT = 50

private fun appActionsList(catalog: AppActionCatalog): Tool = tool(
    name = ToolNames.APP_ACTIONS_LIST,
    title = "List app actions",
    description = "Lists the ready-made actions you can run with app_action_run, with their parameters as JSON Schema. " +
        "Pass 'app' (the app's name, e.g. Spotify, or its package) for that app's actions, or 'query' (what you want to do, " +
        "e.g. \"play music\" or \"navigate home\") to search the actions of every installed app; both together search " +
        "within the app. apps_list shows each app's action ids. Actions come from what each installed app declares on this " +
        "phone (plus community plugins checked against it), so they differ between phones and app versions. They are " +
        "faster than phone use (screen_observe, ui_tap), which works for anything not listed; an action with " +
        "available=false or a 'health' entry doesn't work on this phone right now, so use phone use for it. Only actions " +
        "this agent may run are listed, and apps the user blocked for this agent never appear.",
    scope = Scopes.APPS,
    risk = Risk.READ,
    schema = objectSchema {
        string("app", "App name as the user says it (e.g. Spotify) or package (e.g. com.spotify.music)")
        string("query", "What you want to do, e.g. \"play a song\" or \"share text\"")
        integer("limit", "Most actions to return (default $DEFAULT_QUERY_LIMIT for a query, max $MAX_LIMIT)", minimum = 1, maximum = MAX_LIMIT.toLong())
    },
) { args, ctx ->
    val appName = args.optString("app")?.trim()?.takeIf { it.isNotEmpty() }
    val query = args.optString("query")?.trim()?.takeIf { it.isNotEmpty() }
    val limit = args.optInt("limit")?.coerceIn(1, MAX_LIMIT)
    when {
        appName != null -> listForApp(catalog, appName, query, limit ?: DEFAULT_APP_LIMIT, ctx)
        query != null -> search(catalog, query, limit ?: DEFAULT_QUERY_LIMIT, ctx)
        else -> throw ToolException(
            "Pass 'app' (e.g. Spotify) to list that app's actions, or 'query' (e.g. \"play music\") to search all apps. " +
                "apps_list shows installed apps and their action ids.",
        )
    }
}

private suspend fun listForApp(catalog: AppActionCatalog, appName: String, query: String?, limit: Int, ctx: CallContext): ToolResult {
    val app = catalog.resolveApp(appName, ctx.appAccess)
    val installed = catalog.installedPackages()
    val all = catalog.actions(app, installed)
    val runner = AppActionCatalog.mayRunActions(ctx)
    val runnable = if (runner) all.filter { catalog.mayRun(it, ctx) } else emptyList()
    val hidden = all.size - runnable.size
    val queryWords = query?.let(ActionSearch::words).orEmpty()
    val ranked = if (queryWords.isEmpty()) runnable else ActionSearch.rank(queryWords, runnable)
    val unmatched = queryWords.isNotEmpty() && ranked.isEmpty()
    val shown = (if (unmatched) runnable else ranked).take(limit)
    val plugins = catalog.applicablePlugins(app.packageName, installed)
    val integration = catalog.integration(plugins)
    val footer = buildList {
        if (unmatched) add("No action of ${app.label} matched '$query'; these are all its actions.")
        if (shown.size < (if (unmatched) runnable.size else ranked.size)) add("More actions not shown; raise limit.")
        if (!runner) {
            add("This agent may not run app actions (app_action_run is blocked for it or above its access level), so none are listed.")
        } else if (hidden > 0) {
            add("$hidden ${if (hidden == 1) "action is" else "actions are"} hidden: this agent may not use ${if (hidden == 1) "it" else "them"}.")
        }
    }
    val result = ToolResult.json {
        put("app", app.label)
        put("package", app.packageName)
        put("integration", integration.id)
        plugins.firstOrNull()?.let { put("status", it.status.id) }
        ActionText.integrationNote(integration, plugins.firstOrNull())?.let { put("note", it) }
        putJsonArray("actions") { shown.forEach { a -> addJsonObject { describe(catalog, a, withApp = false) } } }
        if (shown.isNotEmpty()) put("how_to_run", ActionText.howToRun(app.packageName))
    }
    return withFooter(result, footer)
}

private suspend fun search(catalog: AppActionCatalog, query: String, limit: Int, ctx: CallContext): ToolResult {
    val words = ActionSearch.words(query)
    if (words.isEmpty()) throw ToolException("'$query' has no words to search for. Describe the task, e.g. \"play music\".")
    if (!AppActionCatalog.mayRunActions(ctx)) {
        return withFooter(
            ToolResult.json { put("query", query); putJsonArray("matches") {} },
            listOf("This agent may not run app actions (app_action_run is blocked for it or above its access level)."),
        )
    }
    val installed = catalog.platform.installedApps()
    val packages = installed.mapTo(HashSet()) { it.packageName }
    val visible = installed.filter { ctx.appAccess.isAllowed(it.packageName) }
    var hidden = 0
    val candidates = ArrayList<AppAction>()
    for (app in visible) {
        for (action in catalog.actions(app, packages)) {
            if (catalog.mayRun(action, ctx)) candidates += action else if (ActionSearch.score(words, action) > 0) hidden++
        }
    }
    val ranked = ActionSearch.rank(words, candidates)
    val shown = ranked.take(limit)
    val footer = buildList {
        if (ranked.size > shown.size) add("${ranked.size - shown.size} more matches not shown; raise limit or pass 'app'.")
        if (hidden > 0) add("$hidden matching ${if (hidden == 1) "action is" else "actions are"} hidden: this agent may not use them.")
        if (visible.size < installed.size) add("Some apps are hidden by this agent's app access settings.")
        if (shown.isEmpty()) add("No app action matches; use apps_list and the screen tools instead.")
    }
    val result = ToolResult.json {
        put("query", query)
        putJsonArray("matches") { shown.forEach { a -> addJsonObject { describe(catalog, a, withApp = true) } } }
        if (shown.isNotEmpty()) put("how_to_run", ActionText.howToRun(null))
    }
    return withFooter(result, footer)
}

private fun JsonObjectBuilder.describe(catalog: AppActionCatalog, a: AppAction, withApp: Boolean) {
    if (withApp) {
        put("app", a.app.label)
        put("package", a.app.packageName)
        put("integration", catalog.integration(listOfNotNull(a.plugin)).id)
    }
    put("action", a.action.id)
    put("title", a.action.title)
    put("description", a.action.description)
    catalog.toolFor(a)?.let { put("risk", it.risk.name) }
    put("completes", a.action.completesTask)
    put("params", ParamSchemas.objectSchema(a.action.params))
    val reason = try {
        catalog.platform.unavailableReason(a.app.packageName, a.action)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        "Could not check availability on the phone (${e.message ?: e.javaClass.simpleName})."
    }
    if (reason != null && a.action.android !is ToolRecipe) {
        put("available", false)
        put("unavailable_reason", reason)
    }
    catalog.health.status(a.app, a.action.id)?.let { record ->
        put(
            "health",
            buildJsonObject {
                put("status", if (record.failing) "failing" else "failed_last_time")
                put("failures", record.failures)
                put("last_error", record.lastError)
                put("note", "Failed on ${a.app.label} ${catalog.health.versionLabel(a.app)} on this phone; prefer phone use (screen_observe) for this task.")
            },
        )
    }
}

private fun withFooter(result: ToolResult, footer: List<String>): ToolResult =
    if (footer.isEmpty()) result else result.copy(content = result.content + ToolContent.Text(footer.joinToString(" ")))

/**
 * `app_action_run`: resolves the app and action, checks the parameters, then hands the call to the server
 * as `<package>/<action>` (or, for a `tool` recipe, as the built-in tool with the rendered arguments), which
 * applies scopes, policy, approval and audit to it ([DispatchTool]).
 */
internal class AppActionRunTool(private val catalog: AppActionCatalog) : DispatchTool {
    override val name = ToolNames.APP_ACTION_RUN
    override val title = "Run an app action"
    override val description =
        "Runs one of an app's ready-made actions: 'app' (name or package), 'action' (its id) and 'params' (an object " +
            "matching the action's parameters). app_actions_list shows the actions and their parameters; apps_list shows " +
            "each app's action ids. Each action is checked and approved like a tool of its own (named <package>/<action>), " +
            "so it may need the user's approval on the phone. Actions with completes=false open the right screen and " +
            "leave the last step to you (screen_observe, ui_tap) or the user. Apps the user blocked for this agent are refused."
    override val scope = Scopes.APPS
    override val risk = Risk.ACTION
    override val inputSchema: JsonObject = objectSchema {
        string("app", "App name (e.g. Spotify) or package (e.g. com.spotify.music)", required = true)
        string("action", "Action id from app_actions_list, e.g. play", required = true)
        obj("params", "The action's parameters, e.g. {\"query\": \"lofi beats\"}")
    }
    override val timeoutMs: Long get() = 20_000

    override fun describeCall(args: JsonObject): String =
        "Run ${args.optString("action") ?: "an action"} in ${args.optString("app") ?: "an app"}"

    override suspend fun resolve(args: JsonObject, ctx: CallContext): Dispatch {
        val appName = args.optString("app")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Pass 'app' (e.g. Spotify) and 'action' (e.g. play). app_actions_list shows what each app can do.")
        val actionId = args.optString("action")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolException("Pass 'action', e.g. play. Call app_actions_list with app=\"$appName\" to see its actions.")
        val params = paramsOf(args)
        val app = catalog.resolveApp(appName, ctx.appAccess)
        val target = catalog.action(app, actionId) ?: throw ToolException(unknownAction(app, actionId, ctx))
        val recipe = target.action.android ?: throw ToolException(unknownAction(app, actionId, ctx))
        val tool = catalog.toolFor(target) ?: throw ToolException(
            "${app.label}'s '${target.action.id}' action can't run on this phone" +
                ((recipe as? ToolRecipe)?.let { ": it needs the '${it.name}' tool, which this phone doesn't offer" } ?: "") +
                ". Use the screen tools instead.",
        )
        val values = ParamValues.validate(target.action.params, params)
        val rendered = RecipeRenderer(app.packageName).render(recipe, values) // Fails here, before any approval prompt.
        if (rendered is RenderedToolCall) return Dispatch(tool, rendered.args, alias = target.toolName)
        tool.unavailableReason()?.takeIf { it.isNotBlank() }?.let {
            throw ToolException("${app.label}'s '${target.action.id}' action can't run right now: $it")
        }
        return Dispatch(tool, values)
    }

    private fun paramsOf(args: JsonObject): JsonObject = when (val raw = args["params"]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> raw
        // Some agents send the object as JSON text.
        is JsonPrimitive -> if (raw.isString && raw.content.isBlank()) {
            JsonObject(emptyMap())
        } else {
            (runCatching { Json.parseToJsonElement(raw.content) }.getOrNull() as? JsonObject)
                ?: throw ToolException("'params' must be an object, e.g. {\"query\": \"lofi beats\"}.")
        }
        else -> throw ToolException("'params' must be an object, e.g. {\"query\": \"lofi beats\"}.")
    }

    private suspend fun unknownAction(app: InstalledApp, actionId: String, ctx: CallContext): String {
        val ids = catalog.actions(app).filter { catalog.mayRun(it, ctx) }.map { it.action.id }
        val shownId = actionId.take(60)
        return if (ids.isEmpty()) {
            "${app.label} (${app.packageName}) has no action '$shownId' and none this agent can run. Use the screen tools instead."
        } else {
            "${app.label} (${app.packageName}) has no action '$shownId'. Its actions: ${ids.joinToString(", ")}. " +
                "Call app_actions_list with app=\"${app.packageName}\" for their parameters."
        }
    }
}

/**
 * The tool behind one app action whose recipe the platform runs (intent, url or steps), named
 * `<package>/<action id>`. Its scope and risk are the action's; actions of local plugins always need
 * approval ([Tool.alwaysConfirm]) and their prompt says "unreviewed plugin".
 */
internal class AppActionTool(
    val target: AppAction,
    private val platform: AppActionsPlatform,
    private val health: ActionHealth = ActionHealth(),
) : Tool {
    private val action = target.action
    private val app = target.app

    override val name: String = target.toolName
    override val title: String = "${app.label}: ${action.title}" + if (target.isLocal) " ${ActionText.UNREVIEWED}" else ""
    override val description: String = action.description
    override val scope: String = action.effectiveScope

    /** A plugin action always declares its risk; anything else is treated as the riskiest (fail closed). */
    override val risk: Risk = action.risk ?: Risk.SENSITIVE
    override val inputSchema: JsonObject = ParamSchemas.objectSchema(action.params)
    override val timeoutMs: Long = if (action.android is StepsRecipe) PluginRules.STEPS_TIMEOUT_MS else PluginRules.LAUNCH_TIMEOUT_MS
    override val alwaysConfirm: Boolean = target.isLocal

    override fun unavailableReason(): String? = platform.unavailableReason(app.packageName, action)

    override fun describeCall(args: JsonObject): String = ActionText.confirmation(target, args)

    override suspend fun call(args: JsonObject, ctx: CallContext): ToolResult {
        val recipe = action.android ?: throw ToolException("This action has no recipe for this phone.")
        ctx.appAccess.require(app.packageName, app.label)
        val values = ParamValues.validate(action.params, args)
        val rendered = RecipeRenderer(app.packageName).render(recipe, values)
        if (rendered is RenderedToolCall) throw ToolException("Internal error: '$name' must run as ${rendered.name}.")
        unavailableReason()?.takeIf { it.isNotBlank() }?.let { throw ToolException("This action can't run right now: $it") }
        ctx.ensureStillAllowed()
        val result = try {
            platform.execute(ActionRequest(app, action, target.plugin, rendered, values), ctx)
        } catch (e: ToolException) {
            if (e is AppBlockedException || e is CallStoppedException) throw e
            val notes = listOfNotNull(
                (e as? IntegrationException)?.let { health.failureNote(app, health.failed(app, action.id, it.message.orEmpty())) },
                ActionText.failureNote(target.plugin),
            )
            if (notes.isEmpty()) throw e
            throw ToolException((listOf(e.message.orEmpty().trimEnd()) + notes).joinToString(" "))
        }
        health.succeeded(app, action.id)
        return ActionText.result(target, result)
    }
}

/** Agent- and user-facing texts for app actions (pure). */
internal object ActionText {
    const val UNREVIEWED = "(unreviewed plugin)"
    private const val MAX_LISTED_APPS = 10

    /** "Spotify (com.spotify.music), Spotify Lite (com.spotify.lite)". */
    fun appList(apps: List<InstalledApp>): String =
        apps.take(MAX_LISTED_APPS).joinToString(", ") { "${it.label} (${it.packageName})" } +
            if (apps.size > MAX_LISTED_APPS) ", …" else ""

    fun howToRun(packageName: String?): String =
        "Call app_action_run with app=\"${packageName ?: "<package>"}\", action=\"<action>\" and params matching the action's " +
            "params schema."

    /**
     * The approval prompt: the plugin's `confirm` template, or "<App>: <title> (k=v, …)". For local plugins,
     * whose texts are unreviewed, what the rendered recipe will really do comes first:
     * "<recipe summary>. Plugin says: <confirm>", at most [MAX_PROMPT] chars.
     */
    fun confirmation(target: AppAction, params: JsonObject): String {
        val action = target.action
        val base = action.confirm?.let { c -> runCatching { Template.parse(c).render(params) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(target.app.label).append(": ").append(action.title)
                val given = params.entries.filter { it.value !is JsonNull }
                if (given.isNotEmpty()) {
                    append(" (")
                    append(given.joinToString(", ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.let(Templates::text) ?: v}" })
                    append(")")
                }
            }
        if (!target.isLocal) return base
        val recipe = action.android ?: return base
        val shown = runCatching { recipeSummary(RecipeRenderer(target.app.packageName).render(recipe, params)) }.getOrNull() ?: return base
        return ToolText.truncate("${shown.replaceFirstChar { it.uppercaseChar() }}. Plugin says: $base", MAX_PROMPT)
    }

    /** What a rendered recipe will do, in a few words, for prompts (at most [MAX_SUMMARY] chars). */
    fun recipeSummary(recipe: RenderedRecipe): String = ToolText.truncate(
        when (recipe) {
            is RenderedUrl -> "opens ${recipe.url}"
            is RenderedIntent -> intentSummary(recipe)
            is RenderedSteps -> {
                val shown = stepSummaries(recipe.steps)
                if (shown.isEmpty()) "runs ${recipe.steps.size} screen steps in the app" else "in the app: ${shown.joinToString(", ")}"
            }
            is RenderedToolCall -> "runs ${recipe.name}"
        },
        MAX_SUMMARY,
    )

    private fun intentSummary(intent: RenderedIntent): String = buildString {
        append("starts ").append(intent.action)
        intent.data?.let { append(" ").append(it) }
        if (intent.extras.isNotEmpty()) {
            append(" with ")
            append(intent.extras.entries.joinToString(", ") { (k, v) -> "$k=${ToolText.truncate(extraText(v), MAX_VALUE)}" })
        }
    }

    private fun extraText(value: ExtraValue): String = when (value) {
        is ExtraValue.StringValue -> "\"${value.value}\""
        is ExtraValue.IntValue -> value.value.toString()
        is ExtraValue.LongValue -> value.value.toString()
        is ExtraValue.DoubleValue -> value.value.toString()
        is ExtraValue.BooleanValue -> value.value.toString()
        is ExtraValue.StringArrayValue -> value.values.joinToString(", ", "[", "]") { "\"$it\"" }
    }

    /** The steps that act (taps, typing, links, intents), including those inside `if_present`. */
    private fun stepSummaries(steps: List<RenderedStep>): List<String> = steps.flatMap { step ->
        when (val a = step.action) {
            is RenderedStepAction.Tap -> listOf("taps ${selectorText(a.selector)}")
            is RenderedStepAction.LongPress -> listOf("long-presses ${selectorText(a.selector)}")
            is RenderedStepAction.TypeText ->
                listOf("types \"${ToolText.truncate(a.text, MAX_TYPED)}\"" + if (a.submit) " and submits" else "")
            is RenderedStepAction.OpenUrl -> listOf("opens ${a.url.url}")
            is RenderedStepAction.StartIntent -> listOf(intentSummary(a.intent))
            is RenderedStepAction.IfPresent -> stepSummaries(a.then)
            else -> emptyList()
        }
    }

    /** The element a selector names, for people: its text or description, else its view id or class. */
    private fun selectorText(s: Selector): String {
        val text = s.textOrDesc ?: s.text ?: s.desc ?: s.textContains ?: s.descContains
        if (text != null) return "\"${ToolText.truncate(text, MAX_VALUE)}\""
        return s.id?.substringAfter(":id/") ?: s.className ?: "an element"
    }

    private const val MAX_PROMPT = 400
    private const val MAX_SUMMARY = 300
    private const val MAX_TYPED = 60
    private const val MAX_VALUE = 40

    fun integrationNote(integration: Integration, plugin: AppPlugin?): String? = when {
        integration == Integration.LOCAL ->
            "Local plugin that the PhoneBridge project has not reviewed: the user must approve each of its actions on the phone."
        plugin?.status == PluginStatus.EXPERIMENTAL ->
            "Community plugin, marked experimental: if an action fails or the result looks wrong, fall back to the screen tools " +
                "(screen_observe, ui_tap)."
        else -> null
    }

    /** Appended to failures of local and experimental plugin actions. */
    fun failureNote(plugin: AppPlugin?): String? = when {
        plugin == null -> null
        plugin.source == PluginSource.LOCAL ->
            "(${plugin.name} support comes from a local plugin that has not been reviewed: if this keeps failing, use the screen tools instead.)"
        plugin.status == PluginStatus.EXPERIMENTAL ->
            "(${plugin.name} support is an experimental community plugin: if this keeps failing, use the screen tools instead.)"
        else -> null
    }

    /** Added to results of local and experimental plugin actions. */
    private fun resultNote(plugin: AppPlugin?): String? = when {
        plugin == null -> null
        plugin.source == PluginSource.LOCAL ->
            "${plugin.name} support comes from a local plugin that has not been reviewed. If the result looks wrong, check with " +
                "screen_observe and fall back to the screen tools."
        plugin.status == PluginStatus.EXPERIMENTAL ->
            "${plugin.name} support is an experimental community plugin. If the result looks wrong, check with screen_observe and " +
                "fall back to the screen tools."
        else -> null
    }

    /** The agent-facing result of a platform-run action. */
    fun result(target: AppAction, result: ActionResult): ToolResult = ToolResult.json {
        val action = target.action
        put("app", target.app.label)
        put("package", target.app.packageName)
        put("action", action.id)
        val done = action.completesTask
        put("done", done)
        if (!done) put("opened", true)
        for ((key, value) in result.fields) if (key !in RESERVED) put(key, value)
        result.note?.let { put("note", it) }
        if (!done) {
            put(
                "next",
                "${target.app.label} is open at the right place, but the task is not finished: someone still has to complete " +
                    "it there. Use screen_observe to see the screen and ui_tap to finish it, or ask the user to finish it on the phone.",
            )
        }
        resultNote(target.plugin)?.let { put("integration_note", it) }
    }

    private val RESERVED = setOf("app", "package", "action", "done", "opened", "note", "next", "integration_note")
}

/** Keyword search over app actions for `app_actions_list(query=…)` (pure). */
internal object ActionSearch {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val STOP_WORDS = setOf(
        "a", "an", "and", "app", "at", "be", "by", "can", "do", "for", "from", "i", "in", "into", "is", "it", "me", "my",
        "of", "on", "or", "please", "some", "the", "to", "up", "using", "want", "with", "you",
    )

    fun words(text: String): Set<String> =
        text.lowercase().split(SEPARATORS).filter { it.length >= 2 && it !in STOP_WORDS }.toSet()

    /**
     * Keyword overlap: per query word, 3 for the action's title, id or keywords, 2 for the app's name,
     * 1 for the description (whole words, or prefixes of at least 4 letters: "playing" finds "play").
     */
    fun score(query: Set<String>, a: AppAction): Int {
        val strong = words(a.action.title) + words(a.action.id) + a.action.keywords.flatMap(::words)
        val app = words(a.app.label) + (a.plugin?.let { words(it.name) } ?: emptySet())
        val weak = words(a.action.description)
        return query.sumOf { q ->
            when {
                matches(q, strong) -> 3
                matches(q, app) -> 2
                matches(q, weak) -> 1
                else -> 0
            }.toInt()
        }
    }

    /** Actions with a positive score, best first; ties: plugin actions first, then by app label, then catalog order. */
    fun rank(query: Set<String>, actions: List<AppAction>): List<AppAction> =
        actions.mapIndexedNotNull { i, a -> score(query, a).takeIf { it > 0 }?.let { Triple(a, it, i) } }
            .sortedWith(
                compareByDescending<Triple<AppAction, Int, Int>> { it.second }
                    .thenBy { if (it.first.plugin != null) 0 else 1 }
                    .thenBy { it.first.app.label.lowercase() }
                    .thenBy { it.third },
            )
            .map { it.first }

    private fun matches(q: String, words: Set<String>): Boolean =
        q in words || words.any { w -> minOf(w.length, q.length) >= 4 && (w.startsWith(q) || q.startsWith(w)) }
}
