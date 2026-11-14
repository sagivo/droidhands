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
import dev.phonebridge.core.plugins.StepsRecipe
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

    override suspend fun installedApps(): List<InstalledApp> = launchable().map(::installed)

    override suspend fun resolveApp(query: String, access: AppAccess): AppResolution =
        when (val pick = AppVisibility.pick(query, launchable()) { LaunchGate.isAllowed(context, access, it) }) {
            is AppPick.Found -> AppResolution.Found(installed(pick.app))
            is AppPick.Blocked -> AppResolution.Blocked(installed(pick.app))
            is AppPick.Ambiguous -> AppResolution.Ambiguous(pick.candidates.map(::installed), pick.someBlocked)
            is AppPick.NotFound -> AppResolution.NotFound(pick.suggestions.map(::installed))
        }

    override suspend fun discoveredActions(packageName: String): List<PluginAction> =
        if (packageName == context.packageName) emptyList() else scanner.actions(packageName)

    override fun unavailableReason(packageName: String, action: PluginAction): String? =
        if (action.android is StepsRecipe) UiAutomation.actionUnavailableReason(context) else null

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

    private fun installed(entry: AppEntry) = InstalledApp(entry.packageName, entry.label, entry.versionName, entry.system)

    private fun RenderedIntent.pinned(pkg: String): RenderedIntent = if (packageName == pkg) this else throw ToolException(MISPINNED)

    private fun RenderedUrl.pinned(pkg: String): RenderedUrl = if (packageName == pkg) this else throw ToolException(MISPINNED)

    private companion object {
        const val MISPINNED = "Internal error: the action's recipe is not pinned to its app, so nothing was done."
    }
}
