package dev.phonebridge.android.confirm

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.graph.BridgeGraph

/**
 * Handles Allow/Deny notification actions (and swipe-to-dismiss, which denies). Not exported; the
 * intents come only from this app's immutable PendingIntents and must carry the prompt's nonce.
 * Resolving is a quick in-memory operation, so no goAsync() is needed.
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
        // After a process restart the request is gone and this is a no-op.
        val resolved = BridgeGraph.get(context).confirmations.resolveWithNonce(id, nonce, approved)
        if (resolved && approved) collapseNotificationShade(context)
    }

    /**
     * Android leaves the notification shade open after a notification button, so the approved action
     * would open its app behind the shade (and screen steps would see System UI in front). Close it.
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
        const val ACTION_ALLOW = "dev.phonebridge.android.confirm.ALLOW"
        const val ACTION_DENY = "dev.phonebridge.android.confirm.DENY"
        const val EXTRA_ID = "dev.phonebridge.android.confirm.extra.ID"
        const val EXTRA_NONCE = "dev.phonebridge.android.confirm.extra.NONCE"
    }
}
