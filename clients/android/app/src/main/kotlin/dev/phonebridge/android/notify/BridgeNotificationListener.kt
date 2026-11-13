package dev.phonebridge.android.notify

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * Mirrors active notifications; reply/act/dismiss (docs/TOOLS.md "Notifications").
 *
 * Android binds this service once the user grants notification access. Nothing is cached here:
 * every query goes to the system, so callers always see the live notification (and its current
 * `PendingIntent`s). All query helpers are safe to call from any thread and never throw; a lost
 * binding shows up as `null` / `false`.
 */
class BridgeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected; requesting rebind")
        requestRebind(applicationContext, force = true)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Active notifications (all of them when [keys] is null, otherwise only those keys that still
     * exist), or null when the system can't be queried right now (binding lost, system error).
     */
    fun activeOrNull(keys: Array<String>? = null): List<StatusBarNotification>? = try {
        val result = if (keys == null) activeNotifications else getActiveNotifications(keys)
        // The framework returns null when it isn't bound or the binder call failed.
        result?.filterNotNull()
    } catch (e: SecurityException) {
        // "Disallowed call from unknown notification listener": access revoked or not bound yet.
        Log.w(TAG, "getActiveNotifications refused: ${e.javaClass.simpleName}")
        null
    } catch (e: RuntimeException) {
        // DeadObjectException is wrapped by the framework; bad parcels surface as RuntimeException.
        Log.w(TAG, "getActiveNotifications failed: ${e.javaClass.simpleName}")
        null
    }

    /** All active notifications; empty when the system can't be queried. */
    fun active(): List<StatusBarNotification> = activeOrNull().orEmpty()

    /** The active notification with [key], or null if it's gone (or the system can't be queried). */
    fun byKey(key: String): StatusBarNotification? = activeOrNull(arrayOf(key))?.firstOrNull { it.key == key }

    /** Dismisses the notification with [key], like a user swipe. False if the system refused. */
    fun cancel(key: String): Boolean = try {
        cancelNotification(key)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "cancelNotification failed: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "PBNotifications"

        /** Minimum spacing between opportunistic rebind requests. */
        private const val REBIND_INTERVAL_MS = 30_000L

        private val lastRebindAt = AtomicLong(Long.MIN_VALUE / 2)

        /** Connected listener, or null when notification access is off or Android hasn't bound it yet. */
        @Volatile
        var instance: BridgeNotificationListener? = null
            private set

        /** Whether notification access is granted to this app (it may be granted but not yet connected). */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Whether the listener is bound and usable right now. */
        val isConnected: Boolean get() = instance != null

        /** This service's component, as shown in Settings > Notification access. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, BridgeNotificationListener::class.java)

        /**
         * Asks Android to (re)bind the listener. A no-op when access isn't granted. Unless [force],
         * calls are throttled so availability checks can call it freely.
         */
        fun requestRebind(context: Context, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (force) {
                lastRebindAt.set(now)
            } else {
                val last = lastRebindAt.get()
                if (now - last < REBIND_INTERVAL_MS || !lastRebindAt.compareAndSet(last, now)) return
            }
            try {
                NotificationListenerService.requestRebind(componentName(context))
            } catch (e: RuntimeException) {
                Log.w(TAG, "requestRebind failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
