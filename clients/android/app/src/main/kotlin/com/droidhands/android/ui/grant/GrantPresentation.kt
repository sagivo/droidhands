package com.droidhands.android.ui.grant

import com.droidhands.android.Edition
import com.droidhands.android.ui.apps.AppInfo
import com.droidhands.android.ui.apps.AppSelection
import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.GrantMode
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes

/**
 * Pure text for the grant screen ("Give <agent> access") shown at pairing, when creating a token by hand and
 * in Agents > (agent) > Access, and for summaries of an agent's access elsewhere (unit-tested).
 */
object GrantPresentation {
    fun title(agentName: String): String = "Give ${agentName.trim().ifEmpty { "this agent" }} access"

    const val EVERYTHING = "Everything"
    /** The store edition has no phone use, so it doesn't promise "the screen" (docs/EDITIONS.md). */
    fun everythingDescription(phoneUse: Boolean = Edition.PHONE_USE): String =
        "Every app and ability: messages, calls, ${if (phoneUse) "the screen" else "your calendar"}. It acts without asking you first."

    /**
     * The grant screen's text for [ability]. Each edition only describes what it can do: the store edition has no
     * phone use and doesn't read SMS or call history (docs/EDITIONS.md).
     */
    fun abilityDescription(
        ability: Ability,
        phoneUse: Boolean = Edition.PHONE_USE,
        smsAndCallLog: Boolean = Edition.SMS_AND_CALL_LOG,
    ): String =
        when {
            smsAndCallLog -> ability.description(phoneUse)
            ability == Ability.READ_MESSAGES -> "The notifications on this phone, including message previews."
            ability == Ability.SEND_MESSAGES ->
                "Write texts and emails for you to send, and reply to, act on or dismiss notifications."
            else -> ability.description(phoneUse)
        }

    const val CHOSEN = "Only what I choose"
    const val CHOSEN_DESCRIPTION = "Pick apps and abilities; anything else is refused. Best for agents you don't fully trust."

    const val APPS_HEADER = "Apps"
    const val ALL_APPS = "All apps"
    const val CHOSEN_APPS = "Only apps I choose"
    const val CHOOSE_APPS = "Choose apps"
    const val ABILITIES_HEADER = "What it can do"

    const val ASK_SWITCH = "Ask me before it sends messages, calls or pays"
    const val ASK_SWITCH_OFF = "Off: it never asks."
    const val ASK_SWITCH_ON = "You approve those on this phone. Everything else runs without asking."

    const val CHANGE_NEVER_ALLOW = "Change list"

    /** The never-allow line: these apps stay blocked even with Everything. */
    fun neverAllowLine(count: Int): String = when (count) {
        0 -> "Your never-allow list is empty, so Everything includes banking and password apps."
        1 -> "1 app on your never-allow list stays off-limits, even with Everything."
        else -> "$count apps on your never-allow list stay off-limits, even with Everything."
    }

    /** "Allowed: WhatsApp, Gmail +2", or a nudge when none are chosen. */
    fun chosenAppsLine(packages: Set<String>, labels: Map<String, String>): String =
        if (packages.isEmpty()) "No apps chosen yet." else "Allowed: " + AppSelection.summary(packages, infos(labels))

    /** A partly granted ability (older tokens): which of its parts the agent has, in plain words. */
    fun partlyLine(ability: Ability, scopes: Set<String>): String =
        "Partly: " + ability.scopes.filter { it in scopes }.joinToString("; ") { Scopes.DESCRIPTIONS[it] ?: it }

    /** An older token's blocklist, kept as a third app choice until the user picks another. */
    fun blocklistOption(count: Int): String = "All apps except ${if (count == 1) "1 app" else "$count apps"} you blocked (set earlier)"

    /** An older token's risk cap, shown with a way to remove it; null when there is none. */
    fun capNotice(maxRisk: Risk?): String? = when (maxRisk) {
        Risk.READ -> "Read-only (set earlier): it can look but can't change anything, whatever is ticked below."
        Risk.ACTION -> "Limited earlier: no sending, calling or other hard-to-undo actions, whatever is ticked below."
        Risk.SENSITIVE, null -> null
    }

    const val REMOVE_CAP = "Remove this limit"

    const val EVERY_ACTION_NOTICE = "It also asks you before every other action, like opening apps or tapping (set earlier)."
    const val NARROW_ASKING = "Ask only before messages, calls and payments"

    const val AGENT_ACCESS_LINK_HINT = "Changes apply to its next request."

    // --- Summaries of a saved grant ---------------------------------------------------------------------------

    /** One line for lists: "Everything", "WhatsApp, Gmail · 4 abilities", plus whether it asks first. */
    fun shortSummary(record: TokenRecord, labels: Map<String, String> = emptyMap()): String {
        val grant = GrantDraft.from(record)
        val head = if (grant.mode == GrantMode.EVERYTHING) EVERYTHING else chosenSummary(grant, labels)
        return "$head · ${askSummary(record.confirmPolicy)}"
    }

    private fun chosenSummary(grant: GrantDraft, labels: Map<String, String>): String {
        val apps = appsSummary(grant, labels)
        val n = grant.abilities.size + grant.partialAbilities.size
        return "$apps · ${if (n == 1) "1 ability" else "$n abilities"}"
    }

    /** "All apps", "Only WhatsApp, Gmail", "No apps", "All apps except 2". */
    fun appsSummary(grant: GrantDraft, labels: Map<String, String>): String {
        val apps = grant.effectiveApps
        return when (apps.mode) {
            AppAccessMode.ALL -> ALL_APPS
            AppAccessMode.ALLOWLIST -> if (apps.packages.isEmpty()) {
                "No apps"
            } else {
                "Only " + AppSelection.summary(apps.packages, infos(labels), max = 2)
            }
            AppAccessMode.BLOCKLIST -> "All apps except ${apps.packages.size}"
        }
    }

    private fun infos(labels: Map<String, String>): List<AppInfo> = labels.map { (p, l) -> AppInfo(p, l) }

    fun askSummary(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.NEVER -> "Never asks"
        ConfirmPolicy.SENSITIVE -> "Asks before messages, calls, payments"
        ConfirmPolicy.ALL_ACTIONS -> "Asks before every action"
    }

    /** The lines of the Access card on the agent's page: apps, what it can do (and can't), approvals, older limits. */
    fun details(record: TokenRecord, labels: Map<String, String>): List<String> {
        val grant = GrantDraft.from(record)
        if (grant.mode == GrantMode.EVERYTHING) {
            return listOf("$EVERYTHING: every app and everything DroidHands can do.", askSummary(record.confirmPolicy) + ".")
        }
        return buildList {
            add("Apps: " + appsSummary(grant, labels))
            val offered = Ability.offered(Edition.PHONE_USE)
            val can = offered.filter { it in grant.abilities }.map { it.label } +
                offered.filter { it in grant.partialAbilities }.map { "${it.label} (partly)" }
            add("Can: " + can.ifEmpty { listOf("nothing") }.joinToString(", "))
            val cannot = offered.filter { it !in grant.abilities && it !in grant.partialAbilities }.map { it.label }
            if (cannot.isNotEmpty()) add("Can't: " + cannot.joinToString(", "))
            capNotice(record.maxRisk)?.let { add(it.substringBefore(",") + ".") }
            add(askSummary(record.confirmPolicy) + ".")
        }
    }
}
