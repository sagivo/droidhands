package dev.phonebridge.android.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Keeps the relay reconnecting while the phone sleeps (the client's backoff timer stops in CPU sleep):
 * * reconnects at once when the screen turns on or the user unlocks;
 * * while the relay is down, keeps an inexact allow-while-idle alarm (no exact-alarm permission needed)
 *   that reconnects under a short wake lock when the client's own retry stalled ([RelayWakeups]).
 *
 * Network changes are handled by [BridgeService]'s network callback. Owned by [BridgeService]: [start] in
 * onCreate, [stop] in onDestroy (main thread). [retry] is [TransportController.retryRelay].
 */
internal class RelayReconnects(
    context: Context,
    private val retry: (alsoWhileConnecting: Boolean) -> Unit,
) {
    private val app: Context = context.applicationContext
    private val planner = RelayWakeups()
    private val lock = Any()
    private var stopped = false
    private var statusJob: Job? = null
    private val receivers = ArrayList<BroadcastReceiver>(2)

    private val alarmManager: AlarmManager? = app.getSystemService(AlarmManager::class.java)

    private val wakeLock: PowerManager.WakeLock? = try {
        app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    } catch (e: RuntimeException) {
        Log.w(TAG, "No wake lock for relay reconnects", e)
        null
    }

    private val alarmIntent: PendingIntent = PendingIntent.getBroadcast(
        app,
        0,
        Intent(ACTION_ALARM).setPackage(app.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = retry(false)
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Held before the planner can ask for its release (when the attempt ends).
            acquireWakeLock()
            val next = planner.onAlarm()
            if (next == null) {
                releaseWakeLock()
                return
            }
            setAlarm(next)
            retry(true)
        }
    }

    fun start(statuses: Flow<RelayStatus>, scope: CoroutineScope) {
        registerReceivers()
        statusJob = scope.launch {
            statuses.collect { status ->
                val step = planner.onStatus(status)
                if (step.alarmInMs != null) setAlarm(step.alarmInMs) else cancelAlarm()
                if (step.releaseWakeLock) releaseWakeLock()
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopped = true }
        statusJob?.cancel()
        receivers.forEach { receiver ->
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
            }
        }
        receivers.clear()
        cancelAlarm()
        releaseWakeLock()
    }

    /**
     * SCREEN_ON and USER_PRESENT are protected broadcasts (sent by the system and System UI only), exempt
     * from the export-flag requirement; USER_PRESENT would never reach a RECEIVER_NOT_EXPORTED receiver.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        try {
            app.registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
            receivers += screenReceiver
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not listen for screen-on; reconnects wait for the backoff", e)
        }
        try {
            // Only this app's own PendingIntent can reach it.
            ContextCompat.registerReceiver(app, alarmReceiver, IntentFilter(ACTION_ALARM), ContextCompat.RECEIVER_NOT_EXPORTED)
            receivers += alarmReceiver
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not register the reconnect alarm", e)
        }
    }

    private fun setAlarm(delayMs: Long) {
        synchronized(lock) {
            if (stopped) return
            try {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, alarmIntent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not schedule the reconnect alarm", e)
            }
        }
    }

    private fun cancelAlarm() {
        synchronized(lock) {
            try {
                alarmManager?.cancel(alarmIntent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not cancel the reconnect alarm", e)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not acquire the reconnect wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not release the reconnect wake lock", e)
        }
    }

    private companion object {
        const val TAG = "PhoneBridge/Reconnect"
        const val ACTION_ALARM = "dev.phonebridge.android.service.RELAY_RECONNECT"
        const val WAKE_LOCK_TAG = "PhoneBridge:relay-reconnect"

        /** Covers one attempt: connect (15 s) and the relay's welcome (20 s), with margin. */
        const val WAKE_LOCK_TIMEOUT_MS = 45_000L
    }
}
