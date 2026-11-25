package com.droidhands.android.tools.notifications

import com.droidhands.android.Edition
import android.content.Context
import com.droidhands.android.notify.AppLabels
import com.droidhands.android.notify.BridgeNotificationListener
import com.droidhands.android.notify.MappedNotification
import com.droidhands.android.notify.NotificationMapper
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Listener availability and notification lookup for the notification tools. Lookups re-query
 * the system every time, so actions always use the notification's current `PendingIntent`s.
 */
internal object NotificationAccess {
    const val NOT_ENABLED =
        "Notification access is not enabled for DroidHands. Ask the user to open DroidHands app > Settings > Permissions & access > Notification access."

    const val RECONNECTING =
        "Notification access is enabled for DroidHands, but Android has not connected its notification listener yet " +
            "(it is reconnecting). Retry in a few seconds. If this keeps happening, ask the user to turn DroidHands off " +
            "and on again in Android Settings > Notification access."

    const val READ_FAILED =
        "Could not read notifications right now (Android's notification service did not answer). Retry in a few seconds."

    const val OWN_NOTIFICATION =
        "That is one of DroidHands's own notifications (bridge status or an approval prompt); agents can't act on it."

    /** For notifications whose content (and here: actions) Android 15+ hid from DroidHands. */
    val HIDDEN_WITHOUT_ACTION: String =
        "Android hid this notification's content and buttons from DroidHands (it treats it as sensitive), so it can't " +
            "be replied to or acted on here. " +
            (if (Edition.SMS_AND_CALL_LOG) "For SMS use sms_list / sms_send, for" else "For SMS use sms_send (the user taps Send), for") +
            " phone calls call_status / call_answer / call_end; otherwise " +
            (if (Edition.PHONE_USE) "open the app (app_open, then screen tools)." else "ask the user to open the app.")

    /** Generous bound on real keys (`0|com.example|12|tag|10123`), to reject garbage early. */
    private const val MAX_KEY_LENGTH = 1024

    /** Default bound for [peek], so confirmation summaries never stall the caller. */
    const val PEEK_TIMEOUT_MS = 80L

    /** For `Tool.unavailableReason`: null when the listener is connected. */
    fun unavailableReason(context: Context): String? {
        if (BridgeNotificationListener.isConnected) return null
        if (!BridgeNotificationListener.isEnabled(context)) return NOT_ENABLED
        // Granted but not bound: nudge Android (throttled; harmless when it is already rebinding).
        BridgeNotificationListener.requestRebind(context)
        return RECONNECTING
    }

    /** The connected listener, or a [ToolException] telling the agent what the user must do. */
    fun requireListener(context: Context): BridgeNotificationListener =
        BridgeNotificationListener.instance ?: throw ToolException(unavailableReason(context) ?: RECONNECTING)

    /** Every active notification (including DroidHands's own; filter before showing). */
    suspend fun all(context: Context): List<MappedNotification> = withContext(Dispatchers.IO) {
        val listener = requireListener(context)
        val active = listener.activeOrNull() ?: throw ToolException(queryFailure(context))
        active.map { NotificationMapper.map(context, it) }
    }

    /**
     * The live notification with [key]. Throws when it no longer exists (with a hint to refresh
     * keys), when the listener is unavailable, when it is one of DroidHands's own, or when its app
     * is blocked for the agent by [access] (checked before anything about it is read).
     */
    suspend fun find(context: Context, key: String, access: AppAccess): MappedNotification = withContext(Dispatchers.IO) {
        if (key.length > MAX_KEY_LENGTH) throw ToolException("Argument 'key' is too long. Pass a key from notifications_list.")
        val listener = requireListener(context)
        val found = listener.activeOrNull(arrayOf(key)) ?: throw ToolException(queryFailure(context))
        val sbn = found.firstOrNull { it.key == key } ?: throw ToolException(notFound(key))
        if (sbn.packageName == context.packageName) throw ToolException(OWN_NOTIFICATION)
        access.require(sbn.packageName, AppLabels.of(context, sbn.packageName))
        NotificationMapper.map(context, sbn)
    }

    /**
     * Best-effort, non-throwing [find] bounded by [timeoutMs], for `describeCall` (which isn't a
     * suspend function and must not block). Returns null on any problem; callers fall back to a
     * summary built from the raw arguments.
     */
    fun peek(context: Context, key: String?, timeoutMs: Long = PEEK_TIMEOUT_MS): MappedNotification? {
        if (key.isNullOrBlank() || key.length > MAX_KEY_LENGTH) return null
        val listener = BridgeNotificationListener.instance ?: return null
        val future = CompletableFuture.supplyAsync(
            {
                listener.byKey(key)
                    ?.takeIf { it.packageName != context.packageName }
                    ?.let { NotificationMapper.map(context, it) }
            },
            Dispatchers.IO.asExecutor(),
        )
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(false)
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun notFound(key: String): String =
        "No active notification with key \"$key\". It may have been dismissed, answered or replaced by a newer one. " +
            "Call notifications_list to get current keys."

    private fun queryFailure(context: Context): String = unavailableReason(context) ?: READ_FAILED
}
