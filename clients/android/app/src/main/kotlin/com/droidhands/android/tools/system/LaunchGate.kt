package com.droidhands.android.tools.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.droidhands.android.notify.AppLabels
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enforces the agent's app access ([AppAccess], docs/SECURITY.md) before an activity is started
 * for it. DroidHands itself is always off limits, whatever the access settings say.
 */
internal object LaunchGate {
    const val OWN_APP = "DroidHands does not let agents open its own screens."

    /**
     * Throws a [ToolException] (naming the app) unless the agent may use [packageName].
     * Blocking label lookup: call off the main thread.
     */
    fun requireApp(context: Context, access: AppAccess, packageName: String) {
        if (packageName == context.packageName) throw ToolException(OWN_APP)
        access.require(packageName, AppLabels.of(context, packageName))
    }

    /** True if the agent may see/use [packageName] (never DroidHands itself). */
    fun isAllowed(context: Context, access: AppAccess, packageName: String): Boolean =
        packageName != context.packageName && access.isAllowed(packageName)

    /**
     * Checks [intent], which resolved to [resolved] (`resolveActivity` with MATCH_DEFAULT_ONLY),
     * against [access]. An explicit package or component is checked directly. Otherwise, when Android
     * would show its chooser and some candidate apps are blocked, the intent is pinned to the one
     * allowed app (mutating [intent]) or refused; see [LaunchTargetPolicy]. [what] names the thing
     * being opened in errors ("this link", "this intent").
     *
     * Returns the app that will open the intent, or null when Android's chooser will ask the user.
     */
    suspend fun check(context: Context, intent: Intent, resolved: ResolveInfo, access: AppAccess, what: String): LaunchHandler? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val resolvedHandler = handler(context, resolved)
            val explicit = intent.component?.packageName ?: intent.`package`
            if (explicit != null) {
                requireApp(context, access, explicit)
                return@withContext resolvedHandler?.takeIf { it.packageName == explicit }
            }
            resolvedHandler ?: throw ToolException("Android could not tell which app would open $what, so nothing was opened.")
            val candidates = pm.queryIntentActivitiesCompat(intent, PackageManager.MATCH_DEFAULT_ONLY).mapNotNull { handler(context, it) }
            when (val decision = LaunchTargetPolicy.decide(resolvedHandler, candidates) { isAllowed(context, access, it) }) {
                is LaunchDecision.Proceed -> decision.target
                is LaunchDecision.Pin -> {
                    intent.setPackage(decision.target.packageName)
                    decision.target
                }
                is LaunchDecision.Blocked -> {
                    // A single blocked app gets the standard wording (and DroidHands's own message).
                    decision.handlers.singleOrNull()?.let { requireApp(context, access, it.packageName) }
                    LaunchTargetPolicy.requireLaunchable(decision, what)
                    null
                }
                is LaunchDecision.Choose -> {
                    LaunchTargetPolicy.requireLaunchable(decision, what)
                    null
                }
            }
        }

    private fun handler(context: Context, info: ResolveInfo): LaunchHandler? {
        val activity = info.activityInfo ?: return null
        return LaunchHandler(activity.packageName, activity.name, AppLabels.of(context, activity.packageName))
    }
}
