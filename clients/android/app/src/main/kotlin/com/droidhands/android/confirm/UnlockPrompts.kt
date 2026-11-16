package com.droidhands.android.confirm

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.droidhands.android.R
import com.droidhands.android.graph.Channels
import com.droidhands.android.graph.NotificationGroups
import com.droidhands.android.graph.NotificationIds
import kotlinx.coroutines.channels.Channel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android side of "unlock to continue" ([com.droidhands.android.tools.support.UnlockPrompt]):
 * posts a heads-up notification on [Channels.UNLOCK] (its own group, a "Not now" action), listens
 * for SCREEN_ON / USER_PRESENT, polls PowerManager and KeyguardManager as a fallback,
 * and always removes the notification and unregisters its receivers.
 *
 * Thread-safe: concurrent waits each get their own notification.
 */
internal object UnlockPrompts {
    private const val TAG = "DroidHands/Unlock"
    private const val ACTION_DECLINE = "com.droidhands.android.unlock.DECLINE"
    private const val EXTRA_NONCE = "com.droidhands.android.unlock.extra.NONCE"
    private const val NONCE_BYTES = 16

    private val sequence = AtomicLong()
    private val random = SecureRandom()

    /** True if the screen is on and no keyguard is showing. Binder calls; fine on any thread. */
    fun isUsable(context: Context): Boolean {
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return UnlockWait.isUsable(screenOn, locked)
    }

    /**
     * See [com.droidhands.android.tools.support.UnlockPrompt.await]. [isPaused] (agent access paused) is
     * checked first, before every other check: then nothing may run, so the wait ends with
     * [UnlockOutcome.PAUSED] even if the phone is unlocked.
     */
    suspend fun await(
        context: Context,
        agentName: String,
        action: String,
        timeoutMs: Long,
        isPaused: () -> Boolean = { false },
    ): UnlockOutcome {
        val app = context.applicationContext
        if (isPaused()) return UnlockOutcome.PAUSED
        if (isUsable(app)) return UnlockOutcome.USABLE
        if (timeoutMs <= 0) return UnlockOutcome.TIMED_OUT

        val notificationId = NotificationIds.UNLOCK_BASE + (sequence.getAndIncrement() % NotificationIds.UNLOCK_RANGE).toInt()
        val nonce = newNonce()
        // Receivers run on the main thread; the wait reads these from the caller's dispatcher.
        val declined = AtomicBoolean(false)
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                wakeups.trySend(Unit)
            }
        }
        val declineReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val presented = intent.getStringExtra(EXTRA_NONCE) ?: return
                if (MessageDigest.isEqual(presented.toByteArray(), nonce.toByteArray())) {
                    declined.set(true)
                    wakeups.trySend(Unit)
                }
            }
        }
        val registered = register(app, systemReceiver, declineReceiver)
        try {
            post(app, notificationId, nonce, agentName, action, timeoutMs)
            return UnlockWait.await(
                isUsable = { isUsable(app) },
                isDeclined = declined::get,
                wakeups = wakeups,
                timeoutMs = timeoutMs,
                isPaused = isPaused,
            )
        } finally {
            wakeups.close()
            cancel(app, notificationId)
            registered.forEach { receiver ->
                try {
                    app.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Receiver was not registered", e)
                }
            }
        }
    }

    /** Returns the receivers that were registered (polling still works if registration fails). */
    private fun register(context: Context, system: BroadcastReceiver, decline: BroadcastReceiver): List<BroadcastReceiver> {
        val registered = ArrayList<BroadcastReceiver>(2)
        try {
            registerForSystemBroadcasts(context, system)
            registered += system
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not listen for unlock broadcasts; polling instead", e)
        }
        try {
            // Only this app's own PendingIntent can reach it.
            ContextCompat.registerReceiver(context, decline, IntentFilter(ACTION_DECLINE), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered += decline
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not register the decline action", e)
        }
        return registered
    }

    /**
     * USER_PRESENT and SCREEN_ON are protected broadcasts that only system processes can send.
     * USER_PRESENT comes from System UI, which does not run as the system uid, so a
     * RECEIVER_NOT_EXPORTED receiver would never get it. Android exempts receivers of protected
     * broadcasts from the export-flag requirement (API 34+), so none is passed.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerForSystemBroadcasts(context: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(receiver, filter)
    }

    @SuppressLint("MissingPermission") // Checked by Channels.canPost(); a race only throws SecurityException, caught.
    private fun post(context: Context, notificationId: Int, nonce: String, agentName: String, action: String, timeoutMs: Long) {
        if (!Channels.canPost(context, Channels.UNLOCK)) {
            Log.w(TAG, "Notifications are off; waiting for an unlock without a prompt")
            return
        }
        val decline = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(ACTION_DECLINE)
                .setPackage(context.packageName)
                .setIdentifier("unlock/$notificationId/decline")
                .putExtra(EXTRA_NONCE, nonce),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val message = UnlockText.message(agentName, action)
        val publicVersion = NotificationCompat.Builder(context, Channels.UNLOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UnlockText.TITLE)
            .setContentText(UnlockText.PUBLIC_TEXT)
            .build()
        // Wall-clock deadline for the system's countdown; the wait itself uses the coroutine clock.
        val deadline = System.currentTimeMillis() + timeoutMs
        val notification = NotificationCompat.Builder(context, Channels.UNLOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UnlockText.TITLE)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(NotificationGroups.unlock(notificationId))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(timeoutMs + ConfirmationNotifications.NOTIFICATION_GRACE_MS)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            // No content intent: opening DroidHands would cover the app the agent is about to operate.
            .addAction(NotificationCompat.Action.Builder(0, UnlockText.DECLINE_ACTION, decline).build())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not post the unlock prompt", e)
        }
    }

    private fun cancel(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the unlock prompt", e)
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
