package dev.phonebridge.core.policy

import dev.phonebridge.core.auth.TokenRecord
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
 * STUB — implemented by the core agent.
 */
class PolicyEngine(
    private val limits: RateLimits = RateLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun evaluate(token: TokenRecord, tool: Tool): PolicyDecision = TODO()
}
