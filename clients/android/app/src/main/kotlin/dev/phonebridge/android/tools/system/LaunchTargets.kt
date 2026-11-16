package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException

/** An activity that can handle an intent, as PackageManager reports it (Android-free copy). */
data class LaunchHandler(
    val packageName: String,
    /** Fully qualified activity class, when known. */
    val activityName: String? = null,
    /** User-visible app label (falls back to the package name). */
    val label: String = packageName,
)

/** What to do with an implicit intent under the agent's app access (see [LaunchTargetPolicy.decide]). */
sealed interface LaunchDecision {
    /** Start the intent as built. [target] is the app that opens it, or null when Android's chooser asks the user. */
    data class Proceed(val target: LaunchHandler?) : LaunchDecision

    /** Several apps could open it but only [target] is allowed: restrict the intent to that package. */
    data class Pin(val target: LaunchHandler) : LaunchDecision

    /** Refuse: every app that could open it ([handlers]) is blocked; empty when the handlers could not be checked. */
    data class Blocked(val handlers: List<LaunchHandler>) : LaunchDecision

    /** Refuse: blocked apps compete with several [allowed] ones; the agent must name one with `package`. */
    data class Choose(val allowed: List<LaunchHandler>) : LaunchDecision
}

/**
 * App-access policy for tools that start activities from implicit intents (`url_open`,
 * `intent_start`; docs/SECURITY.md "Advanced controls"). Pure: the caller resolves the intent with
 * PackageManager and applies the decision.
 *
 * When Android would show its app chooser (ResolverActivity) and some candidate apps are blocked,
 * letting the chooser appear is not safe: it lists the blocked apps too, and an agent with screen
 * control could tap one. So the intent is pinned to the single allowed app, or refused with the
 * allowed alternatives so the agent can pass `package` explicitly.
 */
object LaunchTargetPolicy {
    /** Packages that host Android's "Open with" / share chooser rather than handling intents themselves. */
    private val RESOLVER_PACKAGES = setOf("android", "com.android.intentresolver")
    private val RESOLVER_CLASS_SUFFIXES = listOf("ResolverActivity", "ChooserActivity")

    /** Longest list of apps named in an error message. */
    private const val MAX_LISTED = 8

    /**
     * True if [resolved] (from `resolveActivity`) is the system chooser rather than a real handler:
     * a known resolver package or class, or an activity that is not one of the [candidates]
     * (`queryIntentActivities`) when there are several of them.
     */
    fun isChooser(resolved: LaunchHandler, candidates: List<LaunchHandler>): Boolean {
        if (resolved.packageName in RESOLVER_PACKAGES) return true
        val cls = resolved.activityName
        if (cls != null && RESOLVER_CLASS_SUFFIXES.any { cls.endsWith(it) }) return true
        return candidates.size > 1 && candidates.none { it.packageName == resolved.packageName }
    }

    /**
     * Decides how to start an implicit intent that resolved to [resolved], given every activity
     * that matches it ([candidates]) and whether the agent may use a package ([isAllowed]).
     */
    fun decide(resolved: LaunchHandler, candidates: List<LaunchHandler>, isAllowed: (String) -> Boolean): LaunchDecision {
        if (!isChooser(resolved, candidates)) {
            return if (isAllowed(resolved.packageName)) LaunchDecision.Proceed(resolved) else LaunchDecision.Blocked(listOf(resolved))
        }
        val apps = candidates.filterNot { it.packageName in RESOLVER_PACKAGES }.distinctBy { it.packageName }
        if (apps.isEmpty()) return LaunchDecision.Blocked(emptyList())
        val allowed = apps.filter { isAllowed(it.packageName) }
        return when {
            allowed.size == apps.size -> LaunchDecision.Proceed(null)
            allowed.isEmpty() -> LaunchDecision.Blocked(apps)
            allowed.size == 1 -> LaunchDecision.Pin(allowed.single())
            else -> LaunchDecision.Choose(allowed)
        }
    }

    /** Agent-facing error for [LaunchDecision.Blocked] with zero or several handlers (one handler: use AppAccess.require). */
    fun blockedMessage(handlers: List<LaunchHandler>, what: String): String =
        if (handlers.isEmpty()) {
            "Android would ask which app should open $what, and PhoneBridge could not check those apps against this agent's " +
                "app access, so nothing was opened. Pass 'package' with the app to use (apps_list shows the apps you may use)."
        } else {
            "This agent isn't allowed to use any app that can open $what (${list(handlers)}), so nothing was opened. " +
                "Don't try to work around this. If it is needed, tell the user: they can allow one of these apps in " +
                "PhoneBridge > Agents > (this agent) > Access."
        }

    /** Agent-facing error for [LaunchDecision.Choose]. */
    fun chooseMessage(allowed: List<LaunchHandler>, what: String): String =
        "Several apps can open $what, and some of them are blocked for this agent, so PhoneBridge won't show Android's " +
            "app chooser. Call again with 'package' set to one of: ${list(allowed)}."

    /** Throws the matching [ToolException] for a refusing decision; returns normally for Proceed/Pin. */
    fun requireLaunchable(decision: LaunchDecision, what: String) {
        when (decision) {
            is LaunchDecision.Blocked -> throw ToolException(blockedMessage(decision.handlers, what))
            is LaunchDecision.Choose -> throw ToolException(chooseMessage(decision.allowed, what))
            is LaunchDecision.Proceed, is LaunchDecision.Pin -> Unit
        }
    }

    private fun list(handlers: List<LaunchHandler>): String =
        handlers.take(MAX_LISTED).joinToString("; ") { if (it.label == it.packageName) it.packageName else "${it.label} (${it.packageName})" } +
            if (handlers.size > MAX_LISTED) "; and ${handlers.size - MAX_LISTED} more" else ""
}
