package dev.phonebridge.core.audit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure counting rules behind [UsageTracker]. */
internal object UsageCounting {
    /** Days kept in [TokenUsage.perDay], today included. */
    const val DAYS_KEPT = 31

    /** Max distinct tool names per token (agents could otherwise grow the file with made-up names). */
    const val MAX_TOOLS = 200

    private const val MAX_TOOL_NAME = 64

    enum class Category { OK, ERROR, DENIED }

    /** Null for outcomes that are not counted (AUTH_FAILED). */
    fun category(outcome: AuditOutcome): Category? = when (outcome) {
        AuditOutcome.OK -> Category.OK
        AuditOutcome.TOOL_ERROR, AuditOutcome.TIMEOUT, AuditOutcome.EXCEPTION, AuditOutcome.UNKNOWN_TOOL -> Category.ERROR
        AuditOutcome.DENIED_SCOPE, AuditOutcome.RATE_LIMITED, AuditOutcome.PAUSED, AuditOutcome.DENIED_BY_USER,
        AuditOutcome.CONFIRM_TIMEOUT, AuditOutcome.DENIED_POLICY, AuditOutcome.DENIED_APP,
        -> Category.DENIED
        AuditOutcome.AUTH_FAILED -> null
    }

    /** Only authenticated `tools/call` entries count. */
    fun counts(entry: AuditEntry): Boolean =
        entry.method == "tools/call" && !entry.tokenId.isNullOrEmpty() && category(entry.outcome) != null

    /** Local date "yyyy-MM-dd" of [epochMillis] in [zone]. */
    fun day(epochMillis: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** Oldest day kept when today is [today]. */
    fun firstKeptDay(today: String): String = LocalDate.parse(today).minusDays((DAYS_KEPT - 1).toLong()).toString()

    /** [usage] plus [entry] (counted on local [day]), with perDay trimmed relative to [today]. */
    fun add(usage: TokenUsage, entry: AuditEntry, day: String, today: String): TokenUsage {
        val category = category(entry.outcome) ?: return usage
        val tool = entry.tool?.takeIf { entry.outcome != AuditOutcome.UNKNOWN_TOOL && it.isNotBlank() && it.length <= MAX_TOOL_NAME }
        val perTool = when {
            tool == null -> usage.perTool
            tool in usage.perTool || usage.perTool.size < MAX_TOOLS -> usage.perTool + (tool to (usage.perTool[tool] ?: 0L) + 1)
            else -> usage.perTool
        }
        val perDay = trimDays(usage.perDay + (day to (usage.perDay[day] ?: 0L) + 1), today)
        return usage.copy(
            totalCalls = usage.totalCalls + 1,
            okCalls = usage.okCalls + if (category == Category.OK) 1 else 0,
            errorCalls = usage.errorCalls + if (category == Category.ERROR) 1 else 0,
            deniedCalls = usage.deniedCalls + if (category == Category.DENIED) 1 else 0,
            lastCallAt = maxOf(usage.lastCallAt ?: entry.timestamp, entry.timestamp),
            perTool = perTool,
            perDay = perDay,
        )
    }

    /**
     * Keeps the [DAYS_KEPT] days ending [today] (ISO dates compare as strings), sorted; drops keys
     * that are not `yyyy-MM-dd` (a corrupt file). Days after today (a clock that moved backwards) are
     * kept: they age out once the clock catches up.
     */
    fun trimDays(perDay: Map<String, Long>, today: String): Map<String, Long> {
        val first = firstKeptDay(today)
        return perDay.filterKeys { it.length == 10 && DATE.matches(it) && it >= first }.toSortedMap()
    }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** [trimDays] for every token. */
    fun trimAll(usage: Map<String, TokenUsage>, today: String): Map<String, TokenUsage> =
        usage.mapValues { (_, u) -> u.copy(perDay = trimDays(u.perDay, today)) }
}
