package com.droidhands.android.ui.connect

import com.droidhands.android.ui.tokens.ConnectionSnippets
import com.droidhands.core.oauth.OAuthClients
import java.net.URLEncoder

/** AI agents with tailored setup on the Connect page (chip order: most used first; Other stays last). */
enum class AgentClient(val label: String) {
    CLAUDE("Claude"),
    CHATGPT("ChatGPT"),
    GROK("Grok"),
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
    GEMINI_CLI("Gemini CLI"),
    MUSE("Muse"),
    GROK_BOT("Grok Bot"),
    INSTINCT("Instinct"),
    OTHER("Other"),
}

/**
 * A link that does the setup in the agent itself. [copyUrlFirst]: put the connection URL on the clipboard before
 * opening. [inBrowser]: a web-only settings page, opened in the browser even when the agent's app claims the link.
 */
data class OpenLink(val label: String, val url: String, val copyUrlFirst: Boolean = false, val inBrowser: Boolean = false)

/**
 * How to add the connection URL to one client: at most one main action, either an [open] link, a [command]
 * to copy, or a chat [message] for agents without connector settings (they open the link and sign in from
 * the chat), and one or two short [steps]. The phone's own "tap Allow" is shown once, under the guide.
 * [note] is an aside under the card.
 */
data class ClientGuide(
    val client: AgentClient,
    /** "claude.ai and the Claude apps": where this guide applies. */
    val appliesTo: String,
    val steps: List<String>,
    val open: OpenLink? = null,
    val command: String? = null,
    val message: String? = null,
    val note: String? = null,
    /** A client ID the agent's own screen asks for: put on the clipboard when its app opens, so it's a paste. */
    val clientId: String? = null,
) {
    /** True when the agent signs in from its chat and shows the code there, not in a browser. */
    val viaChat: Boolean get() = message != null

    /** True for clients that run on a computer: the command has to get there (Share). */
    val onComputer: Boolean get() = command != null
}

/** Pure per-client setup for the connection URL (unit-tested). */
object ClientInstructions {
    /** Name of the server/connector in every client. */
    val SERVER_NAME: String get() = ConnectionSnippets.SERVER_NAME

    /** Name of the connector in Claude and ChatGPT, where people see it next to other connectors. */
    const val CONNECTOR_NAME = "DroidHands"

    /** ChatGPT's Plugins page, where custom MCP servers are added (formerly Connectors, behind Developer mode). */
    const val CHATGPT_CONNECTORS = "https://chatgpt.com/plugins"

    /** Grok's connector settings (custom connectors need a paid plan). */
    const val GROK_CONNECTORS = "https://grok.com/connectors"

