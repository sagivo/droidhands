package dev.phonebridge.android.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonebridge.android.R
import dev.phonebridge.android.graph.Channels
import dev.phonebridge.android.ui.MainActivity

/** Builds the BridgeService foreground notification from [StatusNotificationContent]. */
internal object StatusNotification {
    private const val RC_OPEN = 1
    private const val RC_PAUSE = 2
    private const val RC_RESUME = 3

    fun build(context: Context, content: StatusNotificationContent): Notification {
        val open = openAppIntent(context)
        val toggle = if (content.paused) {
            serviceIntent(context, RC_RESUME, BridgeService.ACTION_RESUME)
        } else {
            serviceIntent(context, RC_PAUSE, BridgeService.ACTION_PAUSE)
        }
        // Shown on a secure lock screen instead of the private version (hides token and tool names).
        val publicVersion = NotificationCompat.Builder(context, Channels.STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setShowWhen(false)
            .build()
        return NotificationCompat.Builder(context, Channels.STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // Show right away: the user should always see when agents can reach the phone.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                NotificationCompat.Action.Builder(0, if (content.paused) "Resume" else "Pause", toggle)
                    // Pausing is always safe; re-enabling agent access requires unlocking (Android 12+).
                    .setAuthenticationRequired(content.paused)
                    .build(),
            )
            .addAction(0, "Open", open)
            .build()
    }

    /** Brings the existing app task to the front (like the launcher icon) instead of stacking a new one. */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, RC_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            BridgeService.intent(context, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
