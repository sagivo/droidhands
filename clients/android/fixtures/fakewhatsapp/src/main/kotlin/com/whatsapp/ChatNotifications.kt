package com.whatsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * WhatsApp-like message notifications: one MessagingStyle notification per sender (stable id from the
 * sender's name), all in group "chats" with a group summary, each with "Reply" (inline RemoteInput) and
 * "Mark as read" actions.
 */
object ChatNotifications {
    const val ACTION_INCOMING = "com.whatsapp.fixture.INCOMING"
    const val CHANNEL_ID = "messages"
    const val GROUP = "chats"
    const val SUMMARY_ID = 1
    const val KEY_REPLY = "reply_text"
    const val EXTRA_FROM = "from"
    const val EXTRA_PHONE = "phone"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    /** Stable per-sender notification id; always >= 1000 so it never collides with [SUMMARY_ID]. */
    fun idFor(from: String): Int = (from.trim().lowercase().hashCode() and 0x3fffffff) + 1000

    fun enabled(context: Context): Boolean = manager(context).areNotificationsEnabled()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Incoming messages (DroidHands test fixture)"
        manager(context).createNotificationChannel(channel)
    }

    /** Posts the sender's notification, appending to the conversation if one is already showing. */
    fun postIncoming(context: Context, from: String, text: String, phone: String?): Int {
        val id = idFor(from)
        val style = activeStyle(context, id)
            ?: NotificationCompat.MessagingStyle(me()).setConversationTitle(from).setGroupConversation(false)
        val sender = Person.Builder().setName(from).setKey(phone ?: from).build()
        style.addMessage(text, System.currentTimeMillis(), sender)
        show(context, id, from, phone, style, alert = true)
        return id
    }

    /** Adds the user's reply to the conversation so the notification shows it was sent. */
    fun appendReply(context: Context, id: Int, from: String, phone: String?, reply: String): Boolean {
        val style = activeStyle(context, id) ?: return false
        style.addMessage(reply, System.currentTimeMillis(), null as Person?) // null sender == the user
        show(context, id, from, phone, style, alert = false)
        return true
    }

    fun cancel(context: Context, id: Int) {
        manager(context).cancel(id)
        refreshSummary(context, removedId = id)
    }

    private fun me(): Person = Person.Builder().setName("You").setKey("me").build()

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun activeStyle(context: Context, id: Int): NotificationCompat.MessagingStyle? =
        manager(context).activeNotifications.firstOrNull { it.id == id && it.tag == null }
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }

    private fun show(
        context: Context,
        id: Int,
        from: String,
        phone: String?,
        style: NotificationCompat.MessagingStyle,
        alert: Boolean,
    ) {
        ensureChannel(context)
        val last = style.messages.lastOrNull()?.text?.toString().orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(from)
            .setContentText(last)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openChat(context, id, phone))
            .addAction(replyAction(context, id, from, phone))
            .addAction(markReadAction(context, id, from, phone))
            .build()
        manager(context).notify(id, notification)
        refreshSummary(context, postedId = id, postedLine = "$from: $last")
    }

    private fun replyAction(context: Context, id: Int, from: String, phone: String?): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java) // explicit: required for a mutable PendingIntent
            .putExtra(EXTRA_FROM, from)
            .putExtra(EXTRA_PHONE, phone)
            .putExtra(EXTRA_NOTIFICATION_ID, id)
        // RemoteInput results are filled into the intent by the sender, so it MUST be mutable.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").setAllowFreeFormInput(true).build()
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pending)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(context: Context, id: Int, from: String, phone: String?): NotificationCompat.Action {
        val intent = Intent(context, MarkReadReceiver::class.java)
            .putExtra(EXTRA_FROM, from)
            .putExtra(EXTRA_PHONE, phone)
            .putExtra(EXTRA_NOTIFICATION_ID, id)
        val pending = PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, "Mark as read", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun openChat(context: Context, id: Int, phone: String?): PendingIntent {
        val intent = if (phone != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$phone"), context, ConversationActivity::class.java)
        } else {
            Intent(context, MainActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Re-posts (or removes) the "chats" group summary. Active notifications may lag a just-posted or
     * just-cancelled notification (posting is asynchronous), so callers pass that change explicitly.
     */
    private fun refreshSummary(context: Context, postedId: Int? = null, postedLine: String? = null, removedId: Int? = null) {
        val nm = manager(context)
        val lines = LinkedHashMap<Int, String>()
        for (sbn in nm.activeNotifications) {
            val n = sbn.notification
            if (sbn.id == SUMMARY_ID || n.group != GROUP || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
            val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
            lines[sbn.id] = "$title: $text"
        }
        if (removedId != null) lines.remove(removedId)
        if (postedId != null && postedLine != null) lines[postedId] = postedLine
        if (lines.isEmpty()) {
            nm.cancel(SUMMARY_ID)
            return
        }
        val count = if (lines.size == 1) "1 chat" else "${lines.size} chats"
        val style = NotificationCompat.InboxStyle().setSummaryText(count)
        lines.values.forEach { style.addLine(it) }
        val open = PendingIntent.getActivity(
            context, SUMMARY_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("WhatsApp")
            .setContentText(count)
            .setStyle(style)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }
}
