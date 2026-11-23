package com.droidhands.android.ui.activity

import com.droidhands.android.ui.apps.ActionNames
import com.droidhands.android.ui.apps.ToolName
import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Coarse outcome groups; each has its own chip color. */
enum class OutcomeCategory { SUCCESS, ERROR, DENIED }

/** One logged argument as shown: "Duration" / "60 min". */
data class ArgumentRow(val label: String, val value: String)

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
        AuditOutcome.TOOL_ERROR -> "Failed"
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

    /**
     * The logged arguments as labelled rows people can read: names humanized ("Duration" for
     * `duration_minutes`, with "60 min"), ISO dates and times through [formatDateTime] / [formatDate]
     * (offsets converted to [zone]), booleans as Yes/No, nested values as compact JSON. Null when there
     * are none or the preview isn't a whole JSON object (it is cut at 300 characters).
     */
    fun argumentRows(
        argsPreview: String?,
        zone: ZoneId,
        formatDateTime: (ZonedDateTime) -> String,
        formatDate: (LocalDate) -> String,
    ): List<ArgumentRow>? {
        val json = argsPreview?.takeIf { it.isNotBlank() } ?: return null
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        if (obj.isEmpty()) return null
        return obj.map { (key, value) ->
            val unit = UNITS.firstOrNull { key.endsWith(it.first) }
            val name = unit?.let { key.removeSuffix(it.first) }?.takeIf { it.isNotEmpty() } ?: key
            val text = when {
                value is JsonNull -> "–"
                value is JsonPrimitive && value.isString -> dateTime(value.content, zone, formatDateTime, formatDate) ?: value.content
                value is JsonPrimitive && value.booleanOrNull != null -> if (value.boolean) "Yes" else "No"
                value is JsonPrimitive -> unit?.let { "${value.content} ${it.second}" } ?: value.content
                else -> value.toString()
            }
            ArgumentRow(ActionNames.humanize(name), text)
        }
    }

    private val UNITS = listOf("_minutes" to "min", "_seconds" to "s", "_ms" to "ms")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val ISO_DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*""")

    private fun dateTime(text: String, zone: ZoneId, formatDateTime: (ZonedDateTime) -> String, formatDate: (LocalDate) -> String): String? =
        runCatching {
            when {
                ISO_DATE.matches(text) -> formatDate(LocalDate.parse(text))
                !ISO_DATE_TIME.matches(text) -> null
                else -> formatDateTime(
                    runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone) }.getOrNull()
                        ?: LocalDateTime.parse(text).atZone(zone),
                )
            }
        }.getOrNull()

    /** Plain-text account of [entry] for the clipboard (bug reports, asking the agent to retry). */
    fun report(entry: AuditEntry, names: ActionNames.Index, time: String): String = buildString {
        appendLine(displayTitle(entry, names).title)
        appendLine("${label(entry.outcome)} · $time · ${tokenLabel(entry)}")
        entry.detail?.let { appendLine(it) }
        appendLine("Tool: ${title(entry)} (${entry.method}, ${transportLabel(entry.transport)}, ${entry.durationMs} ms)")
        entry.argsPreview?.let { append("Arguments: ").appendLine(it) }
    }.trimEnd()

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
