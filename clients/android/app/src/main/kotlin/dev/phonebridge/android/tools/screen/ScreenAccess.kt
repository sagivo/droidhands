package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.AppVisibility
import dev.phonebridge.android.access.SystemOverlay
import dev.phonebridge.android.access.SystemSurfaces
import dev.phonebridge.android.notify.AppLabels
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException

/**
 * The calling agent's app access (docs/SECURITY.md "Advanced controls") as applied by the screen tools: what
 * they may show ([visibility]) and the refusals they raise. Built once per call with [of].
 */
class ScreenAccess private constructor(
    private val context: Context,
    private val appAccess: AppAccess,
    val visibility: AppVisibility,
) {
    /** Whether any app is blocked for this agent (besides PhoneBridge itself). */
    val restricted: Boolean get() = visibility.restricted

    fun allows(pkg: String?): Boolean = visibility.allows(pkg)

    /** Throws the user's refusal for [pkg] unless it is allowed. Blocking (label lookup): call off the main thread. */
    fun require(pkg: String?) {
        if (pkg != null && !allows(pkg)) refuse(pkg)
    }

    /** Throws the refusal for the blocked [pkg] (worded by [AppAccess.require]), optionally after [prefix]. */
    fun refuse(pkg: String, prefix: String? = null): Nothing {
        val label = label(pkg)
        try {
            appAccess.require(pkg, label)
        } catch (e: ToolException) {
            throw if (prefix == null) e else ToolException("$prefix ${e.message}")
        }
        // AppAccess and the visibility rules agree by construction; this is only a safety net.
        throw ToolException(listOfNotNull(prefix, blockedText(label)).joinToString(" "))
    }

    /** User-visible app name (cached). Blocking on first use per package. */
    fun label(pkg: String): String = AppLabels.of(context, pkg)

    companion object {
        /** Visibility for the call [ctx]. Blocking PackageManager IPC at most once a minute: call off the main thread. */
        fun of(context: Context, ctx: CallContext): ScreenAccess {
            val access = ctx.appAccess
            val own = context.packageName
            val restricted = AppVisibility.isRestricted(access.mode, access.packages, access.neverAllow, own)
            val visibility = if (!restricted) {
                AppVisibility.UNRESTRICTED
            } else {
                AppVisibility(
                    restricted = true,
                    isAllowed = access::isAllowed,
                    exempt = SystemSurfaces.packages(context),
                    explicitlyBlocked = AppVisibility.explicitlyBlocked(access.mode, access.packages, access.neverAllow, own),
                )
            }
            return ScreenAccess(context.applicationContext, access, visibility)
        }

        private fun blockedText(label: String) =
            "$label is blocked for this agent by the user. Don't try to reach it another way; ask the user if it is needed."

        /** Why screenshots are refused while [overlay] shows, for agents with app restrictions. */
        fun overlayRefusal(overlay: SystemOverlay): String {
            val next = when (overlay) {
                SystemOverlay.LOCK_SCREEN -> "Ask the user to unlock the phone, or use notifications_list to read notifications."
                SystemOverlay.RECENTS -> "Leave it first (ui_global_action back or home)."
                else -> "Use notifications_list to read notifications (it leaves out blocked apps), or close it first with ui_global_action back."
            }
            return "Can't take a screenshot while ${overlay.description} is showing: it can show content from apps this agent " +
                "may not access. $next"
        }
    }
}
