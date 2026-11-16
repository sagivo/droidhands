package com.droidhands.core.auth

/**
 * Agent-facing refusals for requests outside an agent's grant (pure). They are refused at once, never turned
 * into a prompt; the text names what is missing and where the user can add it, so the agent can tell them.
 */
object GrantText {
    /** Where the user changes [agentName]'s grant: "DroidHands > Agents > Claude > Access". */
    fun accessPath(agentName: String): String = "DroidHands > Agents > ${display(agentName)} > Access"

    /** "this agent" for a blank name, otherwise the name. */
    fun display(agentName: String): String = agentName.trim().ifEmpty { "this agent" }

    /** [display], capitalized to start a sentence. */
    private fun subject(agentName: String): String = display(agentName).replaceFirstChar { it.uppercaseChar() }

    /**
     * [toolName] needs [scope], which [agentName]'s grant lacks: "Claude isn't allowed to make or answer calls
     * ('phone_call' needs that). The user can allow it in DroidHands > Agents > Claude > Access. …".
     */
    fun missingScope(agentName: String, scope: String, toolName: String): String {
        val name = subject(agentName)
        val what = Ability.of(scope)?.phrase ?: "use '$toolName' (it needs the '$scope' scope)"
        val need = if (Ability.of(scope) == null) "" else " ('$toolName' needs that)"
        return "$name isn't allowed to $what$need. The user can allow it in ${accessPath(agentName)}. $NO_WORKAROUND"
    }

    /**
     * The app [packageName] ([label] when known) is outside [agentName]'s grant, or on the never-allow list
     * ([globally]), which applies to every agent whatever its grant.
     */
    fun appNotAllowed(agentName: String, packageName: String, label: String?, globally: Boolean): String {
        val app = label?.trim()?.takeIf { it.isNotEmpty() && it != packageName }?.let { "$it ($packageName)" } ?: packageName
        val workaround = "Do not try to work around this (for example through other apps, links, intents, notifications or screen control)."
        return if (globally) {
            "$app is on DroidHands's never-allow list, so no agent may use it. $workaround If the task needs this app, tell the " +
                "user and let them do it themselves."
        } else {
            "${subject(agentName)} isn't allowed to use $app. The user can allow it in ${accessPath(agentName)}. $workaround " +
                "Tell the user what to allow instead."
        }
    }

    /** A tool above an older token's risk cap ([com.droidhands.core.tools.Risk]); [limit] says what the cap allows. */
    fun aboveRiskCap(agentName: String, toolName: String, limit: String): String =
        "${subject(agentName)} may only use $limit, so '$toolName' is not available. The user can change this in " +
            "${accessPath(agentName)}. $NO_WORKAROUND"

    /** A tool the user blocked with a per-tool rule. */
    fun toolBlocked(agentName: String, toolName: String): String =
        "The user blocked the tool '$toolName' for ${display(agentName)}. The user can change this in " +
            "DroidHands > Agents > ${display(agentName)} > Tool rules. $NO_WORKAROUND"

    private const val NO_WORKAROUND = "Do not try to work around this (for example with other tools); tell the user what to allow instead."
}
