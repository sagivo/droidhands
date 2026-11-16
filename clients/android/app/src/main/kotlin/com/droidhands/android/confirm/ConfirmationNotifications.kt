package com.droidhands.android.confirm

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.droidhands.android.R
import com.droidhands.android.graph.Channels
import com.droidhands.android.graph.NotificationGroups

/**
 * Builds and posts the Allow/Deny prompt for a [PendingConfirmation].
 *
 * Security properties: every PendingIntent is explicit (component set) and immutable, has a unique
 * request code and identifier, and the broadcast ones carry the prompt's random nonce. On Android
 * 12+ the Allow action requires device authentication, so it can't be approved from a locked
 * screen; on Android 11 Allow opens [ConfirmationActivity], which Android only shows after unlock.
 * Deny needs no unlock (refusing is always safe).
 */
internal object ConfirmationNotifications {
    private const val RC_OPEN = 0
    private const val RC_ALLOW = 1
    private const val RC_DENY = 2
    private const val RC_DELETE = 3

    /** Extra time on top of the server timeout before the system removes a forgotten prompt. */
    const val NOTIFICATION_GRACE_MS = 5_000L

    /** True if a prompt on [Channels.CONFIRM] would actually be shown to the user. */
    fun canPost(context: Context): Boolean = Channels.canPost(context, Channels.CONFIRM)

    /** Posts the prompt. Throws SecurityException if notifications are not allowed (check [canPost] first). */
    @SuppressLint("MissingPermission") // Checked by canPost(); a race only throws SecurityException, handled by the caller.
    fun show(context: Context, entry: PendingConfirmation, timeoutMs: Long) {
        val request = entry.request
        val open = PendingIntent.getActivity(
            context,
            requestCode(entry, RC_OPEN),
            ConfirmationActivity.intent(context, request.id).setIdentifier(identifier(entry, "open")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = broadcast(context, entry, ConfirmationReceiver.ACTION_DENY, RC_DENY)
        val dismissed = broadcast(context, entry, ConfirmationReceiver.ACTION_DENY, RC_DELETE, tag = "deleted")
        val allow = if (Build.VERSION.SDK_INT >= 31) {
            broadcast(context, entry, ConfirmationReceiver.ACTION_ALLOW, RC_ALLOW)
        } else {
            // No authentication-required actions before Android 12: review in the activity instead.
            PendingIntent.getActivity(
                context,
                requestCode(entry, RC_ALLOW),
                ConfirmationActivity.intent(context, request.id).setIdentifier(identifier(entry, "allow")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val allowAction = NotificationCompat.Action.Builder(0, "Allow", allow)
            .setAuthenticationRequired(true)
            .build()
        val denyAction = NotificationCompat.Action.Builder(0, "Deny", deny).build()

        // Shown on a secure lock screen when content is hidden: no details, no actions.
        val publicVersion = NotificationCompat.Builder(context, Channels.CONFIRM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("An agent is asking for approval")
            .setContentText("Unlock your phone to review it.")
            .build()

        val notification = NotificationCompat.Builder(context, Channels.CONFIRM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationText.title(request))
            .setContentText(request.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ConfirmationText.notificationBody(request)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Its own group: never auto-grouped with other DroidHands notifications (which collapses it
            // and hides Allow/Deny). As the only member, it alerts (heads-up) like an ungrouped one.
            .setGroup(NotificationGroups.confirmation(entry.notificationId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(timeoutMs + NOTIFICATION_GRACE_MS)
            // Live countdown rendered by the system.
            .setWhen(entry.expiresAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // Never bridge approvals to watches/other devices.
            .setLocalOnly(true)
            .addAction(denyAction)
            .addAction(allowAction)
            .build()

        NotificationManagerCompat.from(context).notify(entry.notificationId, notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun broadcast(context: Context, entry: PendingConfirmation, action: String, kind: Int, tag: String = action): PendingIntent {
        val intent = Intent(context, ConfirmationReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .setIdentifier(identifier(entry, tag))
            .putExtra(ConfirmationReceiver.EXTRA_ID, entry.request.id)
            .putExtra(ConfirmationReceiver.EXTRA_NONCE, entry.nonce)
        return PendingIntent.getBroadcast(
            context,
            requestCode(entry, kind),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // Unique per prompt and purpose; notification ids are unique among pending prompts.
    private fun requestCode(entry: PendingConfirmation, kind: Int): Int = entry.notificationId * 4 + kind

    private fun identifier(entry: PendingConfirmation, purpose: String): String = "confirm/${entry.notificationId}/$purpose"
}
