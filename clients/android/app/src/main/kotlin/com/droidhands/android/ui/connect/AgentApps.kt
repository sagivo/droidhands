package com.droidhands.android.ui.connect

import java.net.URI

/** An AI agent's own Android app. When it is installed, the Connect page offers it first: one tap adds DroidHands. */
data class AgentApp(val packageName: String, val client: AgentClient)

/** What one tap on an installed agent app does. */
sealed interface OneTap {
    /** Opens [link]: a page or app screen that adds DroidHands (copying the connection URL first when it says so). */
    data class Open(val link: OpenLink) : OneTap

    /** Opens the agent's app ([packageName]) with [message] in its chat, ready to send. */
    data class Send(val packageName: String, val message: String) : OneTap
}

/** Pure part of "Agents on this phone" (unit-tested); the PackageManager side is [AgentAppIntents]. */
internal object AgentApps {
    /**
     * Agent apps by package, in the order they're listed. A new one is one line here (and its guide in
     * [ClientInstructions]); its package also goes in the manifest's `<queries>`.
     */
    val KNOWN: List<AgentApp> = listOf(
        AgentApp("com.anthropic.claude", AgentClient.CLAUDE),
        AgentApp("com.openai.chatgpt", AgentClient.CHATGPT),
        AgentApp("com.facebook.aura", AgentClient.MUSE),
        AgentApp("ai.x.grok.bot", AgentClient.GROK_BOT),
        AgentApp("ai.x.grok", AgentClient.GROK),
    )

    /** The [KNOWN] apps for which [isInstalled] is true, in [KNOWN] order. */
    fun installed(isInstalled: (packageName: String) -> Boolean): List<AgentApp> = KNOWN.filter { isInstalled(it.packageName) }

    /**
     * The one tap for [app], from its [guide]: agents that take a chat message get it in their app (they add
     * DroidHands from the chat); the others open their setup link. Null when the guide has neither.
     */
    fun oneTap(app: AgentApp, guide: ClientGuide): OneTap? = when {
        guide.message != null -> OneTap.Send(app.packageName, guide.message)
        guide.open != null -> OneTap.Open(guide.open)
        else -> null
    }

    /** One line under the app's name saying what the tap does, before it's tapped. */
    fun summary(app: AgentApp, tap: OneTap): String = when (tap) {
        is OneTap.Send -> "Opens ${app.client.label} with a message to send"
        is OneTap.Open -> if (tap.link.copyUrlFirst) {
            "Copies your URL and opens ${hostOf(tap.link.url) ?: app.client.label}"
        } else {
            "Opens ${app.client.label} with DroidHands filled in"
        }
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")
}
