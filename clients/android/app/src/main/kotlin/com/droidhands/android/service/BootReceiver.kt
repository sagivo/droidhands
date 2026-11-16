package com.droidhands.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.droidhands.android.graph.BridgeGraph

/**
 * Restarts BridgeService after boot / app update if "start on boot" is on and a transport is
 * enabled. Both broadcasts are exempt from Android 12+ foreground-service start restrictions, and
 * `specialUse` is not among the types Android 15 forbids starting from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = BridgeGraph.get(context)
        val settings = graph.settings.state.value
        if (settings.startOnBoot && (settings.relayEnabled || settings.localServerEnabled)) {
            graph.applySettings()
        }
    }
}
