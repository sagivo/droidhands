package com.whatsapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** Launcher activity: the "Chats" list. Also asks for POST_NOTIFICATIONS (Android 13+). */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Home.show(this)
        ChatNotifications.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) FixtureEvents.record(this, "HOME_OPENED", "source" to "launcher")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        FixtureEvents.record(this, "NOTIFICATION_PERMISSION", "granted" to granted)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}

/** The "Chats" home screen, shared by [MainActivity] and [ConversationActivity] (links without a phone). */
object Home {
    private data class Chat(val name: String, val phone: String, val lastMessage: String)

    private val chats = listOf(
        Chat("Tania Cohen", "972541234567", "See you tomorrow!"),
        Chat("Mom", "972521112233", "Call me when you land"),
        Chat("Dev Team", "15551234567", "Build is green"),
    )

    fun show(activity: Activity) {
        activity.setContentView(R.layout.activity_home)
        val list = activity.findViewById<LinearLayout>(R.id.chat_list)
        for (chat in chats) {
            val row = activity.layoutInflater.inflate(R.layout.item_chat, list, false)
            row.findViewById<TextView>(R.id.conversations_row_contact_name).text = chat.name
            row.findViewById<TextView>(R.id.single_msg_tv).text = chat.lastMessage
            row.setOnClickListener {
                val link = Uri.parse("whatsapp://send?phone=${chat.phone}")
                activity.startActivity(Intent(Intent.ACTION_VIEW, link, activity, ConversationActivity::class.java))
            }
            list.addView(row)
        }
    }
}
