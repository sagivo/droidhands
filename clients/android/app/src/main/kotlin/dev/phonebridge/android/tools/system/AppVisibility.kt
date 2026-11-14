package dev.phonebridge.android.tools.system

/** Result of resolving an app name for an agent whose app access may hide some apps. */
sealed interface AppPick {
    data class Found(val app: AppEntry) : AppPick

    /** Several allowed apps match equally well; [someBlocked] says blocked apps matched too (not named). */
    data class Ambiguous(val candidates: List<AppEntry>, val someBlocked: Boolean) : AppPick

    /** No allowed app matches; [suggestions] are allowed apps only. */
    data class NotFound(val suggestions: List<AppEntry>) : AppPick

    /** The best match is an app the agent may not use; the caller refuses with the app's name. */
    data class Blocked(val app: AppEntry) : AppPick
}

/**
 * App-access rules for `apps_list` and `app_open` (pure; docs/SECURITY.md "Advanced controls").
 * Blocked apps are left out of listings, candidate lists and suggestions. When the name the agent
 * asked for clearly means a blocked app, the refusal names it, so the agent stops looking instead
 * of opening a lookalike.
 */
object AppVisibility {
    /** Apps the agent may see, in the given order. */
    fun visible(apps: List<AppEntry>, isAllowed: (String) -> Boolean): List<AppEntry> = apps.filter { isAllowed(it.packageName) }

    /**
     * Matches [query] against every installed app with [AppMatcher], then applies access: a unique
     * blocked winner is [AppPick.Blocked]; blocked apps tied with allowed ones are dropped from the
     * candidates (and flagged), never silently swapped for the one remaining allowed app.
     */
    fun pick(query: String, apps: List<AppEntry>, isAllowed: (String) -> Boolean): AppPick =
        when (val match = AppMatcher.match(query, apps)) {
            is AppMatch.Found -> if (isAllowed(match.app.packageName)) AppPick.Found(match.app) else AppPick.Blocked(match.app)
            is AppMatch.Ambiguous -> {
                val allowed = match.candidates.filter { isAllowed(it.packageName) }
                when {
                    allowed.isEmpty() -> AppPick.Blocked(match.candidates.first())
                    else -> AppPick.Ambiguous(allowed, someBlocked = allowed.size < match.candidates.size)
                }
            }
            is AppMatch.NotFound -> AppPick.NotFound(match.suggestions.filter { isAllowed(it.packageName) })
        }
}
