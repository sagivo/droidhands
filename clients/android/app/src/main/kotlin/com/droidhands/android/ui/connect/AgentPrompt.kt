package com.droidhands.android.ui.connect

/**
 * The one message that adds DroidHands to any agent, copied or shared from the Connect page (and, with a token,
 * from the token screen). The agent-specific steps live in [INSTRUCTIONS_URL], written for agents, so the app
 * stays the same for every one of them. Pure, unit-tested.
 */
object AgentPrompt {
    /** Name of the connector, next to the person's other connectors. */
    const val CONNECTOR_NAME = "DroidHands"

    /** The full instructions for agents (website/public/connect.md). */
    const val INSTRUCTIONS_URL = "https://droidhands.com/connect.md"

    /** For a URL the agent signs in to: the person only taps Allow, so nothing secret is in it. */
    fun signIn(url: String): String =
        "${intro(url)} No password or token is needed: I'll tap Allow in the DroidHands app on my phone."

    /** For a [token] made on the phone, sent as a bearer token; [extraStep] is what the agent does first, if anything. */
    fun withToken(url: String, token: String, extraStep: String? = null): String =
        "${intro(url)} Use this token as the bearer token: $token" + (extraStep?.let { " $it" } ?: "")

    private fun intro(url: String): String =
        "Add a new connector named $CONNECTOR_NAME with the URL $url and follow the instructions at $INSTRUCTIONS_URL."
}
