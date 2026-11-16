package com.droidhands.android.ui.connect

import com.droidhands.android.ui.tokens.ConnectionSnippets

/** MCP clients with tailored instructions on the Connect page (tab order). */
enum class AgentClient(val label: String) {
    CLAUDE("Claude"),
    CLAUDE_CODE("Claude Code"),
    CHATGPT("ChatGPT"),
    OTHER("Other"),
}

/**
 * How to add the connector URL to one client. [steps] come before the optional [command] (shown
 * copyable), [afterCommand] after it. Every guide ends on the phone: "tap Allow".
 */
data class ClientGuide(
    val client: AgentClient,
    val appliesTo: String,
    val steps: List<String>,
    val command: String? = null,
    val afterCommand: List<String> = emptyList(),
)

/** Pure per-client setup instructions for the connector URL (unit-tested). */
object ClientInstructions {
    const val APPROVE_STEP = "A prompt appears on this phone: check the code matches the one in your browser, then tap Allow."

    fun guide(client: AgentClient, url: String): ClientGuide = when (client) {
        AgentClient.CLAUDE -> ClientGuide(
            client = client,
            appliesTo = "claude.ai, Claude Desktop and the Claude mobile apps",
            steps = listOf(
                "Open Settings → Connectors → Add custom connector.",
                "Name it \"Phone\" and paste your connection URL.",
                "Tap Add, then Connect. Your browser opens a DroidHands page.",
                APPROVE_STEP,
            ),
        )
        AgentClient.CLAUDE_CODE -> ClientGuide(
            client = client,
            appliesTo = "Claude Code in a terminal",
            steps = listOf("Run this on the computer where Claude Code runs:"),
            command = claudeCodeCommand(url),
            afterCommand = listOf(
                "In Claude Code, type /mcp, pick \"${ConnectionSnippets.SERVER_NAME}\" and choose Authenticate. Your browser opens.",
                APPROVE_STEP,
            ),
        )
        AgentClient.CHATGPT -> ClientGuide(
            client = client,
            appliesTo = "ChatGPT on the web (plans with Developer mode)",
            steps = listOf(
                "Open Settings → Connectors → Advanced and turn on Developer mode.",
                "Back in Connectors, tap Create. Name it \"Phone\" and paste your connection URL.",
                "Choose OAuth as the authentication, then Create. Your browser opens a DroidHands page.",
                APPROVE_STEP,
            ),
        )
        AgentClient.OTHER -> ClientGuide(
            client = client,
            appliesTo = "Any MCP client that supports remote servers with OAuth",
            steps = listOf(
                "Add a remote MCP server (Streamable HTTP) and paste your connection URL.",
                "When the client asks you to sign in, your browser opens a DroidHands page.",
                APPROVE_STEP,
            ),
        )
    }

    /** `claude mcp add --transport http phone <url>` — OAuth happens on first use, no token needed. */
    fun claudeCodeCommand(url: String): String =
        "claude mcp add --transport http ${ConnectionSnippets.SERVER_NAME} ${ConnectionSnippets.shellQuote(url)}"

    /** Text for the share sheet: the URL plus a one-line hint, so it is useful when sent to a computer. */
    fun shareText(url: String): String = "DroidHands connection URL (add it as a custom connector / remote MCP server):\n$url"
}
