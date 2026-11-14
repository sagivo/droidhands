package dev.phonebridge.android.ui.activity

import dev.phonebridge.android.ui.apps.ActionNames
import dev.phonebridge.android.ui.apps.ToolName
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome

/** Coarse outcome groups; each has its own chip color. */
enum class OutcomeCategory { SUCCESS, ERROR, DENIED }

/** Outcome filter chips on the Activity screen. */
enum class AuditFilter(val label: String) { ALL("All"), OK("OK"), ERRORS("Errors"), DENIED("Denied") }

/** Pure classification and labels for audit entries (unit-tested). */
object AuditPresentation {
    /**
     * SUCCESS: the call ran. ERROR: it was attempted but failed (tool error, crash, timeout, unknown
     * tool). DENIED: refused before running (auth, scope, rate limit, pause, user denial, no answer).
     */
    fun category(outcome: AuditOutcome): OutcomeCategory = when (outcome) {
        AuditOutcome.OK -> OutcomeCategory.SUCCESS
        AuditOutcome.TOOL_ERROR,
        AuditOutcome.UNKNOWN_TOOL,
        AuditOutcome.TIMEOUT,
        AuditOutcome.EXCEPTION,
        -> OutcomeCategory.ERROR
        AuditOutcome.DENIED_SCOPE,
        AuditOutcome.DENIED_POLICY,
        AuditOutcome.DENIED_APP,
        AuditOutcome.CANCELLED,
        AuditOutcome.RATE_LIMITED,
        AuditOutcome.PAUSED,
        AuditOutcome.DENIED_BY_USER,
        AuditOutcome.CONFIRM_TIMEOUT,
        AuditOutcome.AUTH_FAILED,
        -> OutcomeCategory.DENIED
    }

    fun label(outcome: AuditOutcome): String = when (outcome) {
        AuditOutcome.OK -> "OK"
        AuditOutcome.TOOL_ERROR -> "Tool error"
        AuditOutcome.UNKNOWN_TOOL -> "Unknown tool"
        AuditOutcome.DENIED_SCOPE -> "Not in scope"
        AuditOutcome.DENIED_POLICY -> "Blocked by rule"
        AuditOutcome.DENIED_APP -> "App blocked"
        AuditOutcome.CANCELLED -> "Stopped"
        AuditOutcome.RATE_LIMITED -> "Rate limited"
        AuditOutcome.PAUSED -> "Paused"
        AuditOutcome.DENIED_BY_USER -> "Denied by you"
        AuditOutcome.CONFIRM_TIMEOUT -> "No answer"
        AuditOutcome.AUTH_FAILED -> "Auth failed"
        AuditOutcome.TIMEOUT -> "Timed out"
        AuditOutcome.EXCEPTION -> "Internal error"
    }

    fun matches(entry: AuditEntry, filter: AuditFilter): Boolean = when (filter) {
        AuditFilter.ALL -> true
        AuditFilter.OK -> category(entry.outcome) == OutcomeCategory.SUCCESS
        AuditFilter.ERRORS -> category(entry.outcome) == OutcomeCategory.ERROR
        AuditFilter.DENIED -> category(entry.outcome) == OutcomeCategory.DENIED
    }

    fun filter(entries: List<AuditEntry>, filter: AuditFilter): List<AuditEntry> =
        if (filter == AuditFilter.ALL) entries else entries.filter { matches(it, filter) }

    fun counts(entries: List<AuditEntry>): Map<AuditFilter, Int> =
        AuditFilter.entries.associateWith { f -> entries.count { matches(it, f) } }

    /** Tool name for tool calls, otherwise the JSON-RPC method. */
    fun title(entry: AuditEntry): String = entry.tool?.takeIf { it.isNotBlank() } ?: entry.method

    /** [title] as people read it: app actions become "Spotify – Play music" ([ActionNames]). */
    fun displayTitle(entry: AuditEntry, names: ActionNames.Index = ActionNames.Index.EMPTY): ToolName =
        ActionNames.display(title(entry), names)

    fun tokenLabel(entry: AuditEntry): String = entry.tokenName?.takeIf { it.isNotBlank() }
        ?: entry.tokenId?.let { "Token $it" }
        ?: if (entry.outcome == AuditOutcome.AUTH_FAILED) "Unknown caller" else "No token"

    fun transportLabel(transport: String): String = when (transport.lowercase()) {
        "relay" -> "Relay"
        "local" -> "Local"
        else -> transport
    }

    /**
     * Unique, stable list keys for [entries] (newest first). Identical entries get an occurrence
     * suffix counted from the oldest, so keys don't shift when new entries are prepended.
     */
    fun stableKeys(entries: List<AuditEntry>): List<String> {
        val seen = HashMap<String, Int>()
        val keys = arrayOfNulls<String>(entries.size)
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            val base = "${e.timestamp}:${e.hashCode()}"
            val n = seen.merge(base, 1, Int::plus)!!
            keys[i] = if (n == 1) base else "$base#$n"
        }
        return keys.map { it!! }
    }
}
