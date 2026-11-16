package com.droidhands.android.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidhands.android.graph.BridgeGraph

/**
 * The activity notification's Stop action and swipe-away. Not exported; the intents come only from this app's
 * immutable PendingIntents. Both are always safe (they stop or hide, never allow), so no nonce or unlock is
 * needed. Pause all goes straight to BridgeService, like the status notification's Pause.
 */
class AgentActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = BridgeGraph.get(context)
        when (intent.action) {
            ACTION_STOP -> graph.stopAgentCalls(intent.getStringArrayExtra(EXTRA_TOKEN_IDS).orEmpty().toList())
            ACTION_DISMISSED -> graph.agentActivity.dismiss()
        }
    }

    companion object {
        const val ACTION_STOP = "com.droidhands.android.live.STOP"
        const val ACTION_DISMISSED = "com.droidhands.android.live.DISMISSED"
        const val EXTRA_TOKEN_IDS = "com.droidhands.android.live.extra.TOKEN_IDS"

        const val STOP_LABEL = "Stop"
        const val PAUSE_ALL_LABEL = "Pause all"
    }
}
