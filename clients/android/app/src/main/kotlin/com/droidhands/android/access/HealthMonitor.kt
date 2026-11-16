package com.droidhands.android.access

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds [AccessibilityHealth] for one connected service: probes the window list (on connect, on demand) and
 * treats "user unlocked the phone" as an expected UI change. Probes are blocking IPC: never on the main thread.
 */
internal class HealthMonitor(private val service: AccessibilityService, private val health: AccessibilityHealth) {
    private var registered = false

    // False once the service disconnected: a late probe of this instance must not affect a newer connection.
    @Volatile
    private var active = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (active && intent.action == Intent.ACTION_USER_PRESENT) health.onExpectedChange(SystemClock.uptimeMillis(), usable())
        }
    }

    /** Registers for unlocks and schedules two probes shortly after connecting. Call on the main thread. */
    fun start(scope: CoroutineScope) {
        active = true
        try {
            ContextCompat.registerReceiver(service, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), UNLOCK_RECEIVER_FLAGS)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not watch for unlocks: ${e.javaClass.simpleName}")
        }
        scope.launch(Dispatchers.IO) {
            delay(STARTUP_PROBE_DELAY_MS)
            probe()
            delay(CONFIRM_MS + 200)
            probe()
        }
    }

    fun stop() {
        active = false
        if (!registered) return
        registered = false
        try {
            service.unregisterReceiver(unlockReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    /** Screen on and keyguard dismissed. */
    fun usable(): Boolean = try {
        service.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
    } catch (e: RuntimeException) {
        false
    }

    /** Records the outcome of a window listing done by the caller ([empty]: no windows and no active root). */
    fun record(empty: Boolean) {
        if (active) health.onProbe(SystemClock.uptimeMillis(), usable(), empty)
    }

    /** Probes now (blocking IPC). Returns true if the window list is empty. */
    fun probe(): Boolean {
        val empty = isEmptyNow()
        record(empty)
        return empty
    }

    /**
     * The current verdict: [AccessibilityHealth.STUCK_MESSAGE] or null. Off the main thread it probes, and when
     * the windows are missing it confirms after the grace period, so it may block about a second.
     */
    fun check(): String? {
        val offMain = Looper.myLooper() != Looper.getMainLooper()
        if (offMain && usable() && probe() && health.awaitingConfirmation()) {
            Thread.sleep(CONFIRM_MS + 100)
            probe()
        }
        return cached()
    }

    /** The verdict from what is already known; no IPC into apps. */
    fun cached(): String? = if (health.isStuck(SystemClock.uptimeMillis(), usable())) AccessibilityHealth.STUCK_MESSAGE else null

    private fun isEmptyNow(): Boolean = try {
        service.windows.isNullOrEmpty() && service.rootInActiveWindow == null
    } catch (e: RuntimeException) {
        true
    }

    companion object {
        private const val TAG = "DroidHands/A11yHealth"
        private const val STARTUP_PROBE_DELAY_MS = 1_500L

        /** How long missing windows must persist before the service counts as stuck. */
        const val CONFIRM_MS = 1_000L

        /**
         * Export flag of the unlock receiver. ACTION_USER_PRESENT is sent by System UI, which does not run as the system
         * uid: Android skips non-exported runtime receivers for it (API 33+), and on API 30-32 ContextCompat would guard a
         * non-exported receiver with a permission System UI lacks, so the receiver must be exported. That is safe: only
         * the system may send this protected broadcast.
         */
        const val UNLOCK_RECEIVER_FLAGS = ContextCompat.RECEIVER_EXPORTED
    }
}
