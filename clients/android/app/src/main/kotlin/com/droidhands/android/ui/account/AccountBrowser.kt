package com.droidhands.android.ui.account

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens the API server's sign-in page in a Custom Tab (Google doesn't allow sign-in inside a WebView). */
object AccountBrowser {
    /** False when no browser can open it. */
    fun open(context: Context, url: String): Boolean {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            tab.launchUrl(context, Uri.parse(url))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
