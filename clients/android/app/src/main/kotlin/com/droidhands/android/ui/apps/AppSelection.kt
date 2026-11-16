package com.droidhands.android.ui.apps

import java.text.Normalizer

/** A launchable app as shown in the pickers. */
data class AppInfo(val packageName: String, val label: String)

/** One picker row. [locked] rows can't be toggled (never-allow list); [installed] is false for stale selections. */
data class AppRow(val app: AppInfo, val selected: Boolean, val locked: Boolean, val installed: Boolean = true)

/** A titled group of rows ([title] null for a single untitled list). */
data class AppSection(val key: String, val title: String?, val rows: List<AppRow>)

/** Pure search and grouping for the app pickers (unit-tested). */
object AppSelection {
    /**
     * Sections for the picker. Without a query: "Suggested" (if any), then "All apps", then
     * "Not installed" for selected packages that are gone (so they can be removed). Rows never move
     * between sections when toggled, so the list doesn't jump under the user's finger.
     * With a query: one "Results" section matching label or package name.
     *
     * @param lockedSelected how locked rows display: checked (blocklists) or unchecked (allowlists).
     */
    fun sections(
        apps: List<AppInfo>,
        selected: Set<String>,
        locked: Set<String> = emptySet(),
        suggested: Set<String> = emptySet(),
        query: String = "",
        lockedSelected: Boolean = false,
    ): List<AppSection> {
        fun row(app: AppInfo, installed: Boolean = true): AppRow {
            val isLocked = app.packageName in locked
            return AppRow(app, selected = if (isLocked) lockedSelected else app.packageName in selected, locked = isLocked, installed = installed)
        }
        val installed = apps.mapTo(HashSet()) { it.packageName }
        val missing = selected.filter { it !in installed }.sorted().map { AppInfo(it, it) }
        val needle = fold(query)
        if (needle.isNotEmpty()) {
            val hits = apps.filter { matches(it, needle) }.map { row(it) } +
                missing.filter { matches(it, needle) }.map { row(it, installed = false) }
            return listOf(AppSection("results", "Results", hits))
        }
        val (suggestedApps, others) = apps.partition { it.packageName in suggested }
        return buildList {
            if (suggestedApps.isNotEmpty()) add(AppSection("suggested", "Suggested", suggestedApps.map { row(it) }))
            add(AppSection("all", if (suggestedApps.isEmpty()) null else "All apps", others.map { row(it) }))
            if (missing.isNotEmpty()) add(AppSection("missing", "Not installed", missing.map { row(it, installed = false) }))
        }
    }

    /** Label (accent- and case-insensitive) or package name contains [query]. */
    fun matches(app: AppInfo, query: String): Boolean {
        val needle = fold(query)
        return needle.isEmpty() || fold(app.label).contains(needle) || app.packageName.lowercase().contains(needle)
    }

    /** Labels for [packages] (falls back to the package name), sorted, for summaries like "WhatsApp, Gmail +2". */
    fun summary(packages: Set<String>, apps: List<AppInfo>, max: Int = 3): String {
        if (packages.isEmpty()) return "None"
        val labels = apps.associate { it.packageName to it.label }
        val names = packages.map { labels[it] ?: it }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val shown = names.take(max).joinToString(", ")
        return if (names.size > max) "$shown +${names.size - max}" else shown
    }

    /** Lowercase without diacritics ("Café" → "cafe"), for search. */
    fun fold(text: String): String =
        Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

    private val DIACRITICS = Regex("\\p{Mn}+")
}
