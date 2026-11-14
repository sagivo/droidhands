package dev.phonebridge.android.ui.agents

import dev.phonebridge.android.ui.format.TimeFormat
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import java.time.LocalDate

/**
 * The access levels offered for an agent: a preset is its scopes plus its risk cap
 * ([TokenRecord.maxRisk]); [CUSTOM] is anything else.
 */
enum class AccessLevel(val label: String, val description: String) {
    READ_ONLY(
        "Read-only",
        "Can look but never act: reads messages, notifications, contacts, calendar, location and the screen. Tools " +
            "that change anything are blocked.",
    ),
    ASSISTANT(
        "Assistant",
        "Can also act: send messages, call, reply, create events, open apps and links, and change volume, alarms and " +
            "the clipboard. Sensitive actions ask you first.",
    ),
    FULL("Full control", "Everything, including tapping and typing in any app and raw intents."),
    CUSTOM("Custom", "You pick exactly which capabilities the agent gets."),
    ;

    /** The preset's scopes; null for [CUSTOM]. */
    val scopes: Set<String>?
        get() = when (this) {
            READ_ONLY -> Scopes.PRESET_READ_ONLY
            ASSISTANT -> Scopes.PRESET_ASSISTANT
            FULL -> Scopes.PRESET_FULL
            CUSTOM -> null
        }

    /** The preset's risk cap (Read-only: READ); null for no cap, and for [CUSTOM]. */
    val maxRisk: Risk? get() = if (this == CUSTOM) null else TokenPresentation.presetCap(label)

    companion object {
        /**
         * The preset whose scopes and cap equal [scopes] and [maxRisk] exactly, otherwise [CUSTOM]: the
         * Read-only scopes without the Read-only cap can still act, so they are not "Read-only".
         */
        fun of(scopes: Set<String>, maxRisk: Risk?): AccessLevel =
            entries.firstOrNull { it != CUSTOM && it.scopes == scopes && it.maxRisk == maxRisk } ?: CUSTOM

        fun of(record: TokenRecord): AccessLevel = of(record.scopes, record.maxRisk)
    }
}

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

    /** Short list of the advanced restrictions in force, e.g. ["Only 3 apps", "2 tool rules", "08:00–23:00"]. */
    fun restrictions(record: TokenRecord, formatHours: (ActiveHours) -> String): List<String> = buildList {
        val apps = record.appPolicy.packages.size
        when (record.appPolicy.mode) {
            AppAccessMode.ALL -> Unit
            AppAccessMode.ALLOWLIST -> add(if (apps == 1) "Only 1 app" else "Only $apps apps")
            AppAccessMode.BLOCKLIST -> add(if (apps == 1) "1 app blocked" else "$apps apps blocked")
        }
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
