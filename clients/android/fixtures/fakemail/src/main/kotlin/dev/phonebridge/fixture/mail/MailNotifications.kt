package dev.phonebridge.fixture.mail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * One BigTextStyle notification per email (title = from, text = subject). Actions:
 *  - inline (default): "Reply" (RemoteInput "reply_body"), "Reply in app" (opens compose), "Archive";
 *  - inline = false (Gmail-like): "Archive", "Reply" WITHOUT RemoteInput (opens compose).
 */
object MailNotifications {
    const val ACTION_INCOMING = "dev.phonebridge.fixture.mail.INCOMING"
    const val CHANNEL_ID = "mail"
    const val KEY_REPLY = "reply_body"
    const val EXTRA_EMAIL_ID = "email_id"
    const val EXTRA_FROM = "from"
    const val EXTRA_SUBJECT = "subject"
    const val ACCOUNT = "me@fixture.test"

    /** Stable per-email notification id. */
    fun idFor(emailId: String): Int = (emailId.hashCode() and 0x3fffffff) + 1000

    fun enabled(context: Context): Boolean = manager(context).areNotificationsEnabled()

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Mail", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "New email (PhoneBridge test fixture)"
        manager(context).createNotificationChannel(channel)
    }

    fun cancel(context: Context, emailId: String) = manager(context).cancel(idFor(emailId))

    fun post(context: Context, email: Email, inline: Boolean): Int {
        ensureChannel(context)
        val id = idFor(email.id)
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            context, id, EmailActivity.intent(context, email.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable,
        )
        val composeReply = PendingIntent.getActivity(
            context, id,
            ComposeActivity.replyIntent(context, email, ComposeActivity.SOURCE_NOTIFICATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            immutable,
        )
        val archive = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_delete, "Archive", broadcast(context, ArchiveReceiver::class.java, id, email, false),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_ARCHIVE)
            .setShowsUserInterface(false)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle(email.from)
            .setContentText(email.subject)
            .setSubText(ACCOUNT)
            .setStyle(
                NotificationCompat.BigTextStyle().setBigContentTitle(email.from).bigText("${email.subject}\n${email.body}"),
            )
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(email.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (inline) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").setAllowFreeFormInput(true).build()
            val reply = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply", broadcast(context, InlineReplyReceiver::class.java, id, email, true),
            )
                .addRemoteInput(input)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
            val replyInApp = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, "Reply in app", composeReply)
                .setShowsUserInterface(true)
                .build()
            builder.addAction(reply).addAction(replyInApp).addAction(archive)
        } else {
            // Gmail-like: the Reply action has no RemoteInput; it opens the compose screen.
            val reply = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", composeReply)
                .setShowsUserInterface(true)
                .build()
            builder.addAction(archive).addAction(reply)
        }
        manager(context).notify(id, builder.build())
        return id
    }

    /** Explicit broadcast PendingIntent. Mutable only when it carries a RemoteInput (results are filled in). */
    private fun broadcast(
        context: Context,
        receiver: Class<out BroadcastReceiver>,
        id: Int,
        email: Email,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, receiver)
            .putExtra(EXTRA_EMAIL_ID, email.id)
            .putExtra(EXTRA_FROM, email.from)
            .putExtra(EXTRA_SUBJECT, email.subject)
        val mutability = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