    fun guide(client: AgentClient, url: String): ClientGuide = when (client) {
        AgentClient.CLAUDE -> ClientGuide(
            client = client,
            appliesTo = "claude.ai and the Claude apps",
            open = OpenLink("Add to Claude", claudeAddLink(url)),
            steps = listOf("Claude opens with DroidHands filled in. Tap Add, then Connect."),
        )
        AgentClient.CHATGPT -> ClientGuide(
            client = client,
            appliesTo = "ChatGPT on the web",
            // Custom MCP servers are added on chatgpt.com only, not in the ChatGPT app.
            open = OpenLink("Copy URL and open ChatGPT", CHATGPT_CONNECTORS, copyUrlFirst = true, inBrowser = true),
            steps = listOf(
                "Stay in the browser if it offers the ChatGPT app. Tap Add, then Add custom MCP server.",
                "Name it $CONNECTOR_NAME, paste the URL, keep OAuth, tick I understand, then Create as a plugin.",
            ),
        )
        AgentClient.GROK -> ClientGuide(
            client = client,
            appliesTo = "grok.com and the Grok apps, on paid plans",
            open = OpenLink("Copy URL and open Grok", GROK_CONNECTORS, copyUrlFirst = true, inBrowser = true),
            steps = listOf("Tap New Connector, then Custom. Paste the URL and tap Connect."),
        )
        AgentClient.MUSE -> ClientGuide(
            client = client,
            appliesTo = "Meta Muse, in chat",
            message = agentMessage(url),
            clientId = OAuthClients.PUBLIC_CLIENT_ID,
            steps = listOf(
                "Send the message to Muse, then tap Connect on the card it shows.",
                "If Muse asks for a Client ID, paste it (droidhands is copied). Tap Add.",
            ),
        )
        AgentClient.GROK_BOT -> ClientGuide(
            client = client,
            appliesTo = "Grok Bot, on your phone or computer",
            message = agentMessage(url),
            steps = listOf(
                "Send the message to your Bot, then tap Authorize on the card it shows.",
                "No card? In Grok Bot, open Connect apps › Your plugins and tap Authorize next to droidhands.",
            ),
        )
        AgentClient.INSTINCT -> ClientGuide(
            client = client,
            appliesTo = "Instinct, by text",
            message = agentMessage(url),
            steps = listOf("Text this to Instinct. It texts you back a 4-letter code."),
        )
        AgentClient.CLAUDE_CODE -> ClientGuide(
            client = client,
            appliesTo = "Claude Code on your computer",
            command = claudeCodeCommand(url),
            steps = listOf("Then type /mcp in Claude Code, pick $SERVER_NAME and choose Authenticate."),
        )
        AgentClient.CODEX -> ClientGuide(
            client = client,
            appliesTo = "Codex CLI on your computer",
            command = "codex mcp add $SERVER_NAME --url ${ConnectionSnippets.shellQuote(url)}",
            steps = listOf("Your browser opens to sign in. If it doesn't, run codex mcp login $SERVER_NAME."),
        )
        AgentClient.GEMINI_CLI -> ClientGuide(
            client = client,
            appliesTo = "Gemini CLI on your computer",
            command = "gemini mcp add --transport http -s user $SERVER_NAME ${ConnectionSnippets.shellQuote(url)}",
            steps = listOf("Then type /mcp auth $SERVER_NAME in Gemini CLI."),
        )
        AgentClient.OTHER -> ClientGuide(
            client = client,
            appliesTo = "any MCP client with remote servers and OAuth",
            steps = listOf("Add a remote MCP server (Streamable HTTP) with your connection URL. Sign-in opens in your browser."),
            note = "No connector settings? Share the URL into your agent's chat: it opens the link and signs in from there.",
        )
    }

    /** `claude mcp add --transport http phone <url>`. OAuth happens on first use, so no token is needed. */
    fun claudeCodeCommand(url: String): String =
        "claude mcp add --transport http $SERVER_NAME ${ConnectionSnippets.shellQuote(url)}"

    /**
     * claude.ai's documented install link for a custom connector: it opens "Add custom connector" with the
     * name and URL filled in, and the user still confirms there.
     */
    fun claudeAddLink(url: String): String =
        "https://claude.ai/customize/connectors?modal=add-custom-connector" +
            "&connectorName=${encode(CONNECTOR_NAME)}&connectorUrl=${encode(url)}"

    /**
     * One line to send an agent in chat. Agents that add connectors from chat (Muse, Grok Bot) add [url] and sign
     * in like any connector; the others open it, which answers agents with sign-in instructions.
     */
    fun agentMessage(url: String): String =
        "Connect to my phone with DroidHands: add $url as a custom connector (remote MCP server, OAuth sign-in; " +
            "if it needs a client ID, use droidhands with no secret) and connect to it. No connector settings? Open " +
            "that link: it shows how to sign in yourself."

    /**
     * Text for the share sheet: one line plus the URL. It works sent to a computer (open it for setup buttons,
     * or paste it into a connector) and shared straight into an agent's chat (the agent opens the link).
     */
    fun shareText(url: String): String =
        "Connect to my phone with DroidHands (MCP server). Open this link to set it up; AI agents get instructions there.\n$url"

    /** The command plus one line on where to run it, for sending to a computer. */
    fun shareCommand(guide: ClientGuide): String =
        "Run this where ${guide.client.label} runs, then approve on the phone:\n${guide.command}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
