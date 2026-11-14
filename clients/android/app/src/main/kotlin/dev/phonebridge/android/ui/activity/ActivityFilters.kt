package dev.phonebridge.android.ui.activity

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.auth.TokenRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the Activity list shows. [agent] is a token id, [ActivityFilters.NO_TOKEN] for requests
 * without a valid token (auth failures), or null for every agent.
 */
data class ActivityQuery(
    val agent: String? = null,
    val outcome: AuditFilter = AuditFilter.ALL,
    val search: String = "",
) {
    val isFiltered: Boolean get() = agent != null || outcome != AuditFilter.ALL || search.isNotBlank()
}

/** One entry of the agent dropdown; [agent] as in [ActivityQuery.agent]. */
data class AgentOption(val agent: String?, val label: String)

/** Counts for the summary line above the list. */
data class ActivitySummary(val total: Int, val ok: Int, val errors: Int, val denied: Int)

/** A row of the day-grouped list. */
sealed interface ActivityRow {
    val key: String

    data class DayHeader(val date: LocalDate, val count: Int) : ActivityRow {
        override val key get() = "day-$date"
    }

    data class Item(val entry: AuditEntry, override val key: String) : ActivityRow
}

/** Pure filtering, grouping and summaries for the Activity screen (unit-tested). */
object ActivityFilters {
    /** [ActivityQuery.agent] value selecting requests that carried no valid token. */
    const val NO_TOKEN = "~no-token"

    fun apply(entries: List<AuditEntry>, query: ActivityQuery): List<AuditEntry> {
        if (!query.isFiltered) return entries
        val needle = normalize(query.search)
        return entries.filter { entry ->
            matchesAgent(entry, query.agent) &&
                AuditPresentation.matches(entry, query.outcome) &&
                (needle.isEmpty() || normalize(entry.tool.orEmpty()).contains(needle) || normalize(entry.method).contains(needle))
        }
    }

    fun matchesAgent(entry: AuditEntry, agent: String?): Boolean = when (agent) {
        null -> true
        NO_TOKEN -> entry.tokenId == null
        else -> entry.tokenId == agent
    }

    /**
     * Dropdown options: all agents, every known token (current names, list order), tokens that only
     * appear in the log (deleted agents, with their logged name), then "Unknown callers" if any.
     */
    fun agentOptions(entries: List<AuditEntry>, tokens: List<TokenRecord>): List<AgentOption> = buildList {
        add(AgentOption(null, "All agents"))
        val known = tokens.mapTo(HashSet()) { it.id }
        tokens.forEach { add(AgentOption(it.id, it.name)) }
        val deleted = LinkedHashMap<String, String>()
        var anonymous = false
        entries.forEach { e ->
            val id = e.tokenId
            if (id == null) {
                anonymous = true
            } else if (id !in known && id !in deleted) {
                deleted[id] = e.tokenName?.takeIf { it.isNotBlank() }?.let { "$it (deleted)" } ?: "Deleted agent"
            }
        }
        deleted.forEach { (id, label) -> add(AgentOption(id, label)) }
        if (anonymous) add(AgentOption(NO_TOKEN, "Unknown callers"))
    }

    /** Label of the selected option, falling back to "All agents" for an id that vanished. */
    fun agentLabel(options: List<AgentOption>, agent: String?): String =
        options.firstOrNull { it.agent == agent }?.label ?: options.first().label

    /** [entries] (newest first) with a header before each local day. Keys are unique and stable. */
    fun rows(entries: List<AuditEntry>, zone: ZoneId): List<ActivityRow> {
        if (entries.isEmpty()) return emptyList()
        val keys = AuditPresentation.stableKeys(entries)
        val days = entries.map { dayOf(it.timestamp, zone) }
        val counts = days.groupingBy { it }.eachCount()
        val rows = ArrayList<ActivityRow>(entries.size + counts.size)
        var previous: LocalDate? = null
        entries.forEachIndexed { i, entry ->
            val day = days[i]
            if (day != previous) {
                rows += ActivityRow.DayHeader(day, counts.getValue(day))
                previous = day
            }
            rows += ActivityRow.Item(entry, keys[i])
        }
        return rows
    }

    /** "Today", "Yesterday", otherwise [formatDate] (locale-aware, from the UI). */
    fun dayLabel(date: LocalDate, today: LocalDate, formatDate: (LocalDate) -> String): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> formatDate(date)
    }

    fun summary(entries: List<AuditEntry>): ActivitySummary {
        var ok = 0
        var errors = 0
        var denied = 0
        entries.forEach {
            when (AuditPresentation.category(it.outcome)) {
                OutcomeCategory.SUCCESS -> ok++
                OutcomeCategory.ERROR -> errors++
                OutcomeCategory.DENIED -> denied++
            }
        }
        return ActivitySummary(entries.size, ok, errors, denied)
    }

    /** "48 requests · 40 OK · 5 errors · 3 denied" (zero counts left out). */
    fun summaryText(summary: ActivitySummary): String {
        if (summary.total == 0) return "No requests"
        return listOfNotNull(
            if (summary.total == 1) "1 request" else "${summary.total} requests",
            summary.ok.takeIf { it > 0 }?.let { "$it OK" },
            summary.errors.takeIf { it > 0 }?.let { if (it == 1) "1 error" else "$it errors" },
            summary.denied.takeIf { it > 0 }?.let { "$it denied" },
        ).joinToString(" · ")
    }

    fun dayOf(timestamp: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    /** Lowercase, with `_` and `-` read as spaces, so "whatsapp send" finds `whatsapp_send`. */
    private fun normalize(text: String): String =
        text.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ")
}
