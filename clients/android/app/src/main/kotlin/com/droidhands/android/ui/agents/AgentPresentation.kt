package com.droidhands.android.ui.agents

import com.droidhands.android.ui.format.TimeFormat
import com.droidhands.android.ui.tokens.TokenPresentation
import com.droidhands.android.ui.tokens.TokenState
import com.droidhands.core.audit.TokenUsage
import com.droidhands.core.auth.ActiveHours
import com.droidhands.core.auth.TokenRecord
import java.time.LocalDate

/** Pure text and ordering helpers for the Agents screens (unit-tested). */
object AgentPresentation {
    /** [TokenRecord.origin] of agents connected through one-tap pairing (docs/PROTOCOL.md §5). */
    const val ORIGIN_PAIRING = "pairing"

    fun isPaired(record: TokenRecord): Boolean = record.origin == ORIGIN_PAIRING

    /** Where the agent connected from: "claude.ai", or "Token" for manually created tokens. */
    fun clientLabel(record: TokenRecord): String =
        record.clientHost?.takeIf { it.isNotBlank() } ?: if (isPaired(record)) "Connected agent" else "Token"

    /** Most recent sign of life from the token record or the usage counters, or null if never used. */
    fun lastActive(record: TokenRecord, usage: TokenUsage?): Long? =
        listOfNotNull(record.lastUsedAt, usage?.lastCallAt).maxOrNull()

    fun lastActiveText(lastActive: Long?, now: Long): String =
        if (lastActive == null) "Never used" else "Active ${TimeFormat.relative(lastActive, now)}"

    /** Calls on [today] (a local date) according to the per-day usage counters. */
    fun callsOn(usage: TokenUsage?, today: LocalDate): Long = usage?.perDay?.get(today.toString()) ?: 0L

    fun callsTodayText(calls: Long): String = when (calls) {
        0L -> "No calls today"
        1L -> "1 call today"
        else -> "$calls calls today"
    }

    /**
     * Active agents first (most recently active first, then newest), then expired, then revoked.
     * Stable for equal keys, so the list doesn't jump around.
     */
    fun sorted(records: List<TokenRecord>, usage: Map<String, TokenUsage>, now: Long): List<TokenRecord> =
        records.sortedWith(
            compareBy<TokenRecord> { stateRank(TokenPresentation.state(it, now)) }
                .thenByDescending { lastActive(it, usage[it.id]) ?: Long.MIN_VALUE }
                .thenByDescending { it.createdAt },
        )

    /**
     * Short list of the advanced restrictions in force beyond the grant (whose apps and abilities the access
     * summary shows), e.g. ["2 tool rules", "08:00–23:00"].
     */
    fun restrictions(record: TokenRecord, formatHours: (ActiveHours) -> String): List<String> = buildList {
        val rules = record.toolRules.size
        if (rules > 0) add(if (rules == 1) "1 tool rule" else "$rules tool rules")
        record.activeHours?.let { add(formatHours(it)) }
    }

    fun stateLabel(state: TokenState): String = when (state) {
        TokenState.ACTIVE -> "Active"
        TokenState.REVOKED -> "Revoked"
        TokenState.EXPIRED -> "Expired"
    }

    /** "Revoked 3 agents" style confirmation for [TokenManager.revokeAll]. */
    fun revokedAllText(count: Int): String = when (count) {
        0 -> "No active agents to revoke"
        1 -> "Revoked 1 agent"
        else -> "Revoked $count agents"
    }

    /** Number of agents that can currently act (for the "Revoke all" confirmation). */
    fun activeCount(records: List<TokenRecord>, now: Long): Int =
        records.count { TokenPresentation.state(it, now) == TokenState.ACTIVE }

    private fun stateRank(state: TokenState): Int = when (state) {
        TokenState.ACTIVE -> 0
        TokenState.EXPIRED -> 1
        TokenState.REVOKED -> 2
    }
}
