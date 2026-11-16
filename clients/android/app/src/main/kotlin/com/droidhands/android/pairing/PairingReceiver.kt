package com.droidhands.android.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.droidhands.android.graph.BridgeGraph

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
        private const val TAG = "DroidHands/Pairing"
        const val ACTION_DENY = "com.droidhands.android.pairing.DENY"
        const val EXTRA_ID = "com.droidhands.android.pairing.extra.ID"
        const val EXTRA_NONCE = "com.droidhands.android.pairing.extra.NONCE"
    }
}
