package com.droidhands.fixture.mail

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast

/**
 * Compose screen (exported). Prefilled from:
 *  - ACTION_SENDTO / ACTION_VIEW `mailto:a@x.com,b@x.com?to=..&cc=..&bcc=..&subject=..&body=..`;
 *  - ACTION_SEND / ACTION_SEND_MULTIPLE extras EXTRA_EMAIL / EXTRA_CC / EXTRA_BCC / EXTRA_SUBJECT / EXTRA_TEXT
 *    (also used to fill whatever a mailto: URI leaves empty);
 *  - internal replies ([replyIntent]): extra `in_reply_to` (email id) and `source` (app / notification).
 * Send (top-right ImageButton `send`) records EMAIL_SENT and finishes; an empty To shows a Toast instead.
 */
class ComposeActivity : Activity() {
    private var inReplyTo: String? = null
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)
        val draft = Draft.from(intent)
        val source = intent.getStringExtra(EXTRA_SOURCE)
        inReplyTo = draft.inReplyTo
        if (savedInstanceState == null) {
            field(R.id.to).setText(draft.to.joinToString(", "))
            field(R.id.cc).setText(draft.cc.joinToString(", "))
            field(R.id.bcc).setText(draft.bcc.joinToString(", "))
            field(R.id.subject).setText(draft.subject)
            field(R.id.body).setText(draft.body)
            if (draft.to.isNotEmpty()) field(R.id.body).requestFocus()
            FixtureEvents.record(
                this, "COMPOSE_OPENED",
                "action" to intent.action, "to" to draft.to.joinToString(", "), "cc" to draft.cc.joinToString(", "),
                "bcc" to draft.bcc.joinToString(", ").ifEmpty { null }, "subject" to draft.subject, "body" to draft.body,
                "in_reply_to" to draft.inReplyTo, "source" to source,
            )
        }
        // Opening the reply from a notification action consumes that notification (like Gmail).
        if (source == SOURCE_NOTIFICATION) draft.inReplyTo?.let { MailNotifications.cancel(this, it) }
        findViewById<View>(R.id.send).setOnClickListener { send() }
        findViewById<View>(R.id.close).setOnClickListener { finish() }
    }

    override fun onDestroy() {
        if (isFinishing && !sent) FixtureEvents.record(this, "COMPOSE_CLOSED", "in_reply_to" to inReplyTo)
        super.onDestroy()
    }

    private fun field(id: Int): EditText = findViewById(id)

    private fun send() {
        val to = field(R.id.to).text.toString().trim()
        if (to.isEmpty()) {
            Toast.makeText(this, "Add at least one recipient.", Toast.LENGTH_SHORT).show()
            FixtureEvents.record(this, "SEND_BLOCKED", "reason" to "empty_to")
            return
        }
        sent = true
        FixtureEvents.record(
            this, "EMAIL_SENT",
            "to" to to,
            "cc" to field(R.id.cc).text.toString().trim(),
            "bcc" to field(R.id.bcc).text.toString().trim().ifEmpty { null },
            "subject" to field(R.id.subject).text.toString(),
            "body" to field(R.id.body).text.toString(),
            "in_reply_to" to inReplyTo,
        )
        Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val ACTION_REPLY = "com.droidhands.fixture.mail.action.REPLY"
        const val EXTRA_IN_REPLY_TO = "in_reply_to"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_NOTIFICATION = "notification"

        /** Compose prefilled as a reply: to = sender, subject = "Re: ...", in_reply_to = email id. */
        fun replyIntent(context: Context, email: Email, source: String): Intent =
            Intent(context, ComposeActivity::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(email.from))
                .putExtra(Intent.EXTRA_SUBJECT, replySubject(email.subject))
                .putExtra(EXTRA_IN_REPLY_TO, email.id)
                .putExtra(EXTRA_SOURCE, source)
    }
}

/** The fields an incoming intent asks to prefill. */
data class Draft(
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
) {
    companion object {
        fun from(intent: Intent): Draft {
            val to = mutableListOf<String>()
            val cc = mutableListOf<String>()
            val bcc = mutableListOf<String>()
            var subject: String? = null
            var body: String? = null
            val data = intent.data
            if (data != null && "mailto".equals(data.scheme, ignoreCase = true)) {
                // Parse the raw string (RFC 6068): "+" stays "+", "%20" is a space, "#" is not a fragment.
                val raw = data.toString().substringAfter(':')
                to += addresses(Uri.decode(raw.substringBefore('?')))
                for (pair in raw.substringAfter('?', "").split('&')) {
                    if (pair.isEmpty()) continue
                    val value = Uri.decode(pair.substringAfter('=', ""))
                    when (Uri.decode(pair.substringBefore('=')).lowercase()) {
                        "to" -> to += addresses(value)
                        "cc" -> cc += addresses(value)
                        "bcc" -> bcc += addresses(value)
                        "subject" -> subject = value
                        "body" -> body = value
                    }
                }
            }
            intent.extraValues(Intent.EXTRA_EMAIL).forEach { to += addresses(it) }
            intent.extraValues(Intent.EXTRA_CC).forEach { cc += addresses(it) }
            intent.extraValues(Intent.EXTRA_BCC).forEach { bcc += addresses(it) }
            if (subject == null) subject = intent.extraString(Intent.EXTRA_SUBJECT)
            if (body == null) body = intent.extraValues(Intent.EXTRA_TEXT).joinToString("\n").ifEmpty { null }
            return Draft(
                to.distinct(), cc.distinct(), bcc.distinct(), subject.orEmpty(), body.orEmpty(),
                intent.extraString(ComposeActivity.EXTRA_IN_REPLY_TO),
            )
        }

        private fun addresses(value: String): List<String> =
            value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
