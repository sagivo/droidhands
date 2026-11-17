package com.droidhands.android.ui.connect

import android.content.Context
import android.content.Intent
import com.droidhands.android.ui.components.Clipboard
import com.droidhands.android.ui.components.SystemIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android side of [AgentApps]: which agent apps are on this phone, and opening them. */
internal object AgentAppIntents {
    /** What [send] did, so the page can say what to do next. */
    enum class Sent {
        /** The app opened with the message in its chat box. */
        IN_CHAT,

        /** The app takes no shared text: it opened with the message on the clipboard. */
        COPIED,

        /** The app couldn't be opened (uninstalled or disabled since the page loaded). */
        FAILED,
    }

    /** The agent apps installed (and launchable) on this phone, in [AgentApps.KNOWN] order. */
    suspend fun installed(context: Context): List<AgentApp> = withContext(Dispatchers.IO) {
        AgentApps.installed { packageName -> SystemIntents.openApp(context, packageName) != null }
    }

    /**
     * Opens [packageName] with [text] ready to send in its chat: a share to that app only, no share sheet.
     * Apps that don't take shared text open with [text] on the clipboard instead.
     */
    fun send(context: Context, packageName: String, text: String, clientId: String? = null): Sent {
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .setPackage(packageName)
        if (SystemIntents.launch(context, share)) {
            clientId?.let { Clipboard.copy(context, "Client ID", it, false) }
            return Sent.IN_CHAT
        }
        val open = SystemIntents.openApp(context, packageName) ?: return Sent.FAILED
        Clipboard.copy(context, "Message", text, false)
        return if (SystemIntents.launch(context, open)) Sent.COPIED else Sent.FAILED
    }

    /** Opens [link] (in the browser if [OpenLink.inBrowser], or [forceBrowser]). False if nothing could open it. */
    fun open(context: Context, link: OpenLink, forceBrowser: Boolean = false): Boolean =
        if (link.inBrowser || forceBrowser) {
            SystemIntents.launch(context, SystemIntents.inBrowser(context, link.url), SystemIntents.webPage(link.url))
        } else {
            SystemIntents.launch(context, SystemIntents.webPage(link.url))
        }
}
