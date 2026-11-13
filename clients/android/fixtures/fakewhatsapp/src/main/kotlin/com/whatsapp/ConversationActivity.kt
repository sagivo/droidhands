package com.whatsapp

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Chat screen opened by https://api.whatsapp.com/send?phone=&text=, https://wa.me/<phone>?text=,
 * whatsapp://send?phone=&text= (and ACTION_SEND with a "jid" extra).
 *  - no phone                 -> "Chats" home screen (no entry / send);
 *  - phone ending in "0000"   -> home screen + "isn't on WhatsApp" dialog (NOT_ON_WHATSAPP);
 *  - otherwise                -> chat with `text` prefilled in `entry`; tapping `send` records SENT.
 * singleTask: a new link arrives in [onNewIntent] and replaces the current chat.
 */
class ConversationActivity : Activity() {
    private var phone = ""
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun load(intent: Intent) {
        dialog?.dismiss()
        dialog = null
        val link = ChatLink.from(intent)
        phone = link.phone
        when {
            link.phone.isEmpty() -> {
                Home.show(this)
                FixtureEvents.record(this, "HOME_OPENED", "source" to "link", "uri" to link.source)
            }
            link.phone.endsWith("0000") -> {
                Home.show(this)
                showNotOnWhatsApp(link)
            }
            else -> showChat(link)
        }
    }

    private fun showChat(link: ChatLink) {
        // A fresh content view per chat: the message list starts empty, header and entry are replaced.
        setContentView(R.layout.activity_conversation)
        findViewById<TextView>(R.id.conversation_contact_name).text = "+${link.phone}"
        val entry = findViewById<EditText>(R.id.entry)
        entry.setText(link.text.orEmpty())
        entry.setSelection(entry.length())
        findViewById<ImageButton>(R.id.send).setOnClickListener { send(entry) }
        FixtureEvents.record(this, "CHAT_OPENED", "phone" to link.phone, "text" to link.text, "uri" to link.source)
    }

    private fun send(entry: EditText) {
        val text = entry.text.toString()
        if (text.isBlank()) return
        val list = findViewById<LinearLayout>(R.id.conversation_list)
        val bubble = layoutInflater.inflate(R.layout.item_message, list, false) as TextView
        bubble.text = text
        list.addView(bubble)
        entry.text.clear()
        val scroll = findViewById<ScrollView>(R.id.conversation_scroll)
        scroll.post { scroll.scrollTo(0, list.bottom) }
        FixtureEvents.record(this, "SENT", "phone" to phone, "text" to text)
    }

    private fun showNotOnWhatsApp(link: ChatLink) {
        val shown = AlertDialog.Builder(this)
            .setMessage("The phone number +${link.phone} isn't on WhatsApp.")
            .setPositiveButton("OK", null)
            .setNegativeButton("Invite") { _, _ -> FixtureEvents.record(this, "INVITE", "phone" to link.phone) }
            .show()
        // Material dialog buttons are ALL CAPS by default; keep the exact labels "OK" / "Invite".
        shown.getButton(DialogInterface.BUTTON_POSITIVE)?.isAllCaps = false
        shown.getButton(DialogInterface.BUTTON_NEGATIVE)?.isAllCaps = false
        dialog = shown
        FixtureEvents.record(this, "NOT_ON_WHATSAPP", "phone" to link.phone, "text" to link.text, "uri" to link.source)
    }
}

/** What a chat link asks for. [phone] is digits only ("" if absent); [source] is the raw link, for logging. */
data class ChatLink(val phone: String, val text: String?, val source: String?) {
    companion object {
        fun from(intent: Intent): ChatLink {
            if (intent.action == Intent.ACTION_SEND) {
                val jid = intent.extraString("jid")
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                return ChatLink(digitsOf(jid?.substringBefore('@')), text, "send:jid=${jid.orEmpty()}")
            }
            val uri = intent.data ?: return ChatLink("", null, null)
            val rawPhone = if (uri.host.equals("wa.me", ignoreCase = true)) {
                uri.pathSegments.firstOrNull() ?: uri.param("phone")
            } else {
                uri.param("phone")
            }
            return ChatLink(digitsOf(rawPhone), uri.param("text"), uri.toString())
        }

        private fun Uri.param(name: String): String? =
            try {
                getQueryParameter(name)
            } catch (e: UnsupportedOperationException) { // opaque URI such as "whatsapp:send"
                null
            }
    }
}
