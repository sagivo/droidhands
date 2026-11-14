package dev.phonebridge.core.policy

import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Tool
import java.time.ZoneId

data class RateLimits(
    val callsPerMinute: Int = 120,
    val sensitiveCallsPerMinute: Int = 20,
)

sealed interface PolicyDecision {
    /** Run without asking. */
    data object Allow : PolicyDecision

    /** Run only after user approval. */
    data object RequireConfirmation : PolicyDecision

    /**
     * Refuse; [reason] is shown to the agent. [rateLimited] = a rate limit (audited RATE_LIMITED);
     * otherwise the token's own rules refused it (a BLOCK tool rule, a tool above the token's max risk, or
     * outside active hours; audited DENIED_POLICY).
     */
    data class Deny(val reason: String, val rateLimited: Boolean = false) : PolicyDecision
}

/**
 * Scope check is done by McpServer before this. PolicyEngine applies, in this order:
 * 1. the token's per-tool rule BLOCK ([ToolRule.BLOCK]) → Deny;
 * 2. the token's [TokenRecord.maxRisk]: a tool of higher risk → Deny, whatever its tool rule says;
 * 3. the token's [TokenRecord.activeHours], evaluated at [clock] in [zone] (phone-local time) → Deny
 *    outside the window (overnight windows such as 22:00-06:00 work);
 * 4. per-token sliding-window rate limits (recording the call when allowed);
 * 5. confirmation: rule ASK → RequireConfirmation (even for `READ` tools), rule ALLOW → Allow,
 *    no rule → the token's ConfirmPolicy vs the tool's Risk.
 *
 * Every call counts toward the token's total limit; `SENSITIVE` calls also count toward the
 * sensitive limit (ALLOW rules are still rate-limited). Denied calls are not recorded, so an agent
 * that backs off recovers as the window slides. Thread-safe. Memory is bounded: each window holds
 * at most its limit of timestamps, and tokens idle for a whole window are dropped.
 */
