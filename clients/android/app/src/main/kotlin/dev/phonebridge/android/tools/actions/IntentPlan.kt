package dev.phonebridge.android.tools.actions

import dev.phonebridge.core.plugins.ExtraValue
import dev.phonebridge.core.plugins.PluginRules
import dev.phonebridge.core.plugins.RenderedIntent
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.Templates
import dev.phonebridge.core.tools.ToolException

/** How an app action's activity start treats the app's existing task. */
enum class TaskMode {
    /** A plain start (FLAG_ACTIVITY_NEW_TASK only). */
    STANDARD,

    /** Like tapping the app's icon: bring its existing task to the front (FLAG_ACTIVITY_RESET_TASK_IF_NEEDED). */
    RESUME,

    /** Start the app afresh, clearing its back stack (FLAG_ACTIVITY_CLEAR_TASK): steps `launch: {fresh: true}`. */
    FRESH,
}

/**
 * An activity start for an app action, as plain values (pure): what [ActionIntents] turns into an Android
 * Intent pinned to [packageName]. It never carries URI-permission flags. [className], when set, is a full
 * class name started in [packageName]; otherwise the intent is resolved among [packageName]'s activities.
 */
data class IntentPlan(
    val action: String,
    val packageName: String,
    val data: String? = null,
    val type: String? = null,
    val categories: List<String> = emptyList(),
    val className: String? = null,
    val extras: Map<String, ExtraValue> = emptyMap(),
    val taskMode: TaskMode = TaskMode.STANDARD,
    /** Links: when no activity of the package accepts the link as BROWSABLE, try once more without that category. */
    val retryWithoutBrowsable: Boolean = false,
)

/**
 * Maps rendered recipes (docs/PLUGINS.md §6.2, §6.3) to [IntentPlan]s (pure). The renderer already refuses call
 * actions and forbidden URL schemes; they are checked again here, because a plan is the last stop before Android.
 */
object IntentPlans {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"

    /** An `intent` recipe. A launcher intent (MAIN + LAUNCHER) resumes the app's task like its home-screen icon. */
    fun of(intent: RenderedIntent): IntentPlan {
        requireSafe(intent.action, intent.packageName, intent.data)
        val launcher = intent.action == ACTION_MAIN && CATEGORY_LAUNCHER in intent.categories
        return IntentPlan(
            action = intent.action,
            packageName = intent.packageName,
            data = intent.data,
            type = intent.type,
            categories = intent.categories,
            className = intent.className,
            extras = intent.extras,
            taskMode = if (launcher) TaskMode.RESUME else TaskMode.STANDARD,
        )
    }

    /** A `url` recipe: ACTION_VIEW with CATEGORY_BROWSABLE, only in the app (never a browser fallback). */
    fun of(url: RenderedUrl): IntentPlan {
        requireSafe(ACTION_VIEW, url.packageName, url.url)
        return IntentPlan(
            action = ACTION_VIEW,
            packageName = url.packageName,
            data = url.url,
            categories = listOf(CATEGORY_BROWSABLE),
            retryWithoutBrowsable = true,
        )
    }

    /** The app's launcher screen ([launcherClass]); [fresh] clears its back stack first (steps `launch`). */
    fun launcher(packageName: String, launcherClass: String, fresh: Boolean): IntentPlan {
        requireSafe(ACTION_MAIN, packageName, null)
        return IntentPlan(
            action = ACTION_MAIN,
            packageName = packageName,
            categories = listOf(CATEGORY_LAUNCHER),
            className = launcherClass,
            taskMode = if (fresh) TaskMode.FRESH else TaskMode.RESUME,
        )
    }

    private fun requireSafe(action: String, packageName: String, data: String?) {
        if (PluginRules.isCallAction(action)) {
            throw ToolException("This action would place a phone call, which app actions may not do. Use phone_call instead.")
        }
        if (PluginRules.isBridgePackage(packageName)) throw ToolException("App actions can't open PhoneBridge itself.")
        data?.let(Templates::requireAllowedUrl)
    }
}
