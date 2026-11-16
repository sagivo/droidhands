package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import kotlinx.serialization.json.JsonObject

/** An installed app that can be launched, as the platform lists it. */
data class InstalledApp(
    val packageName: String,
    /** The name users know it by, e.g. "Spotify". */
    val label: String,
    val versionName: String? = null,
    /** Preinstalled system app. */
    val system: Boolean = false,
    /** The installed build (Android's versionCode); with [versionName], what [ActionHealth] keys records by. */
    val versionCode: Long? = null,
)

/** Result of resolving an app name for an agent whose app access may hide some apps. */
sealed interface AppResolution {
    data class Found(val app: InstalledApp) : AppResolution

    /** Several allowed apps match equally well; [someBlocked]: blocked apps matched too (never named). */
    data class Ambiguous(val candidates: List<InstalledApp>, val someBlocked: Boolean = false) : AppResolution

    /** No allowed app matches; [suggestions] are allowed apps only. */
    data class NotFound(val suggestions: List<InstalledApp> = emptyList()) : AppResolution

    /** The name clearly means an app the agent may not use; the refusal names it so the agent stops looking. */
    data class Blocked(val app: InstalledApp) : AppResolution
}

/** One action call, ready to run on the device. */
class ActionRequest(
    val app: InstalledApp,
    val action: PluginAction,
    /** The plugin that defines [action], or null for a discovered action. */
    val plugin: AppPlugin?,
    /** Never a [RenderedToolCall]: those run as the built-in tool. */
    val recipe: RenderedRecipe,
    /** The validated parameters ([ParamValues.validate]). */
    val params: JsonObject,
)

/** What a device reports after running an action: extra result fields (e.g. `{"handled_by": "…"}`) and an optional note. */
data class ActionResult(val fields: JsonObject = JsonObject(emptyMap()), val note: String? = null)

/**
 * What the platform provides for app actions (docs/PLUGINS.md). The Android app implements it with
 * PackageManager, its fuzzy app matcher and the accessibility service; core tests use a fake.
 * Implementations must be thread-safe.
 */
interface AppActionsPlatform {
    /** Launchable apps installed now, PhoneBridge itself excluded. May be cached and refreshed on package changes. */
    suspend fun installedApps(): List<InstalledApp>

    /**
     * Resolves an app name as the user says it ("spotify", "Google Maps"), honouring [access] the way
     * `app_open` does: blocked apps are never Found, candidates or suggestions, and a name that clearly
     * means a blocked app is [AppResolution.Blocked]. Exact package names are resolved by the caller first.
     */
    suspend fun resolveApp(query: String, access: AppAccess): AppResolution

    /**
     * Actions discovered from what [packageName] declares (§8): ordinary actions with intent or url
     * recipes, ids as in §8 (`open`, `share_text`, `shortcut_<id>`, …). Empty if none or not installed.
     */
    suspend fun discoveredActions(packageName: String): List<PluginAction>

    /** Null if [action] of [packageName] can run now, else why not (e.g. steps need accessibility). Fast, never blocks. */
    fun unavailableReason(packageName: String, action: PluginAction): String?

    /**
     * Runs [request] (an intent, link or steps recipe pinned to [ActionRequest.app]). Throw
     * [dev.phonebridge.core.tools.ToolException] with an agent-facing message on failure ([IntegrationException] when
     * the app didn't do what the recipe expects, so [ActionHealth] records it), and
     * [dev.phonebridge.core.tools.AppBlockedException] if the app turns out to be blocked. Must call
     * [CallContext.ensureStillAllowed] after any wait and right before each side effect, and honour the
     * screen tools' safety checks for steps (docs/PLUGINS.md §6.4).
     */
    suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult
}
