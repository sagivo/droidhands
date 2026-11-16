// Adapted from T3 Code (MIT, Copyright (c) 2026 T3 Tools Inc.),
// apps/mobile/modules/t3-agent-notifications/android/src/main/java/expo/modules/t3agentnotifications/AgentNotifications.kt
package com.droidhands.android.live

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.droidhands.android.R
import com.droidhands.android.graph.Channels
import com.droidhands.android.graph.NotificationGroups
import com.droidhands.android.graph.NotificationIds
import com.droidhands.android.service.BridgeService
import com.droidhands.android.ui.Destination
import com.droidhands.android.ui.MainActivity

/**
 * Posts the activity notification from [ActivityContent]: silent, alerting at most once, private on the lock
 * screen, opening the Activity tab, with Stop and Pause all while calls run. On Android 16 it asks to be
 * promoted to a Live Update (ongoing, uncolorized, a status-bar chip).
 *
 * It is a DroidHands notification like the others, so screen tools hide it from agents and refuse to tap it
 * (SelfProtection); unlike approval and pairing prompts it does not pause screen control (ApprovalGuard).
 */
internal object AgentActivityNotification {
    private const val RC_OPEN = 1
    private const val RC_STOP = 2
    private const val RC_PAUSE = 3
    private const val RC_DISMISSED = 4

    /**
     * Android 16's Notification.EXTRA_REQUEST_PROMOTED_ONGOING and EXTRA_SHORT_CRITICAL_TEXT, set directly
     * because the app compiles against API 35 (androidx.core's setRequestPromotedOngoing writes the same extra).
     */
    internal const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    internal const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    fun canPost(context: Context): Boolean = Channels.canPost(context, Channels.ACTIVITY)

    /** Throws SecurityException if notifications are not allowed (check [canPost] first). */
    @SuppressLint("MissingPermission") // Checked by canPost(); a race only throws SecurityException, handled by the caller.
    fun show(context: Context, content: ActivityContent) {
        val open = PendingIntent.getActivity(
            context,
            RC_OPEN,
            MainActivity.intent(context, Destination.ACTIVITY.route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Shown on a secure lock screen instead: no agent names, no actions.
        val publicVersion = NotificationCompat.Builder(context, Channels.ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.publicTitle)
            .setShowWhen(false)
            .build()
        val builder = NotificationCompat.Builder(context, Channels.ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setOngoing(content.ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setTimeoutAfter(content.timeoutMs)
            // Live Updates must stay uncolorized to be promoted.
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NotificationGroups.ACTIVITY)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(context, AgentActivityReceiver.ACTION_DISMISSED, RC_DISMISSED))
        if (content.requestPromotion) {
            builder.addExtras(
                Bundle().apply {
                    putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                    content.chip?.let { putString(EXTRA_SHORT_CRITICAL_TEXT, it) }
                },
            )
        }
        if (content.stopTokenIds.isNotEmpty()) {
            val stop = broadcast(context, AgentActivityReceiver.ACTION_STOP, RC_STOP) {
                putExtra(AgentActivityReceiver.EXTRA_TOKEN_IDS, content.stopTokenIds.toTypedArray())
            }
            // The same global pause as the status notification's Pause.
            val pause = PendingIntent.getService(
                context,
                RC_PAUSE,
                BridgeService.intent(context, BridgeService.ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, AgentActivityReceiver.STOP_LABEL, stop)
            builder.addAction(0, AgentActivityReceiver.PAUSE_ALL_LABEL, pause)
        }
        NotificationManagerCompat.from(context).notify(NotificationIds.ACTIVITY, builder.build())
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NotificationIds.ACTIVITY)
    }

    private fun broadcast(context: Context, action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AgentActivityReceiver::class.java).setAction(action).setPackage(context.packageName).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
