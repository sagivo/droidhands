package dev.phonebridge.android.notify

import java.util.Locale

/** Arguments of `notifications_list`, already validated. */
data class NotificationQuery(
    /** Exact package name, or null for all apps. */
    val packageName: String? = null,
    /** Case-insensitive substring of title, text, or message sender/text. */
    val query: String? = null,
    val includeOngoing: Boolean = false,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 100
    }
}

/** Result page: the newest [items] of [totalMatches] matching notifications. */
data class FilteredNotifications(val items: List<NotificationInfo>, val totalMatches: Int) {
    val omitted: Int get() = totalMatches - items.size
}

/** Default visibility rules and argument filters for `notifications_list` (pure). */
object NotificationFilter {
    /**
     * Applies, in order: drop [ownPackage] (bridge status and approval prompts must never be
     * visible to or actionable by agents), drop group summaries that have other notifications in
     * their group (the children carry the content), drop ongoing unless requested, then the
     * package and query filters. Sorted newest first, capped at [NotificationQuery.limit].
     */
    fun apply(all: List<NotificationInfo>, query: NotificationQuery, ownPackage: String): FilteredNotifications {
        val foreign = all.filter { it.packageName != ownPackage }
        val groupSizes = foreign.groupingBy { it.groupKey }.eachCount()
        val needle = query.query?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        val matches = foreign
            .asSequence()
            .filterNot { it.isGroupSummary && (groupSizes[it.groupKey] ?: 0) > 1 }
            .filter { query.includeOngoing || !it.ongoing }
            .filter { query.packageName == null || it.packageName == query.packageName }
            .filter { needle == null || matchesQuery(it, needle) }
            .sortedWith(compareByDescending<NotificationInfo> { it.postTime }.thenBy { it.key })
            .toList()
        val limit = query.limit.coerceIn(1, NotificationQuery.MAX_LIMIT)
        return FilteredNotifications(matches.take(limit), matches.size)
    }

    /** [needle] must already be lowercase. */
    internal fun matchesQuery(info: NotificationInfo, needle: String): Boolean {
        fun String?.has() = this != null && lowercase(Locale.ROOT).contains(needle)
        return info.title.has() || info.text.has() || info.messages.any { it.text.has() || it.sender.has() }
    }
}
