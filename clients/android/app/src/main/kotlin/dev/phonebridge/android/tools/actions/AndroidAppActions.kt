package dev.phonebridge.android.tools.actions

import android.content.Context
import dev.phonebridge.android.tools.screen.UiAutomation
import dev.phonebridge.android.tools.system.AppEntry
import dev.phonebridge.android.tools.system.AppPick
import dev.phonebridge.android.tools.system.AppVisibility
import dev.phonebridge.android.tools.system.LaunchGate
import dev.phonebridge.android.tools.system.LaunchNotes
import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionsPlatform
import dev.phonebridge.core.plugins.AppResolution
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.RenderedIntent
import dev.phonebridge.core.plugins.RenderedSteps
import dev.phonebridge.core.plugins.RenderedToolCall
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.RecipeProbe
import dev.phonebridge.core.plugins.RecipeRenderer
import dev.phonebridge.core.plugins.StepsRecipe
import dev.phonebridge.core.plugins.UrlRecipe
import dev.phonebridge.android.tools.system.LaunchableApp
import java.util.concurrent.ConcurrentHashMap
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Android side of app actions (docs/PLUGINS.md): installed apps and discovered actions from [AppScanner],
 * app names resolved like `app_open` does ([AppVisibility.pick], [LaunchGate]; PhoneBridge itself is never
 * offered), intent and link recipes started by [ActionIntents] (pinned to the app), and screen recipes run by
 * [StepRunner] on top of the screen tools' safety checks. Thread-safe.
 */
internal class AndroidAppActions(context: Context) : AppActionsPlatform {
    private val context = context.applicationContext
    private val scanner = AppScanner(this.context)

    override suspend fun installedApps(): List<InstalledApp> =
        scanner.scan().apps.filter { it.entry.packageName != context.packageName }.map(::installed)

    override suspend fun resolveApp(query: String, access: AppAccess): AppResolution =
        when (val pick = AppVisibility.pick(query, launchable()) { LaunchGate.isAllowed(context, access, it) }) {
            is AppPick.Found -> AppResolution.Found(installed(pick.app))
            is AppPick.Blocked -> AppResolution.Blocked(installed(pick.app))
            is AppPick.Ambiguous -> AppResolution.Ambiguous(pick.candidates.map(::installed), pick.someBlocked)
            is AppPick.NotFound -> AppResolution.NotFound(pick.suggestions.map(::installed))
        }

    override suspend fun discoveredActions(packageName: String): List<PluginAction> =
        if (packageName == context.packageName) emptyList() else scanner.actions(packageName)

    /** Per app version and action: "" when its intent or link reaches the app, else why not ([verify]). */
    private val verified = ConcurrentHashMap<Any, String>()

    override fun unavailableReason(packageName: String, action: PluginAction): String? = when (action.android) {
        is StepsRecipe -> UiAutomation.actionUnavailableReason(context)
        is IntentRecipe, is UrlRecipe -> {
            val app = scanner.cachedApp(packageName)
            if (app == null) {
                null
            } else {
                val key = listOf(packageName, VersionKey(app.versionCode, app.lastUpdateTime), action)
                verified.getOrPut(key) { verify(app, action) }.takeIf { it.isNotEmpty() }
            }
        }
        else -> null
    }

    /**
     * "" if an exported activity of the installed [app] accepts [action]'s intent or link (rendered with
     * [RecipeProbe.sampleValues]; nothing is started), else the agent-facing reason. Plugins are written for some app
     * version; this keeps them honest on the one installed here.
     */
    private fun verify(app: LaunchableApp, action: PluginAction): String = try {
        val pkg = app.entry.packageName
        val plan = when (val rendered = RecipeRenderer(pkg).render(action.android!!, RecipeProbe.sampleValues(action))) {
            is RenderedIntent -> IntentPlans.of(rendered)
            is RenderedUrl -> IntentPlans.of(rendered)
            else -> null
        }
        if (plan == null || ActionIntents.canStart(context.packageManager, plan)) {
            ""
        } else {
            val version = app.entry.versionName?.let { " $it" } ?: ""
            "${app.entry.label}$version on this phone has no screen that accepts this action's " +
                (if (action.android is UrlRecipe) "link" else "intent") + " (it was written for another version of the app). " +
                "Use the app's open_link action if it has one, or phone use (screen_observe, ui_tap)."
        }
    } catch (e: Exception) {
        "" // Can't check (e.g. a recipe that needs real values): don't hide the action.
    }

    override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult {
        val pkg = request.app.packageName
        return when (val recipe = request.recipe) {
            is RenderedIntent -> launch(request, IntentPlans.of(recipe.pinned(pkg)), ctx)
            is RenderedUrl -> launch(request, IntentPlans.of(recipe.pinned(pkg)), ctx)
            is RenderedSteps -> {
                if (recipe.packageName != pkg) throw ToolException(MISPINNED)
                StepRunner.run(context, request, recipe, ctx)
            }
            is RenderedToolCall -> throw ToolException("Internal error: '${request.action.id}' runs as the ${recipe.name} tool.")
        }
    }

    /** Fills the discovery cache in the background (see [AppScanner.warmUp]). */
    suspend fun warmUp(log: (String, Throwable) -> Unit) = scanner.warmUp(log)

    private suspend fun launch(request: ActionRequest, plan: IntentPlan, ctx: CallContext): ActionResult {
        val activity = ActionIntents.start(context, plan, ctx, request.app.label)
        return ActionResult(
            fields = buildJsonObject { put("activity", activity.removePrefix(request.app.packageName)) },
            note = LaunchNotes.afterLaunch(context),
        )
    }

    private suspend fun launchable(): List<AppEntry> = scanner.scan().entries.filter { it.packageName != context.packageName }

    private fun installed(entry: AppEntry) =
        installed(entry, scanner.cachedApp(entry.packageName)?.versionCode)

    private fun installed(app: LaunchableApp) = installed(app.entry, app.versionCode)

    private fun installed(entry: AppEntry, versionCode: Long?) =
        InstalledApp(entry.packageName, entry.label, entry.versionName, entry.system, versionCode)

    private fun RenderedIntent.pinned(pkg: String): RenderedIntent = if (packageName == pkg) this else throw ToolException(MISPINNED)

    private fun RenderedUrl.pinned(pkg: String): RenderedUrl = if (packageName == pkg) this else throw ToolException(MISPINNED)

    private companion object {
        const val MISPINNED = "Internal error: the action's recipe is not pinned to its app, so nothing was done."
    }
}
