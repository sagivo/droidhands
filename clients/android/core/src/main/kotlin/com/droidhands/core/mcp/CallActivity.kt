package com.droidhands.core.mcp

import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.tools.Risk

/**
 * A `tools/call` the user may be shown while it runs (the phone's live "agent activity" notification).
 * Carries who and what only, never the call's arguments: [label] is the body-free line the tool itself
 * reported through [com.droidhands.core.tools.CallContext.reportActivity] (e.g. "Sending WhatsApp to
 * Tania Cohen"), with one-time codes already masked when the user hides them.
 */
data class ActiveCall(
    /** Unique per call within this process. */
    val id: Long,
    val tokenId: String,
    val agentName: String,
    /** The tool that really runs (for `app_action_run`, the resolved tool). */
    val toolName: String,
    val toolTitle: String,
    val risk: Risk,
    val startedAt: Long,
    val label: String? = null,
    /** An approval prompt for this call is open. */
    val awaitingApproval: Boolean = false,
)

/**
 * Follows the tool calls that got past the policy checks, from just before their approval prompt (if any)
 * to their audit entry. Refused calls (unknown tool, missing scope, paused, policy, rate limits) are never
 * reported. Methods are called on the calls' own threads, concurrently; they must return quickly and should
 * not throw (exceptions are swallowed, so a broken listener can never fail a call).
 */
interface CallActivity {
    /** [call] started; its approval prompt (if it needs one) follows. */
    fun onStarted(call: ActiveCall) {}

    /** [call]'s label or approval state changed. */
    fun onUpdated(call: ActiveCall) {}

    /** [call] ended and was audited with [outcome] (CANCELLED when it was stopped). */
    fun onFinished(call: ActiveCall, outcome: AuditOutcome) {}

    companion object {
        val NONE: CallActivity = object : CallActivity {}
    }
}
