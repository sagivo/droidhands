package dev.phonebridge.android.notify

import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Mirrors active notifications; reply/act/dismiss (docs/TOOLS.md "Notifications").
 *
 * STUB — implemented by the notifications agent. Companion API is used by other components.
 */
class BridgeNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: BridgeNotificationListener? = null
            private set

        /** Whether notification access is granted to this app. */
        fun isEnabled(context: Context): Boolean = TODO()
    }
}
