package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import kotlinx.serialization.json.JsonObject

/** The operating system a device runs. Picks each plugin action's recipe section (`android:` or `ios:`). */
enum class DeviceOs {
    ANDROID,
    IOS,
    ;

    /** The plugin YAML key and the `platform` agents see, e.g. `ios`. */
    val id: String get() = name.lowercase()
}

/** An installed app that can be launched, as the platform lists it. */
data class InstalledApp(
    /** Android package name, or iOS bundle identifier: the app's id everywhere (app access, rules, audit). */
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
 * PackageManager, its fuzzy app matcher and the accessibility service; the iOS host with the app inventory and the
 * on-device runner (docs/IOS.md); core tests use a fake.
 * Implementations must be thread-safe.
 */
interface AppActionsPlatform {
    /** Which OS this is: plugin actions run with their recipe for it ([PluginAction.recipeFor]). */
    val os: DeviceOs get() = DeviceOs.ANDROID

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

    /**
     * What [packageName] declares that is useful to know but is not an action PhoneBridge can start, shown to agents
     * as `declares` in app_actions_list (null or empty: nothing). Keys are kinds and values are lists of short
     * objects, e.g. iOS `app_intents` (`{id, title, phrases?, params?}`: only Siri, Spotlight and Shortcuts can run
     * them, so they are done with phone use), `quick_actions` (Home Screen icon menu), `document_types`. Read from the
     * installed app version, never from a list kept by PhoneBridge.
     */
    suspend fun declaredCapabilities(packageName: String): JsonObject? = null

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
