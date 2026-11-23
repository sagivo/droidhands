package com.droidhands.android.tools.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import com.droidhands.android.Edition
import com.droidhands.android.tools.support.Launcher
import com.droidhands.android.tools.system.LaunchGate
import com.droidhands.android.tools.system.queryIntentActivitiesCompat
import com.droidhands.android.tools.system.resolveActivityCompat
import com.droidhands.core.plugins.ExtraValue
import com.droidhands.core.plugins.IntegrationException
import com.droidhands.core.tools.CallContext
import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts the activity of an [IntentPlan] (docs/PLUGINS.md §6.2, §6.3): always an exported activity of the plan's
 * own package, picked explicitly (so Android never shows a chooser or opens another app), after the agent's app
 * access is checked ([LaunchGate]). If the package has no matching activity the call fails: there is no browser
 * fallback.
 */
internal object ActionIntents {
    /**
     * Starts [plan] for the call [ctx]; [appLabel] names the app in errors. Returns the started activity's class.
     * Checks app access first and [CallContext.ensureStillAllowed] right before the start.
     */
    suspend fun start(context: Context, plan: IntentPlan, ctx: CallContext, appLabel: String): String {
        withContext(Dispatchers.IO) { LaunchGate.requireApp(context, ctx.appAccess, plan.packageName) }
        val (intent, resolved) = withContext(Dispatchers.IO) { resolve(context.packageManager, plan, appLabel) }
        LaunchGate.check(context, intent, resolved, ctx.appAccess, "this action")
        ctx.ensureStillAllowed()
        Launcher.startActivity(context, intent)
        return resolved.activityInfo.name
    }

    /** The launcher activity of [packageName], or null if it has none. Blocking IPC: call on Dispatchers.IO. */
    fun launcherClass(context: Context, packageName: String): String? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.className

    /** [plan] as an Intent pinned to its package, with its component set to the activity that will run it. */
    private fun resolve(pm: PackageManager, plan: IntentPlan, appLabel: String): Pair<Intent, ResolveInfo> {
        val attempts = if (plan.retryWithoutBrowsable) {
            listOf(plan, plan.copy(categories = plan.categories - IntentPlans.CATEGORY_BROWSABLE))
        } else {
            listOf(plan)
        }
        for (attempt in attempts.distinct()) {
            val intent = toIntent(attempt)
            val info = resolveInPackage(pm, intent, attempt) ?: continue
            intent.component = ComponentName(plan.packageName, info.activityInfo.name)
            return intent to info
        }
        throw IntegrationException(
            if (plan.retryWithoutBrowsable) {
                "$appLabel can't open this link on this phone (maybe its app version doesn't support it), so nothing was " +
                    "opened. App actions never open links in another app; use ${Edition.fallback()} instead."
            } else {
                "$appLabel has no screen for this action on this phone (maybe its app version doesn't support it), so " +
                    "nothing was opened. Use ${Edition.fallback()} instead."
            },
        )
    }

    /** Whether an exported activity of [plan]'s package accepts it (as [start] would find it). Blocking IPC. */
    fun canStart(pm: PackageManager, plan: IntentPlan): Boolean {
        val attempts = if (plan.retryWithoutBrowsable) {
            listOf(plan, plan.copy(categories = plan.categories - IntentPlans.CATEGORY_BROWSABLE))
        } else {
            listOf(plan)
        }
        return attempts.distinct().any { resolveInPackage(pm, toIntent(it), it) != null }
    }

    /** The exported activity of the plan's package that should run [intent], or null if none can. */
    private fun resolveInPackage(pm: PackageManager, intent: Intent, plan: IntentPlan): ResolveInfo? {
        val pkg = plan.packageName
        val className = plan.className
        if (className != null) {
            intent.component = ComponentName(pkg, className)
            val info = pm.resolveActivityCompat(intent) ?: return null
            val activity = info.activityInfo ?: return null
            return info.takeIf { activity.packageName == pkg && activity.exported }
        }
        intent.setPackage(pkg)
        val candidates = pm.queryIntentActivitiesCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.packageName == pkg && it.activityInfo.exported }
        if (candidates.isEmpty()) return null
        // Android's own pick (e.g. the app's preferred handler) when it is one of them, else the best match.
        val preferred = pm.resolveActivityCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        return candidates.firstOrNull { it.activityInfo.name == preferred?.name && preferred?.packageName == pkg } ?: candidates.first()
    }

    /** The Intent for [plan], without a component (set by [resolve]). Never adds URI-permission flags. */
    private fun toIntent(plan: IntentPlan): Intent {
        val intent = Intent(plan.action)
        val uri = plan.data?.let(Uri::parse)
        when {
            uri != null && plan.type != null -> intent.setDataAndType(uri, plan.type)
            uri != null -> intent.data = uri
            plan.type != null -> intent.type = plan.type
        }
        plan.categories.forEach(intent::addCategory)
        for ((key, value) in plan.extras) {
            when (value) {
                is ExtraValue.StringValue -> intent.putExtra(key, value.value)
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
                is ExtraValue.LongValue -> intent.putExtra(key, value.value)
                is ExtraValue.DoubleValue -> intent.putExtra(key, value.value)
                is ExtraValue.BooleanValue -> intent.putExtra(key, value.value)
                is ExtraValue.StringArrayValue -> intent.putExtra(key, value.values.toTypedArray())
            }
        }
        when (plan.taskMode) {
            TaskMode.STANDARD -> Unit
            TaskMode.RESUME -> intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            TaskMode.FRESH -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return intent
    }
}
