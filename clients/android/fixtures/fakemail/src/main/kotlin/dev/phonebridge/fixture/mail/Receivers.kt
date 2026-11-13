package dev.phonebridge.fixture.mail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Test trigger (exported):
 * `adb shell am broadcast -n dev.phonebridge.fixture.mail/.IncomingEmailReceiver -a dev.phonebridge.fixture.mail.INCOMING
 *  --es from dana@example.com --es subject 'Lunch?' --es body 'Free at 12:30?' [--ez inline false]`
 * Adds the email to the inbox, records EMAIL_RECEIVED and posts its notification.
 */
class IncomingEmailReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != MailNotifications.ACTION_INCOMING) return
        val from = intent.extraString("from")?.takeIf { it.isNotBlank() } ?: "unknown@example.com"
        val subject = intent.extraString("subject") ?: "(no subject)"
        val body = intent.extraString("body").orEmpty()
        val inline = intent.extraBoolean("inline", default = true)
        val email = MailStore.add(context, from, subject, body)
        FixtureEvents.record(
            context, "EMAIL_RECEIVED",
            "id" to email.id, "from" to from, "subject" to subject, "body" to body, "inline" to inline,
            "notification_id" to MailNotifications.idFor(email.id),
            // false => POST_NOTIFICATIONS not granted (or notifications blocked): nothing will be shown.
            "posted" to MailNotifications.enabled(context),
        )
        MailNotifications.post(context, email, inline)
    }
}

/** Inline "Reply" action (RemoteInput "reply_body"): records EMAIL_REPLY and removes the notification. */
class InlineReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val emailId = intent.getStringExtra(MailNotifications.EXTRA_EMAIL_ID).orEmpty()
        val from = intent.getStringExtra(MailNotifications.EXTRA_FROM).orEmpty()
        val subject = intent.getStringExtra(MailNotifications.EXTRA_SUBJECT).orEmpty()
        val body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MailNotifications.KEY_REPLY)?.toString()
        FixtureEvents.record(
            context, "EMAIL_REPLY",
            "to" to from, "subject" to replySubject(subject), "body" to body.orEmpty(), "in_reply_to" to emailId,
            "error" to (if (body == null) "no_remote_input" else null),
        )
        if (body != null) MailNotifications.cancel(context, emailId)
    }
}

/** "Archive" action: records ARCHIVE, removes the email from the inbox and cancels the notification. */
class ArchiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val emailId = intent.getStringExtra(MailNotifications.EXTRA_EMAIL_ID).orEmpty()
        FixtureEvents.record(
            context, "ARCHIVE",
            "id" to emailId,
            "from" to intent.getStringExtra(MailNotifications.EXTRA_FROM),
            "subject" to intent.getStringExtra(MailNotifications.EXTRA_SUBJECT),
            "source" to "notification",
        )
        MailStore.remove(context, emailId)
        MailNotifications.cancel(context, emailId)
    }
}
