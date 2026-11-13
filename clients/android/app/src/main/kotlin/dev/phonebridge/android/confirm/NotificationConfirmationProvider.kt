package dev.phonebridge.android.confirm

import android.content.Context
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Shows a high-priority notification with Allow/Deny (explicit, immutable PendingIntents carrying the
 * request id as nonce) and suspends until resolved; tapping opens [ConfirmationActivity].
 * Cancellation (timeout) removes the notification.
 *
 * STUB — implemented by the services agent.
 */
class NotificationConfirmationProvider(private val context: Context) : ConfirmationProvider {
    override suspend fun requestConfirmation(request: ConfirmationRequest): ConfirmationResult = TODO()

    /** Pending requests (oldest first) — the UI may show them in-app too. */
    val pendingRequests: StateFlow<List<ConfirmationRequest>> get() = TODO()

    fun pending(id: String): ConfirmationRequest? = TODO()

    /** Completes a pending request; no-op for unknown/finished ids. */
    fun resolve(id: String, approved: Boolean): Unit = TODO()
}
