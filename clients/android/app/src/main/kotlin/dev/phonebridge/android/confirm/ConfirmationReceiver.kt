package dev.phonebridge.android.confirm

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.graph.BridgeGraph

/**
 * Handles Allow/Deny notification actions (and swipe-to-dismiss, which denies). Not exported; the
 * intents come only from this app's immutable PendingIntents and must carry the prompt's nonce.
 * Resolving is a quick in-memory operation; Allow uses goAsync() only to let the shade close first.
 */
class ConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approved = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return
        val confirmations = BridgeGraph.get(context).confirmations
        if (!approved) {
            // After a process restart the request is gone and this is a no-op.
            confirmations.resolveWithNonce(id, nonce, false)
            return
        }
        // Close the shade first and let it go before the call resumes, so the app the call opens is
        // the last window to appear.
        collapseNotificationShade(context)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                confirmations.resolveWithNonce(id, nonce, true)
            } finally {
                pending.finish()
            }
        }, SHADE_CLOSE_MS)
    }

    /**
     * Android leaves the notification shade open after a notification button, so the approved action
     * would open its app behind the shade, and screen tools that follow window changes would still see
     * System UI in front after the shade closes. Only this app's own Allow buttons reach this receiver.
     */
    private fun collapseNotificationShade(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BridgeAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            // Allowed for apps until Android 12, which replaced it with the accessibility action above.
            @Suppress("DEPRECATION")
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    companion object {
        /** Time for the notification shade to close after Allow, before the approved call goes on. */
        private const val SHADE_CLOSE_MS = 500L

        const val ACTION_ALLOW = "dev.phonebridge.android.confirm.ALLOW"
        const val ACTION_DENY = "dev.phonebridge.android.confirm.DENY"
        const val EXTRA_ID = "dev.phonebridge.android.confirm.extra.ID"
        const val EXTRA_NONCE = "dev.phonebridge.android.confirm.extra.NONCE"
    }
}
