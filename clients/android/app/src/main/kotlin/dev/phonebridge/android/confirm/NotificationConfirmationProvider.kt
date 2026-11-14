package dev.phonebridge.android.confirm

import android.content.Context
import android.util.Log
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.tools.support.ApprovalGuard
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shows a high-priority notification with Allow/Deny (explicit, immutable PendingIntents carrying a
 * per-prompt random nonce) and suspends until resolved; tapping opens [ConfirmationActivity].
 * Cancellation (timeout) removes the notification.
 *
 * If notifications can't be shown (POST_NOTIFICATIONS denied or the channel blocked), it starts
 * [ConfirmationActivity] directly as a best effort (Android may block background activity starts)
 * and still waits. As a safety net it gives up with [ConfirmationResult.TIMED_OUT] slightly after
 * [timeoutMs] even if the caller never cancels.
 *
 * Thread-safe; [requestConfirmation] may be called concurrently from any dispatcher.
 */
class NotificationConfirmationProvider(
    private val context: Context,
    /** How long the server waits for an answer (must match McpServer's confirmationTimeoutMs). */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ConfirmationProvider {
    private val appContext: Context = context.applicationContext

    // Screen control stays off while any prompt is pending: the prompt may be showing, and agents must
    // never operate it (ApprovalGuard, checked before every gesture).
    private val registry = PendingConfirmations(onPendingChanged = { ApprovalGuard.set(GUARD_SOURCE, it) })

    override suspend fun requestConfirmation(request: ConfirmationRequest): ConfirmationResult {
        val entry = registry.register(request, timeoutMs) ?: run {
            Log.w(TAG, "Duplicate confirmation id for ${request.toolName}; denying")
            return ConfirmationResult.DENIED
        }
        try {
            prompt(entry)
            return withTimeoutOrNull(timeoutMs + ConfirmationNotifications.NOTIFICATION_GRACE_MS) { entry.result.await() }
                ?: ConfirmationResult.TIMED_OUT
        } finally {
            dismiss(entry)
        }
    }

    /** Pending requests (oldest first) — the UI may show them in-app too. */
    val pendingRequests: StateFlow<List<ConfirmationRequest>> get() = registry.requests

    /**
     * True while an approval prompt is waiting for the user. Also fed to [ApprovalGuard], which keeps
     * UI-automation tools off the screen meanwhile (the prompt may be showing, and agents must never operate it).
     */
    val hasPendingConfirmation: StateFlow<Boolean> get() = registry.hasPending

    fun pending(id: String): ConfirmationRequest? = registry[id]?.request

    /** Epoch millis when the server stops waiting for [id], or null if it is not pending. */
    fun expiresAt(id: String): Long? = registry[id]?.expiresAt

    /** Completes a pending request; no-op for unknown/finished ids. For trusted in-app UI only. */
    fun resolve(id: String, approved: Boolean) {
        val entry = registry[id] ?: return
        if (registry.resolve(id, approved)) dismiss(entry)
    }

    /**
     * Completes a pending request only if [nonce] matches the one issued with its notification
     * (used by [ConfirmationReceiver]). Returns false for unknown/finished ids or a wrong nonce.
     */
    fun resolveWithNonce(id: String, nonce: String, approved: Boolean): Boolean {
        val entry = registry[id] ?: return false
        val resolved = registry.resolveWithNonce(id, nonce, approved)
        if (resolved) dismiss(entry) else Log.w(TAG, "Ignored a stale or mismatched confirmation callback")
        return resolved
    }

    /** Denies every pending request, e.g. when the user pauses access. */
    fun denyAll() {
        val entries = registry.all()
        registry.denyAll()
        entries.forEach(::dismiss)
    }

    private suspend fun prompt(entry: PendingConfirmation) {
        val posted = try {
            ConfirmationNotifications.canPost(appContext) && run {
                ConfirmationNotifications.show(appContext, entry, timeoutMs)
                true
            }
        } catch (e: RuntimeException) {
            // e.g. SecurityException if the notification permission was revoked a moment ago.
            Log.w(TAG, "Could not post the confirmation notification; opening the confirmation screen", e)
            false
        }
        if (!posted) withContext(Dispatchers.Main) { launchActivity(entry) }
    }

    private fun launchActivity(entry: PendingConfirmation) {
        // A bound accessibility service is exempt from background-activity-launch limits.
        val launcher: Context = BridgeAccessibilityService.instance ?: appContext
        try {
            launcher.startActivity(ConfirmationActivity.intent(appContext, entry.request.id))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the confirmation screen; the request will time out", e)
        }
    }

    private fun dismiss(entry: PendingConfirmation) {
        registry.remove(entry)
        try {
            ConfirmationNotifications.cancel(appContext, entry.notificationId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel confirmation notification", e)
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Confirm"

        /** [ApprovalGuard] source: a confirmation prompt is pending. */
        const val GUARD_SOURCE = "confirmations"

        /** McpServer's default confirmation timeout. */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
