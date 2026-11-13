package dev.phonebridge.android.graph

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channel ids shared by every component that posts notifications. */
object Channels {
    /** Persistent foreground-service notification (low importance, silent, no badge). */
    const val STATUS = "bridge_status"

    /** Allow/Deny prompts for agent actions (high importance, heads-up, vibrates). */
    const val CONFIRM = "bridge_confirm"

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
        manager.createNotificationChannels(listOf(status, confirm))
    }
}

/** Notification ids, partitioned so components never collide. */
object NotificationIds {
    /** The BridgeService foreground notification. */
    const val STATUS = 1

    /** First id used for confirmation prompts; prompts use [CONFIRM_BASE] until [CONFIRM_BASE] + [CONFIRM_RANGE]. */
    const val CONFIRM_BASE = 10_000
    const val CONFIRM_RANGE = 100_000
}
