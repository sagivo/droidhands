package dev.phonebridge.android.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.phonebridge.android.graph.BridgeGraph

/**
 * Handles the Deny action (and swipe-to-dismiss, which also denies) on pairing notifications. Not
 * exported; the intents come only from this app's immutable PendingIntents and must carry the
 * prompt's nonce. Denying is a quick in-memory operation, so no goAsync() is needed. After a process
 * restart the request is gone and this is a no-op.
 */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DENY) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return
        if (!BridgeGraph.get(context).pairing.denyWithNonce(id, nonce)) {
            Log.w(TAG, "Ignored a stale or mismatched pairing callback")
        }
    }

    companion object {
        private const val TAG = "PhoneBridge/Pairing"
        const val ACTION_DENY = "dev.phonebridge.android.pairing.DENY"
        const val EXTRA_ID = "dev.phonebridge.android.pairing.extra.ID"
        const val EXTRA_NONCE = "dev.phonebridge.android.pairing.extra.NONCE"
    }
}
