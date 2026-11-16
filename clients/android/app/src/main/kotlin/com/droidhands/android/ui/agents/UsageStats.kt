package com.droidhands.android.ui.agents

import com.droidhands.core.audit.TokenUsage
import java.time.LocalDate

/** Calls on one local day. */
data class DayCount(val date: LocalDate, val count: Long)

/** One tool and how often the agent called it. */
data class ToolCount(val tool: String, val count: Long)

/** Pure aggregation of [TokenUsage] for the agent detail page (unit-tested). */
object UsageStats {
    const val CHART_DAYS = 14

    /**
     * The last [days] local days ending with [today] (oldest first), filling days without calls with 0.
     * Keys of [perDay] that don't parse as `yyyy-MM-dd` are ignored.
     */
    fun lastDays(perDay: Map<String, Long>, today: LocalDate, days: Int = CHART_DAYS): List<DayCount> {
        require(days > 0) { "days must be positive" }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayCount(date, (perDay[date.toString()] ?: 0L).coerceAtLeast(0L))
        }
    }

    /** The [limit] most-called tools, ties broken by name so the order is stable. */
    fun topTools(perTool: Map<String, Long>, limit: Int = 5): List<ToolCount> =
        perTool.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { ToolCount(it.key, it.value) }

    /**
     * Top of the chart's value axis: the smallest "nice" number (1, 2, 5 × 10^n) at or above [max],
     * so the gridline label is a round number. 0 calls still gets a scale of 1.
     */
    fun niceCeiling(max: Long): Long {
        if (max <= 1) return 1
        var magnitude = 1L
        while (magnitude * 10 <= max) magnitude *= 10
        for (step in longArrayOf(1, 2, 5, 10)) {
            val candidate = step * magnitude
            if (candidate >= max) return candidate
        }
        return 10 * magnitude
    }

    /** "1.2K" from 1,000 up, plain digits below. */
    fun compact(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> trimmed(value / 1_000.0) + "K"
        else -> trimmed(value / 1_000_000.0) + "M"
    }

    /**
     * Screen-reader summary of the chart, e.g. "Calls per day, last 14 days: 87 in total.
     * Busiest day Nov 12 with 23 calls. Today 4 calls."
     */
    fun chartDescription(days: List<DayCount>, formatDay: (LocalDate) -> String): String {
        if (days.isEmpty()) return "No usage data."
        val total = days.sumOf { it.count }
        val header = "Calls per day, last ${days.size} days: "
        if (total == 0L) return header + "no calls."
        val peak = days.maxBy { it.count }
        val today = days.last()
        return header + "${calls(total)} in total. Busiest day ${formatDay(peak.date)} with ${calls(peak.count)}. " +
            "Today ${calls(today.count)}."
    }

    /** Share of [part] in [total] as a whole percentage, for the totals row ("92 %"). */
    fun percent(part: Long, total: Long): Int = if (total <= 0) 0 else ((part * 100.0) / total).toInt().coerceIn(0, 100)

    fun calls(n: Long): String = if (n == 1L) "1 call" else "$n calls"

    private fun trimmed(v: Double): String {
        val oneDecimal = Math.round(v * 10) / 10.0
        return if (oneDecimal % 1.0 == 0.0) oneDecimal.toLong().toString() else oneDecimal.toString()
    }
}
