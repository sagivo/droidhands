package com.droidhands.android.ui.connect

import com.droidhands.core.oauth.OAuthClients
import java.net.URLEncoder

/**
 * The agents on the Connect page, in list order; [OTHER] stays last. [packageName] is the agent's Android app:
 * when it's installed, its row shows the app's icon and sending goes straight into it. A new one is one line here
 * plus its guide in [ClientInstructions]; its package also goes in the manifest's `<queries>`.
 */
enum class AgentClient(val label: String, val packageName: String? = null) {
    CLAUDE("Claude", "com.anthropic.claude"),
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    MUSE("Muse", "com.facebook.aura"),
    GROK("Grok", "ai.x.grok"),
    GROK_BOT("Grok Bot", "ai.x.grok.bot"),
    OTHER("Other agent"),
}

/**
 * A link that does the setup in the agent itself. [copyUrlFirst]: put the connection URL on the clipboard before
 * opening. [inBrowser]: a web-only settings page, opened in the browser even when the agent's app claims the link.
 */
data class OpenLink(val label: String, val url: String, val copyUrlFirst: Boolean = false, val inBrowser: Boolean = false)

/**
 * How to add DroidHands to one agent: exactly one action, either an [open] link or a chat [message] to send it,
 * then at most two short [steps]. The phone's own "Tap Allow" is shown after them.
 */
data class ClientGuide(
    val client: AgentClient,
    val steps: List<String>,
    val open: OpenLink? = null,
    val message: String? = null,
    /** A client ID the agent's own screen asks for: put on the clipboard when the message is sent, so it's a paste. */
    val clientId: String? = null,
)

/** Pure per-agent setup for the connection URL (unit-tested). */
object ClientInstructions {
    /** Name of the connector in Claude and ChatGPT, where people see it next to other connectors. */
    const val CONNECTOR_NAME = "DroidHands"

    /** ChatGPT's Plugins page, where custom MCP servers are added (formerly Connectors, behind Developer mode). */
    const val CHATGPT_CONNECTORS = "https://chatgpt.com/plugins"

    /** Grok's connector settings (custom connectors need a paid plan). */
    const val GROK_CONNECTORS = "https://grok.com/connectors"

    fun guide(client: AgentClient, url: String): ClientGuide = when (client) {
        AgentClient.CLAUDE -> ClientGuide(
            client = client,
            open = OpenLink("Add to Claude", claudeAddLink(url)),
            steps = listOf("Claude opens with DroidHands filled in. Tap Add, then Connect."),
        )
        AgentClient.CHATGPT -> ClientGuide(
            client = client,
            // Custom MCP servers are added on chatgpt.com only, not in the ChatGPT app.
            open = OpenLink("Copy URL and open ChatGPT", CHATGPT_CONNECTORS, copyUrlFirst = true, inBrowser = true),
            steps = listOf(
                "Stay in the browser if it offers the ChatGPT app. Tap Add, then Add custom MCP server.",
                "Name it $CONNECTOR_NAME, paste the URL, keep OAuth, tick I understand, then Create as a plugin.",
            ),
        )
        AgentClient.GROK -> ClientGuide(
            client = client,
            open = OpenLink("Copy URL and open Grok", GROK_CONNECTORS, copyUrlFirst = true, inBrowser = true),
            steps = listOf("Tap New Connector, then Custom. Paste the URL and tap Connect. Needs a paid Grok plan."),
        )
        AgentClient.MUSE -> ClientGuide(
            client = client,
            message = agentMessage(url),
            clientId = OAuthClients.PUBLIC_CLIENT_ID,
            steps = listOf(
                "Send the message in Muse, then tap Connect on the card it shows.",
                "If Muse asks for a Client ID, paste it (droidhands is copied). Tap Add.",
            ),
        )
        AgentClient.GROK_BOT -> ClientGuide(
            client = client,
            message = agentMessage(url),
            steps = listOf(
                "Send the message in Grok Bot, then tap Authorize on the card it shows.",
                "No card? In Grok Bot, open Connect apps › Your plugins and tap Authorize next to droidhands.",
            ),
        )
        AgentClient.OTHER -> ClientGuide(
            client = client,
            message = agentMessage(url),
            steps = listOf("Send this to your agent, in its chat or on the computer it runs on."),
        )
    }

    /**
     * claude.ai's documented install link for a custom connector: it opens "Add custom connector" with the
     * name and URL filled in, and the user still confirms there.
     */
    fun claudeAddLink(url: String): String =
        "https://claude.ai/customize/connectors?modal=add-custom-connector" +
            "&connectorName=${encode(CONNECTOR_NAME)}&connectorUrl=${encode(url)}"

    /**
     * One line to send any agent. Agents that add connectors (Muse, Grok Bot, coding agents) add [url] and sign
     * in like any connector; the others open it, which answers agents with sign-in instructions and people
     * with a setup page.
     */
    fun agentMessage(url: String): String =
        "Connect to my phone with DroidHands: add $url as a custom connector (remote MCP server, OAuth sign-in; " +
            "if it needs a client ID, use droidhands with no secret) and connect to it. No connector settings? Open " +
            "that link: it shows how to sign in yourself."

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
