package dev.phonebridge.android.pairing

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.phonebridge.android.R
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.graph.Channels
import dev.phonebridge.android.graph.NotificationGroups

/**
 * Posts the pairing prompt as a heads-up notification on [Channels.PAIRING]:
 * "<client> (<host>) wants to connect · code <code>". Tapping it (or "Review") opens
 * [PairingActivity]; "Deny" and swiping it away deny through [PairingReceiver] with the prompt's
 * nonce. There is deliberately no Allow button: the user must pick the access level first.
 *
 * Each prompt has its own group, so Android never auto-groups and collapses it with other
 * PhoneBridge notifications. Every PendingIntent is explicit and immutable, with a unique request
 * code and identifier. If notifications are off, [PairingActivity] is opened directly as a best
 * effort (Android may block that from the background); the Home card lists the request too.
 */
class PairingNotifications(context: Context) : PairingPrompts {
    private val appContext = context.applicationContext

    override fun show(entry: PendingPairing) {
        if (!Channels.canPost(appContext, Channels.PAIRING)) {
            openActivity(entry)
            return
        }
        try {
            post(entry)
        } catch (e: RuntimeException) {
            // e.g. SecurityException: the notification permission was revoked a moment ago.
            Log.w(TAG, "Could not post the pairing notification; opening the pairing screen", e)
            openActivity(entry)
        }
    }

    override fun cancel(entry: PendingPairing) {
        NotificationManagerCompat.from(appContext).cancel(entry.notificationId)
    }

    @SuppressLint("MissingPermission") // Checked by Channels.canPost(); a race throws SecurityException, handled by show().
    private fun post(entry: PendingPairing) {
        val request = entry.request
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            appContext,
            requestCode(entry, RC_OPEN),
            PairingActivity.intent(appContext, request.id).setIdentifier(identifier(entry, "open")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = denyIntent(entry, RC_DENY, "deny")
        val dismissed = denyIntent(entry, RC_DELETE, "deleted")

        // Shown on a secure lock screen that hides content: no names, no code, no actions.
        val publicVersion = NotificationCompat.Builder(appContext, Channels.PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("An AI agent wants to connect")
            .setContentText("Unlock your phone to review it.")
            .build()

        val notification = NotificationCompat.Builder(appContext, Channels.PAIRING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PairingPresentation.notificationTitle(request))
            .setContentText(PairingPresentation.notificationText(request))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PairingPresentation.notificationBigText(request)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(NotificationGroups.pairing(entry.notificationId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((request.expiresAt - now).coerceAtLeast(0) + GRACE_MS)
            .setWhen(request.expiresAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .addAction(NotificationCompat.Action.Builder(0, "Deny", deny).build())
            .addAction(NotificationCompat.Action.Builder(0, "Review", open).build())
            .build()

        NotificationManagerCompat.from(appContext).notify(entry.notificationId, notification)
    }

    private fun denyIntent(entry: PendingPairing, kind: Int, purpose: String): PendingIntent {
        val intent = Intent(appContext, PairingReceiver::class.java)
            .setAction(PairingReceiver.ACTION_DENY)
            .setPackage(appContext.packageName)
            .setIdentifier(identifier(entry, purpose))
            .putExtra(PairingReceiver.EXTRA_ID, entry.request.id)
            .putExtra(PairingReceiver.EXTRA_NONCE, entry.nonce)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(entry, kind),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openActivity(entry: PendingPairing) {
        // A bound accessibility service is exempt from background-activity-launch limits.
        val launcher: Context = BridgeAccessibilityService.instance ?: appContext
        try {
            launcher.startActivity(PairingActivity.intent(appContext, entry.request.id))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the pairing screen; the request will time out unless answered in the app", e)
        }
    }

    // Unique per prompt and purpose; notification ids are unique among pending prompts.
    private fun requestCode(entry: PendingPairing, kind: Int): Int = entry.notificationId * 4 + kind

    private fun identifier(entry: PendingPairing, purpose: String): String = "pairing/${entry.notificationId}/$purpose"

    private companion object {
        const val TAG = "PhoneBridge/Pairing"
        const val RC_OPEN = 0
        const val RC_DENY = 1
        const val RC_DELETE = 2

        /** Extra time after expiresAt before the system removes a forgotten prompt. */
        const val GRACE_MS = 5_000L
    }
}
