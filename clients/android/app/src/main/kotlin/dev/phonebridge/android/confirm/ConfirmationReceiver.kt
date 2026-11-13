package dev.phonebridge.android.confirm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        BridgeGraph.get(context).confirmations.resolveWithNonce(id, nonce, approved)
    }

    companion object {
        const val ACTION_ALLOW = "dev.phonebridge.android.confirm.ALLOW"
        const val ACTION_DENY = "dev.phonebridge.android.confirm.DENY"
        const val EXTRA_ID = "dev.phonebridge.android.confirm.extra.ID"
        const val EXTRA_NONCE = "dev.phonebridge.android.confirm.extra.NONCE"
    }
}
