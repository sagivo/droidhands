package com.droidhands.android.ui.connect

import com.droidhands.android.ui.tokens.ConnectionSnippets
import java.net.URLEncoder

/** MCP clients with tailored setup on the Connect page (chip order: most used first). */
enum class AgentClient(val label: String) {
    CLAUDE("Claude"),
    CHATGPT("ChatGPT"),
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
    GEMINI_CLI("Gemini CLI"),
    OTHER("Other"),
}

/** A link that does the setup in the agent itself. [copyUrlFirst]: put the connection URL on the clipboard before opening. */
data class OpenLink(val label: String, val url: String, val copyUrlFirst: Boolean = false)

/**
 * How to add the connection URL to one client: at most one [open] link (the main action) or one [command]
 * to copy, and one or two short [steps]. The phone's own "tap Allow" is shown once, under the guide.
 */
data class ClientGuide(
    val client: AgentClient,
    /** "claude.ai and the Claude apps": where this guide applies. */
    val appliesTo: String,
    val steps: List<String>,
    val open: OpenLink? = null,
    val command: String? = null,
) {
    /** True for clients that run on a computer: the command has to get there (Share). */
    val onComputer: Boolean get() = command != null
}

/** Pure per-client setup for the connection URL (unit-tested). */
object ClientInstructions {
    /** Name of the server/connector in every client. */
    val SERVER_NAME: String get() = ConnectionSnippets.SERVER_NAME

    /** Name of the connector in Claude and ChatGPT, where people see it next to other connectors. */
    const val CONNECTOR_NAME = "DroidHands"

    /** ChatGPT's connector settings. */
    const val CHATGPT_CONNECTORS = "https://chatgpt.com/#settings/Connectors"

    fun guide(client: AgentClient, url: String): ClientGuide = when (client) {
        AgentClient.CLAUDE -> ClientGuide(
            client = client,
            appliesTo = "claude.ai and the Claude apps",
            open = OpenLink("Add to Claude", claudeAddLink(url)),
            steps = listOf("Claude opens with DroidHands filled in. Tap Add, then Connect."),
        )
        AgentClient.CHATGPT -> ClientGuide(
            client = client,
            appliesTo = "ChatGPT on the web, with Developer mode",
            open = OpenLink("Copy URL and open ChatGPT", CHATGPT_CONNECTORS, copyUrlFirst = true),
            steps = listOf(
                "Under Advanced, turn on Developer mode.",
                "Tap Create, paste the URL, choose OAuth, then Create.",
            ),
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

    /** Text for the share sheet: the URL plus one line, so it still makes sense on another device. */
    fun shareText(url: String): String = "Add this phone to your AI agent as a custom connector (remote MCP server):\n$url"

    /** The command plus one line on where to run it, for sending to a computer. */
    fun shareCommand(guide: ClientGuide): String =
        "Run this where ${guide.client.label} runs, then approve on the phone:\n${guide.command}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
