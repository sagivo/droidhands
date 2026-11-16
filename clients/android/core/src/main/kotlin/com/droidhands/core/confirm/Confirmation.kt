package com.droidhands.core.confirm

import com.droidhands.core.tools.Risk

data class ConfirmationRequest(
    /** Random id (also used as the nonce for UI callbacks). */
    val id: String,
    val tokenName: String,
    val toolName: String,
    val toolTitle: String,
    /** Human-readable summary of exactly what will happen. */
    val summary: String,
    val risk: Risk,
    val transport: String,
)

enum class ConfirmationResult { APPROVED, DENIED, TIMED_OUT }

/**
 * Asks the phone's user to approve an action. Implementations must be cancellation-aware: the
 * caller applies the timeout by cancelling the coroutine, and the implementation must then
 * remove its prompt (e.g. cancel the notification).
 */
fun interface ConfirmationProvider {
    suspend fun requestConfirmation(request: ConfirmationRequest): ConfirmationResult
}
