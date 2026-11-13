package dev.phonebridge.fixture.mail

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Launcher: the inbox list. Each row is a clickable `email_row` with `email_from` / `email_subject`. */
class InboxActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox)
        findViewById<View>(R.id.compose).setOnClickListener { startActivity(Intent(this, ComposeActivity::class.java)) }
        MailNotifications.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        MailStore.onChanged = { render() }
        render()
    }

    override fun onPause() {
        MailStore.onChanged = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        FixtureEvents.record(this, "NOTIFICATION_PERMISSION", "granted" to granted)
    }

    private fun render() {
        val emails = MailStore.all(this)
        val list = findViewById<LinearLayout>(R.id.email_list)
        list.removeAllViews()
        for (email in emails) {
            val row = layoutInflater.inflate(R.layout.item_email, list, false)
            row.findViewById<TextView>(R.id.email_from).text = email.from
            row.findViewById<TextView>(R.id.email_subject).text = email.subject
            row.findViewById<TextView>(R.id.email_snippet).text = email.snippet
            row.setOnClickListener { startActivity(EmailActivity.intent(this, email.id)) }
            list.addView(row)
        }
        findViewById<View>(R.id.empty_inbox).visibility = if (emails.isEmpty()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}

/** One email: `email_from`, `email_subject`, `email_body`, and the `reply` / `archive` buttons. */
class EmailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val email = intent.getStringExtra(EXTRA_EMAIL_ID)?.let { MailStore.find(this, it) }
        if (email == null) {
            Toast.makeText(this, "This email is no longer in the inbox", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_email)
        findViewById<TextView>(R.id.email_from).text = email.from
        findViewById<TextView>(R.id.email_subject).text = email.subject
        findViewById<TextView>(R.id.email_body).text = email.body
        findViewById<View>(R.id.reply).setOnClickListener {
            startActivity(ComposeActivity.replyIntent(this, email, source = "app"))
        }
        findViewById<View>(R.id.archive).setOnClickListener {
            MailStore.remove(this, email.id)
            MailNotifications.cancel(this, email.id)
            FixtureEvents.record(
                this, "ARCHIVE", "id" to email.id, "from" to email.from, "subject" to email.subject, "source" to "app",
            )
            finish()
        }
        if (savedInstanceState == null) {
            FixtureEvents.record(this, "EMAIL_OPENED", "id" to email.id, "from" to email.from, "subject" to email.subject)
        }
    }

    companion object {
        const val EXTRA_EMAIL_ID = "email_id"

        fun intent(context: Context, emailId: String): Intent =
            Intent(context, EmailActivity::class.java).putExtra(EXTRA_EMAIL_ID, emailId)
    }
}
