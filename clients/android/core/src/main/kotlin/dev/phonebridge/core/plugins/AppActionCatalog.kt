package dev.phonebridge.core.plugins

import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.DispatchTool
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolNames
import kotlinx.coroutines.CancellationException

/** How an app is integrated, as `apps_list` and `app_actions_list` report it. */
enum class Integration {
    /** A reviewed plugin from `plugins/`. */
    PLUGIN,

    /** A plugin imported on the phone (unreviewed). */
    LOCAL,

    /** Discovered actions only (§8). */
    BASIC,
    ;

    val id: String get() = name.lowercase()
}

/** One action of one installed app. [plugin] is null for a discovered action. */
data class AppAction(val app: InstalledApp, val action: PluginAction, val plugin: AppPlugin?) {
    /** The name policy, rules and audit use: `<package>/<action id>`, e.g. `com.spotify.music/play`. */
    val toolName: String get() = virtualToolName(app.packageName, action.id)

    /** From an unreviewed local plugin: always needs approval. */
    val isLocal: Boolean get() = plugin?.source == PluginSource.LOCAL
}

/** `<package>/<action id>`: the tool name of an app action (docs/PLUGINS.md §9). */
fun virtualToolName(packageName: String, actionId: String): String = "$packageName/$actionId"

/** What `apps_list` adds per app: its [integration] and the ids of the actions the calling agent may run. */
data class AppSummary(val integration: Integration, val actionIds: List<String>, val pluginStatus: PluginStatus? = null)

/**
 * Every installed app's actions: plugin actions from [plugins] plus the platform's discovered actions
 * (docs/PLUGINS.md §8; a plugin action wins on an id clash), each runnable as a tool named
 * `<package>/<action id>` (see [toolFor]). Thread-safe; [plugins] is read on every use so imported
 * plugins apply at once.
 *
 * [builtInTool] looks up the platform's registered tools by name, for `tool` recipes.
 */
