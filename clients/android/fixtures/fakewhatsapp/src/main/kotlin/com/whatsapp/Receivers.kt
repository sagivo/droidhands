package com.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Test trigger (exported):
 * `adb shell am broadcast -n com.whatsapp/.IncomingMessageReceiver -a com.whatsapp.fixture.INCOMING
 *  --es from Tania --es text 'Are you coming?' [--es phone 972541234567]`
 * Records INCOMING and posts the sender's notification.
 */
class IncomingMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != ChatNotifications.ACTION_INCOMING) return
        val from = intent.extraString("from")?.takeIf { it.isNotBlank() } ?: "Unknown"
        val text = intent.extraString("text").orEmpty()
        val phone = digitsOf(intent.extraString("phone")).ifEmpty { null }
        FixtureEvents.record(
            context, "INCOMING",
            "from" to from, "text" to text, "phone" to phone,
            "notification_id" to ChatNotifications.idFor(from),
            // false => POST_NOTIFICATIONS not granted (or notifications blocked): nothing will be shown.
            "posted" to ChatNotifications.enabled(context),
        )
        ChatNotifications.postIncoming(context, from, text, phone)
    }
}

/** "Reply" action (RemoteInput "reply_text"): records REPLY, then shows the reply in the notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra(ChatNotifications.EXTRA_FROM).orEmpty()
        val phone = intent.getStringExtra(ChatNotifications.EXTRA_PHONE)
        val id = intent.getIntExtra(ChatNotifications.EXTRA_NOTIFICATION_ID, ChatNotifications.idFor(from))
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatNotifications.KEY_REPLY)?.toString()
        val source = when {
            reply == null -> null
            RemoteInput.getResultsSource(intent) == RemoteInput.SOURCE_CHOICE -> "choice"
            else -> "free_form"
        }
        FixtureEvents.record(
            context, "REPLY",
            "from" to from, "text" to reply.orEmpty(), "phone" to phone, "source" to source,
            "error" to (if (reply == null) "no_remote_input" else null),
        )
        if (reply != null) ChatNotifications.appendReply(context, id, from, phone, reply)
    }
}

/** "Mark as read" action: records MARK_READ and cancels the sender's notification. */
class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra(ChatNotifications.EXTRA_FROM).orEmpty()
        val phone = intent.getStringExtra(ChatNotifications.EXTRA_PHONE)
        val id = intent.getIntExtra(ChatNotifications.EXTRA_NOTIFICATION_ID, ChatNotifications.idFor(from))
        FixtureEvents.record(context, "MARK_READ", "from" to from, "phone" to phone)
        ChatNotifications.cancel(context, id)
    }
}
