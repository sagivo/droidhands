package com.droidhands.android.ui.apps

import com.droidhands.core.plugins.AppAction
import com.droidhands.core.plugins.AppActionCatalog
import com.droidhands.core.plugins.Integration
import com.droidhands.core.plugins.PluginStatus
import com.droidhands.core.plugins.ToolRecipe
import com.droidhands.core.tools.Risk
import kotlinx.coroutines.CancellationException

/** One parameter of an app action, for the app detail page. */
data class ActionParamInfo(val name: String, val description: String, val type: String, val required: Boolean)

/**
 * One action of one installed app as the UI shows it (docs/PLUGINS.md): a snapshot of the catalog's
 * [AppAction] and the tool it runs ([AppActionCatalog.toolFor]), so [risk] and [scope] are what policy uses.
 */
data class ActionInfo(
    val id: String,
    val title: String,
    val description: String,
    val risk: Risk,
    val scope: String,
    val params: List<ActionParamInfo> = emptyList(),
    /** The tool name rules, approvals and the audit log use: `<package>/<action id>`. */
    val toolName: String,
    /** For `tool` recipes, the built-in tool that really runs (its rules decide Allow / Ask, docs/PLUGINS.md §6.5). */
    val builtInTool: String? = null,
    /** Discovered from what the app declares (§8), not from a plugin. */
    val discovered: Boolean = false,
    /** From an unreviewed local plugin: always asks for approval. */
    val unreviewed: Boolean = false,
    /** False when the action opens a screen where someone still has to finish (§4 `completes`). */
    val completes: Boolean = true,
    /** Why it can't run right now (e.g. screen recipes without the accessibility service), or null. */
    val unavailableReason: String? = null,
)

/** One launchable app and its actions, for the Apps tab, per-action rules and activity names. */
data class AppListing(
    val packageName: String,
    val label: String,
    val integration: Integration,
    val pluginId: String? = null,
    val pluginName: String? = null,
    val pluginStatus: PluginStatus? = null,
    val actions: List<ActionInfo> = emptyList(),
    val system: Boolean = false,
)

/** Builds [AppListing]s from the app-action catalog (pure JVM: core types only, unit-tested with a fake platform). */
object AppListings {
    /**
     * Every installed app (DroidHands itself excluded by the platform) with its actions in catalog order.
     * Actions whose tool can't run on this phone ([AppActionCatalog.toolFor] null) are left out, as they are
     * for agents. One app's failing scan only empties that app's list.
     */
    suspend fun load(catalog: AppActionCatalog): List<AppListing> {
        val apps = catalog.platform.installedApps()
        val installed = apps.mapTo(HashSet()) { it.packageName }
        return apps.map { app ->
            val plugins = catalog.applicablePlugins(app.packageName, installed)
            val actions = try {
                catalog.actions(app, installed).mapNotNull { info(catalog, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val plugin = plugins.firstOrNull()
            AppListing(
                packageName = app.packageName,
                label = app.label,
                integration = catalog.integration(plugins),
                pluginId = plugin?.id,
                pluginName = plugin?.name,
                pluginStatus = plugin?.status,
                actions = actions,
                system = app.system,
            )
        }
    }

    private fun info(catalog: AppActionCatalog, action: AppAction): ActionInfo? {
        val tool = catalog.toolFor(action) ?: return null
        val recipe = action.action.android
        val unavailable = if (recipe is ToolRecipe) {
            null
        } else {
            runCatching { catalog.platform.unavailableReason(action.app.packageName, action.action) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return ActionInfo(
            id = action.action.id,
            title = action.action.title,
            description = action.action.description,
            risk = tool.risk,
            scope = tool.scope,
            params = action.action.params.map { ActionParamInfo(it.name, it.description, it.type.id, it.required) },
            toolName = action.toolName,
            builtInTool = (recipe as? ToolRecipe)?.name,
            discovered = action.plugin == null,
            unreviewed = action.isLocal,
            completes = action.action.completesTask,
            unavailableReason = unavailable,
        )
    }
}
