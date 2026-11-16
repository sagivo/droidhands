package com.droidhands.android.ui.home

import com.droidhands.android.ui.activity.AuditPresentation
import com.droidhands.android.ui.activity.OutcomeCategory
import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.TokenUsage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Agent activity on the current local day, for the Home summary card. */
data class TodaySummary(val actions: Long, val agents: Int, val denied: Int, val errors: Int) {
    val isEmpty: Boolean get() = actions == 0L && denied == 0 && errors == 0

    companion object {
        private const val TOOLS_CALL = "tools/call"

        /**
         * Counts today's tool calls per agent from the audit log, and takes the larger of that and
         * the persistent usage counter (the log is a ring buffer and may have dropped older entries
         * of a busy day). Denied and failed calls come from the log.
         */
        fun compute(entries: List<AuditEntry>, usage: Map<String, TokenUsage>, now: Long, zone: ZoneId): TodaySummary {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val todayKey = today.toString()
            val perAgent = HashMap<String, Long>()
            var denied = 0
            var errors = 0
            for (entry in entries) {
                if (entry.method != TOOLS_CALL || !isOn(entry.timestamp, today, zone)) continue
                entry.tokenId?.let { perAgent.merge(it, 1L, Long::plus) }
                when (AuditPresentation.category(entry.outcome)) {
                    OutcomeCategory.DENIED -> denied++
                    OutcomeCategory.ERROR -> errors++
                    OutcomeCategory.SUCCESS -> Unit
                }
            }
            usage.forEach { (tokenId, u) ->
                val counted = u.perDay[todayKey] ?: 0L
                if (counted > (perAgent[tokenId] ?: 0L)) perAgent[tokenId] = counted
            }
            return TodaySummary(
                actions = perAgent.values.sum(),
                agents = perAgent.count { it.value > 0 },
                denied = denied,
                errors = errors,
            )
        }

        /** "23 actions by 2 agents · 1 denied", or a calm empty-state line. */
        fun text(summary: TodaySummary): String {
            if (summary.isEmpty) return "No agent activity today"
            val head = buildString {
                append(if (summary.actions == 1L) "1 action" else "${summary.actions} actions")
                if (summary.agents > 0) append(if (summary.agents == 1) " by 1 agent" else " by ${summary.agents} agents")
            }
            return listOfNotNull(
                head,
                summary.denied.takeIf { it > 0 }?.let { "$it denied" },
                summary.errors.takeIf { it > 0 }?.let { if (it == 1) "1 failed" else "$it failed" },
            ).joinToString(" · ")
        }

        private fun isOn(timestamp: Long, day: LocalDate, zone: ZoneId): Boolean =
            Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == day
    }
}
