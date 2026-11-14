package dev.phonebridge.android.graph

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notification channel ids shared by every component that posts notifications. */
object Channels {
    /** Persistent foreground-service notification (low importance, silent, no badge). */
    const val STATUS = "bridge_status"

    /** Allow/Deny prompts for agent actions (high importance, heads-up, vibrates). */
    const val CONFIRM = "bridge_confirm"

    /** One-tap pairing requests from new agents (high importance, heads-up). */
    const val PAIRING = "bridge_pairing"

    /** "Unlock your phone to let <agent> …" prompts (high importance, heads-up). */
    const val UNLOCK = "bridge_unlock"

    /** Creates (or updates the names of) all channels. Idempotent; cheap enough for Application.onCreate. */
    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(STATUS, "Bridge status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows that PhoneBridge is running, its connections and the last agent action."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val confirm = NotificationChannel(CONFIRM, "Action approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks you to Allow or Deny actions requested by your AI agents."
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        val pairing = NotificationChannel(PAIRING, "New agent connections", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks you to approve an AI agent that wants to connect to this phone."
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        val unlock = NotificationChannel(UNLOCK, "Unlock requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks you to unlock the phone when an agent's action needs the screen."
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(status, confirm, pairing, unlock))
    }

    /**
     * True if a notification on [channelId] would actually be shown: the app may post notifications
     * (POST_NOTIFICATIONS on Android 13+, not blocked in settings) and the channel is not turned off.
     * Re-creates missing channels (e.g. deleted by a "clear data" race) once.
     */
    fun canPost(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(channelId)
            ?: run {
                createAll(context)
                manager.getNotificationChannelCompat(channelId)
            }
            ?: return false
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }
}

/** Notification ids, partitioned so components never collide. */
object NotificationIds {
    /** The BridgeService foreground notification. */
    const val STATUS = 1

    /** First id used for confirmation prompts; prompts use [CONFIRM_BASE] until [CONFIRM_BASE] + [CONFIRM_RANGE]. */
    const val CONFIRM_BASE = 10_000
    const val CONFIRM_RANGE = 100_000

    /** Pairing prompts use [PAIRING_BASE] until [PAIRING_BASE] + [PAIRING_RANGE]. */
    const val PAIRING_BASE = 120_000
    const val PAIRING_RANGE = 10_000

    /** Unlock prompts use [UNLOCK_BASE] until [UNLOCK_BASE] + [UNLOCK_RANGE]. */
    const val UNLOCK_BASE = 140_000
    const val UNLOCK_RANGE = 10_000
}

/**
 * Notification group keys. Every prompt gets a group of its own: Android auto-groups an app's
 * *ungrouped* notifications once there are several, and an auto-grouped prompt is collapsed under
 * the group header with its Allow/Deny buttons hidden. A group with a single notification and no
 * summary is shown as a normal, expanded notification.
 */
object NotificationGroups {
    const val STATUS = "phonebridge.status"

    fun confirmation(notificationId: Int): String = "phonebridge.confirm.$notificationId"

    fun pairing(notificationId: Int): String = "phonebridge.pairing.$notificationId"

    fun unlock(notificationId: Int): String = "phonebridge.unlock.$notificationId"
}