class AppActionCatalog(
    private val plugins: () -> PluginCatalog,
    val platform: AppActionsPlatform,
    private val builtInTool: (String) -> Tool?,
    /** How each action has fared on each installed app version (reported to agents, recorded by [AppActionTool]). */
    val health: ActionHealth = ActionHealth(),
) {
    /** Launchable apps the agent may see under [access]. */
    suspend fun visibleApps(access: AppAccess): List<InstalledApp> = platform.installedApps().filter { access.isAllowed(it.packageName) }

    /**
     * The installed app [query] names (a package name, or the app's name as the user says it).
     * @throws AppBlockedException if it is blocked for the agent (whether installed or not, for package names).
     * @throws ToolException if no app or several apps match, listing candidates the agent may use.
     */
    suspend fun resolveApp(query: String, access: AppAccess): InstalledApp {
        val name = query.trim()
        if (name.isEmpty()) throw ToolException("Pass 'app': the app's name (e.g. Spotify) or package (e.g. com.spotify.music).")
        val installed = platform.installedApps()
        installed.firstOrNull { it.packageName == name }?.let { app ->
            access.require(app.packageName, app.label)
            return app
        }
        return when (val resolution = platform.resolveApp(name, access)) {
            is AppResolution.Found -> resolution.app.also { access.require(it.packageName, it.label) }
            is AppResolution.Blocked -> {
                val app = resolution.app
                access.require(app.packageName, app.label)
                throw AppBlockedException(app.packageName, AppAccess.blockedMessage(app.packageName, app.label, globally = false, agentName = access.agentName))
            }
            is AppResolution.Ambiguous -> throw ToolException(
                "'$name' matches several apps: ${ActionText.appList(resolution.candidates)}. Call again with 'app' set to one " +
                    "of these packages, or ask the user which one they mean." +
                    if (resolution.someBlocked) " (Some matching apps are not available to this agent.)" else "",
            )
            is AppResolution.NotFound -> {
                // A package name that isn't installed: refuse a blocked one the same way as an installed one.
                if (PluginRules.PACKAGE_NAME.matches(name) && name.none { it.isUpperCase() }) {
                    access.require(name)
                    throw ToolException("No app with package '$name' is installed. Use apps_list to see installed apps.")
                }
                val closest = resolution.suggestions.filter { access.isAllowed(it.packageName) }
                throw ToolException(
                    "No installed app matches '$name'." +
                        (if (closest.isNotEmpty()) " Closest: ${ActionText.appList(closest)}." else "") +
                        " Use apps_list to see installed apps.",
                )
            }
        }
    }

    /**
     * Every action of [app]: actions of the plugins that apply to it (those whose first installed package it
     * is, docs/PLUGINS.md §3), in catalog order, then the discovered actions whose ids no plugin action uses.
     * Plugin actions without an Android recipe are left out.
     */
    suspend fun actions(app: InstalledApp): List<AppAction> = actions(app, installedPackages())

    /** [actions] given the [installed] package names (to list many apps with one platform query). */
    suspend fun actions(app: InstalledApp, installed: Set<String>): List<AppAction> {
        val applicable = applicablePlugins(app.packageName, installed)
        val fromPlugins = applicable.flatMap { p -> p.actions.filter { it.android != null }.map { AppAction(app, it, p) } }
        val taken = fromPlugins.mapTo(HashSet()) { it.action.id }
        val discovered = try {
            platform.discoveredActions(app.packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // A failing scan must not hide the plugin actions.
        }
        val extra = discovered
            .filter { a ->
                PluginRules.ACTION_ID.matches(a.id) &&
                    when (val recipe = a.android) {
                        is IntentRecipe, is UrlRecipe, is StepsRecipe -> a.risk != null
                        // Only one built-in tool: open_link opening links with url_open, pinned to this app.
                        is ToolRecipe -> a.risk == null && isPinnedLinkOpener(recipe, app.packageName)
                        null -> false
                    }
            }
            .distinctBy { it.id }
            .filter { it.id !in taken }
            .map { AppAction(app, it, null) }
        return fromPlugins + extra
    }

    /**
     * A discovered `tool` recipe is accepted only as `url_open` with the app's own package literally pinned (open_link,
     * docs/PLUGINS.md §8): discovery must never reach other built-in tools.
     */
    private fun isPinnedLinkOpener(recipe: ToolRecipe, packageName: String): Boolean =
        recipe.name == URL_OPEN && recipe.args.keys == setOf("url", "package") &&
            recipe.args["package"] == ValueSpec.Template(packageName)

    /** One action of [app] by id (`play`, or the full `<package>/play`), or null. */
    suspend fun action(app: InstalledApp, id: String): AppAction? {
        val wanted = id.trim().removePrefix("${app.packageName}/")
        return actions(app).firstOrNull { it.action.id == wanted }
    }

    /** Package names of the installed apps. */
    suspend fun installedPackages(): Set<String> = platform.installedApps().mapTo(HashSet()) { it.packageName }

    /** The plugins that apply to [packageName]: those for which it is the first installed of their packages. */
    fun applicablePlugins(packageName: String, installed: Set<String>): List<AppPlugin> =
        plugins().pluginsFor(packageName).filter { p -> p.androidPackages.firstOrNull { it in installed } == packageName }

    /** The integration of an app whose applicable plugins ([applicablePlugins]) are [plugins]. */
    fun integration(plugins: List<AppPlugin>): Integration = when {
        plugins.any { it.source == PluginSource.LOCAL } -> Integration.LOCAL
        plugins.isNotEmpty() -> Integration.PLUGIN
        else -> Integration.BASIC
    }

    /** For `apps_list`: each of [apps]' integration and the action ids the agent behind [ctx] may run. */
    suspend fun summarize(apps: List<InstalledApp>, ctx: CallContext): Map<String, AppSummary> {
        val installed = installedPackages()
        val runner = mayRunActions(ctx)
        return apps.associate { app ->
            val applicable = applicablePlugins(app.packageName, installed)
            val ids = if (runner) actions(app, installed).filter { mayRun(it, ctx) }.map { it.action.id } else emptyList()
            app.packageName to AppSummary(integration(applicable), ids, applicable.firstOrNull()?.status)
        }
    }

    /**
     * The tool a call of [action] runs, or null if it can't run on this device: for a `tool` recipe the
     * built-in tool (marked unreviewed for local plugins; null if missing or not allowed for recipes),
     * otherwise a tool named [AppAction.toolName] that renders and runs the recipe on the platform.
     */
    fun toolFor(action: AppAction): Tool? {
        val recipe = action.action.android ?: return null
        if (recipe !is ToolRecipe) return AppActionTool(action, platform, health)
        if (ToolNames.recipeRefusal(recipe.name) != null) return null
        val tool = builtInTool(recipe.name) ?: return null
        if (tool is DispatchTool) return null
        return if (action.isLocal) UnreviewedTool(tool) else tool
    }

    /**
     * True if the agent behind [ctx] may run [action]: its tool exists, is in the token's scopes and risk
     * cap, and neither that tool nor [AppAction.toolName] has a BLOCK rule. (App access and
     * `app_action_run` itself are checked separately, see [mayRunActions].)
     */
    fun mayRun(action: AppAction, ctx: CallContext): Boolean {
        val tool = toolFor(action) ?: return false
        return mayUse(ctx, tool) && ctx.toolRules[action.toolName] != ToolRule.BLOCK
    }

    companion object {
        private const val URL_OPEN = "url_open"

        /** True if the token may call `app_action_run` at all (its scope, risk cap and tool rule). */
        fun mayRunActions(ctx: CallContext): Boolean =
            Scopes.allows(ctx.scopes, Scopes.APPS) && ctx.toolRules[ToolNames.APP_ACTION_RUN] != ToolRule.BLOCK &&
                !PolicyEngine.exceedsMaxRisk(ctx.maxRisk, Risk.ACTION)

        /** Same test as `tools/list`: in scope, not BLOCKed and within the risk cap. */
        fun mayUse(ctx: CallContext, tool: Tool): Boolean =
            Scopes.allows(ctx.scopes, tool.scope) && ctx.toolRules[tool.name] != ToolRule.BLOCK &&
                !PolicyEngine.exceedsMaxRisk(ctx.maxRisk, tool.risk)
    }
}

/** A built-in tool run by an unreviewed local plugin's `tool` recipe: always approved on the phone, and says so. */
private class UnreviewedTool(private val delegate: Tool) : Tool by delegate {
    override val title: String get() = "${delegate.title} ${ActionText.UNREVIEWED}"
    override val alwaysConfirm: Boolean get() = true
}
