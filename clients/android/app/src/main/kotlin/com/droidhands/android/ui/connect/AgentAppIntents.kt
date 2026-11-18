package com.droidhands.android.ui.connect

import android.content.Context
import android.content.Intent
import com.droidhands.android.ui.components.Clipboard
import com.droidhands.android.ui.components.SystemIntents

/** The Android side of the agent list: sending to an agent, and opening its setup link. */
internal object AgentAppIntents {
    /** What [send] did, so the page can say what to do next. */
    enum class Sent {
        /** The agent's app (or the share sheet) opened with the message. */
        SENT,

        /** The agent's app takes no shared text: it opened with the message on the clipboard. */
        COPIED,

        /** Nothing could take the message. */
        FAILED,
    }

    /**
     * Sends [text] to [client]: straight into its app's chat when it's installed (a share to that app only, no
     * share sheet; an app that takes no shared text opens with [text] copied), otherwise through the share sheet.
     * [clientId] goes on the clipboard afterwards, for the agent's screen that asks for it.
     */
    fun send(context: Context, client: AgentClient, text: String, clientId: String? = null): Sent {
        val packageName = client.packageName?.takeIf { SystemIntents.openApp(context, it) != null }
        val sent = if (packageName == null) {
            if (Clipboard.share(context, "Send to your agent", text)) Sent.SENT else Sent.FAILED
        } else {
            val share = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(packageName)
            when {
                SystemIntents.launch(context, share) -> Sent.SENT
                else -> SystemIntents.openApp(context, packageName)?.takeIf { SystemIntents.launch(context, it) }?.let {
                    Clipboard.copy(context, "Message", text, false)
                    Sent.COPIED
                } ?: Sent.FAILED
            }
        }
        if (sent == Sent.SENT) clientId?.let { Clipboard.copy(context, "Client ID", it, false) }
        return sent
    }

    /** Opens [link] (in the browser if [OpenLink.inBrowser], or [forceBrowser]). False if nothing could open it. */
    fun open(context: Context, link: OpenLink, forceBrowser: Boolean = false): Boolean =
        if (link.inBrowser || forceBrowser) {
            SystemIntents.launch(context, SystemIntents.inBrowser(context, link.url), SystemIntents.webPage(link.url))
        } else {
            SystemIntents.launch(context, SystemIntents.webPage(link.url))
        }
}