class PolicyEngine(
    private val limits: RateLimits = RateLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Zone for [TokenRecord.activeHours] (the phone's local time). */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private class Windows(val all: ArrayDeque<Long> = ArrayDeque(), val sensitive: ArrayDeque<Long> = ArrayDeque())

    private val lock = Any()
    private val windows = HashMap<String, Windows>()
    private var lastSweep = Long.MIN_VALUE

    init {
        require(limits.callsPerMinute > 0) { "callsPerMinute must be positive" }
        require(limits.sensitiveCallsPerMinute > 0) { "sensitiveCallsPerMinute must be positive" }
    }

    fun evaluate(token: TokenRecord, tool: Tool): PolicyDecision {
        val rule = token.toolRules[tool.name]
        if (rule == ToolRule.BLOCK) return PolicyDecision.Deny(blockedMessage(tool.name))
        token.maxRisk?.let { cap -> if (exceedsMaxRisk(cap, tool.risk)) return PolicyDecision.Deny(aboveMaxRiskMessage(tool.name, cap)) }
        token.activeHours?.let { hours -> outsideActiveHours(hours, clock())?.let { return it } }

        val sensitive = tool.risk == Risk.SENSITIVE
        synchronized(lock) {
            val now = clock()
            sweep(now)
            val w = windows.getOrPut(token.id) { Windows() }
            prune(w.all, now)
            prune(w.sensitive, now)
            if (w.all.size >= limits.callsPerMinute) {
                return deny("at most ${limits.callsPerMinute} tool calls per minute", w.all, now)
            }
            if (sensitive && w.sensitive.size >= limits.sensitiveCallsPerMinute) {
                return deny("at most ${limits.sensitiveCallsPerMinute} sensitive actions per minute", w.sensitive, now)
            }
            w.all.addLast(now)
            if (sensitive) w.sensitive.addLast(now)
        }
        return if (requiresConfirmation(token.confirmPolicy, tool.risk, rule)) PolicyDecision.RequireConfirmation else PolicyDecision.Allow
    }

    /** Null when [now] is inside [hours]; otherwise the denial explaining the window. */
    private fun outsideActiveHours(hours: ActiveHours, now: Long): PolicyDecision.Deny? {
        if (!ActiveWindow.isValid(hours)) return PolicyDecision.Deny(INVALID_HOURS_MESSAGE)
        val zoneId = try {
            zone()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
        val minute = ActiveWindow.minuteOfDay(now, zoneId)
        if (ActiveWindow.contains(hours, minute)) return null
        return PolicyDecision.Deny(outsideHoursMessage(hours, minute))
    }

    /** Number of tokens with live rate-limit state (for tests and diagnostics). */
    internal fun trackedTokens(): Int = synchronized(lock) { windows.size }

    private fun deny(limit: String, window: ArrayDeque<Long>, now: Long): PolicyDecision.Deny {
        val retryAfter = retryAfterSeconds(window.first(), now)
        return PolicyDecision.Deny(
            "Rate limit reached for this token ($limit). Slow down and retry in $retryAfter s.",
            rateLimited = true,
        )
    }

    /**
     * Drops timestamps outside the window. Timestamps in the future (the wall clock moved
     * backwards) are dropped too, so a clock change can never lock a token out for long.
     */
    private fun prune(window: ArrayDeque<Long>, now: Long) {
        while (window.isNotEmpty() && (now - window.first() >= WINDOW_MS || window.first() > now)) window.removeFirst()
    }

    /** At most once per window, forgets tokens without calls in the last window. */
    private fun sweep(now: Long) {
        if (lastSweep != Long.MIN_VALUE && now - lastSweep in 0 until WINDOW_MS) return
        lastSweep = now
        val it = windows.values.iterator()
        while (it.hasNext()) {
            val w = it.next()
            prune(w.all, now)
            prune(w.sensitive, now)
            if (w.all.isEmpty() && w.sensitive.isEmpty()) it.remove()
        }
    }

    companion object {
        /** Sliding window length. */
        const val WINDOW_MS = 60_000L

        /** Which calls need on-phone approval under [policy]. */
        fun requiresConfirmation(policy: ConfirmPolicy, risk: Risk): Boolean = when (policy) {
            ConfirmPolicy.NEVER -> false
            ConfirmPolicy.SENSITIVE -> risk == Risk.SENSITIVE
            ConfirmPolicy.ALL_ACTIONS -> risk != Risk.READ
        }

        /**
         * Whether a call needs on-phone approval for a token with [policy] and an optional per-tool
         * [rule]: ASK always, ALLOW never, otherwise [requiresConfirmation]. BLOCKed calls never run.
         */
        fun requiresConfirmation(policy: ConfirmPolicy, risk: Risk, rule: ToolRule?): Boolean = when (rule) {
            ToolRule.ASK -> true
            ToolRule.ALLOW, ToolRule.BLOCK -> false
            null -> requiresConfirmation(policy, risk)
        }

        /** True when a token capped at [maxRisk] (null = no cap) may not use a tool of [risk]. */
        fun exceedsMaxRisk(maxRisk: Risk?, risk: Risk): Boolean = maxRisk != null && risk > maxRisk

        internal const val INVALID_HOURS_MESSAGE =
            "This agent's active hours in PhoneBridge are not a valid time window, so it may not act right now. " +
                "Ask the user to fix or turn off this agent's active hours in the PhoneBridge app."

        internal fun blockedMessage(toolName: String) =
            "The tool '$toolName' is blocked for this agent by the user. Do not try to work around it (for example " +
                "with other tools). If the task needs it, ask the user to change this agent's tool rules in the PhoneBridge app."

        internal fun aboveMaxRiskMessage(toolName: String, cap: Risk): String {
            val limit = when (cap) {
                Risk.READ -> "read-only tools (it may look but not change anything on the phone)"
                Risk.ACTION -> "tools that do not act toward other people (no sending, calling or other hard-to-undo actions)"
                Risk.SENSITIVE -> "all tools"
            }
            return "The tool '$toolName' is not available to this agent: the user limited it to $limit. Do not try to work " +
                "around it (for example with other tools). If the task needs it, ask the user to change this agent's access " +
                "level in the PhoneBridge app."
        }

        internal fun outsideHoursMessage(hours: ActiveHours, nowMinute: Int) =
            "This agent may only act between ${ActiveWindow.format(hours.startMinute)} and ${ActiveWindow.format(hours.endMinute)} " +
                "(the phone's local time; it is ${ActiveWindow.format(nowMinute)} there now). Try again within that window, " +
                "or ask the user to change this agent's active hours in the PhoneBridge app."

        /** Seconds until the oldest call leaves the window, clamped to 1..60. */
        internal fun retryAfterSeconds(oldest: Long, now: Long): Long {
            val ms = oldest + WINDOW_MS - now
            return ((ms + 999) / 1000).coerceIn(1, WINDOW_MS / 1000)
        }
    }
}
