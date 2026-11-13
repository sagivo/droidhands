package dev.phonebridge.core.policy

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Tool

data class RateLimits(
    val callsPerMinute: Int = 120,
    val sensitiveCallsPerMinute: Int = 20,
)

sealed interface PolicyDecision {
    /** Run without asking. */
    data object Allow : PolicyDecision

    /** Run only after user approval. */
    data object RequireConfirmation : PolicyDecision

    /** Refuse; [reason] is shown to the agent. */
    data class Deny(val reason: String, val rateLimited: Boolean = false) : PolicyDecision
}

/**
 * Scope check is done by McpServer before this. PolicyEngine applies per-token sliding-window rate
 * limits (recording the call when allowed) and the token's ConfirmPolicy vs the tool's Risk.
 *
 * Every call counts toward the token's total limit; `SENSITIVE` calls also count toward the
 * sensitive limit. Denied calls are not recorded, so an agent that backs off recovers as the
 * window slides. Thread-safe. Memory is bounded: each window holds at most its limit of
 * timestamps, and tokens idle for a whole window are dropped.
 */
class PolicyEngine(
    private val limits: RateLimits = RateLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Zone for TokenRecord.activeHours (wave 2: core agent implements tool rules + active hours). */
    private val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
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
        return confirmationDecision(token.confirmPolicy, tool.risk)
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

        internal fun confirmationDecision(policy: ConfirmPolicy, risk: Risk): PolicyDecision =
            if (requiresConfirmation(policy, risk)) PolicyDecision.RequireConfirmation else PolicyDecision.Allow

        /** Seconds until the oldest call leaves the window, clamped to 1..60. */
        internal fun retryAfterSeconds(oldest: Long, now: Long): Long {
            val ms = oldest + WINDOW_MS - now
            return ((ms + 999) / 1000).coerceIn(1, WINDOW_MS / 1000)
        }
    }
}
