package com.droidhands.core.mcp

import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.GrantText
import com.droidhands.core.auth.ToolRule
import com.droidhands.core.tools.AppAccess
import com.droidhands.core.tools.Risk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tells an agent its grant up front (docs/SECURITY.md "Access"): which apps, which abilities, and that actions
 * inside it run without asking the user, so it doesn't try things outside it. Pure. Used for the MCP
 * `initialize` instructions and `bridge_capabilities` (`token.access`).
 *
 * Privacy: like [PolicySummary], never names blocked or never-allowed apps; only apps the agent may use.
 */
internal object AccessSummary {
    /** At most this many allowed apps are named in the instructions; bridge_capabilities lists them all. */
    const val MAX_NAMED_APPS = 30

    /** One token's access, as the summaries need it. */
    class Facts(
        val agentName: String,
        val scopes: Set<String>,
        val apps: AppAccess,
        /** Null when unknown (then approvals are not described). */
        val confirmPolicy: ConfirmPolicy?,
        val maxRisk: Risk?,
        val toolRules: Map<String, ToolRule>,
    ) {
        val abilities: List<Ability> get() = Ability.entries.filter { a -> a.scopes.all { it in scopes } }
        val partial: List<Ability> get() = Ability.entries.filter { a -> a.scopes.any { it in scopes } && !a.scopes.all { it in scopes } }
        val missing: List<Ability> get() = Ability.entries.filter { a -> a.scopes.none { it in scopes } }
        val asksFor: List<String> get() = toolRules.filterValues { it == ToolRule.ASK }.keys.sorted()
    }

    /** The paragraph appended to the server's instructions for [facts]. [label] names apps (null: package only). */
    fun instructions(facts: Facts, label: (String) -> String?): String = buildString {
        append("Your access on this phone (the user chose it when they connected you, as \"")
        append(GrantText.display(facts.agentName)).append("\"):\n")
        append("- Apps: ").append(appsLine(facts.apps, label)).append('\n')
        append("- You may: ").append(abilitiesLine(facts)).append('\n')
        if (facts.missing.isNotEmpty()) {
            append("- You may not: ").append(facts.missing.joinToString("; ") { it.phrase }).append(". Such requests are refused.\n")
        }
        facts.maxRisk?.let { append("- Limit: ").append(capLine(it)).append('\n') }
        facts.confirmPolicy?.let { append("- Approvals: ").append(approvalLine(it, facts.asksFor)).append('\n') }
        append("Stay inside this access. If a task needs more, don't look for workarounds: tell the user what to allow in ")
        append(GrantText.accessPath(facts.agentName)).append('.')
    }

    /** `token.access` of bridge_capabilities. */
    fun json(facts: Facts): JsonObject = buildJsonObject {
        putJsonArray("abilities") { facts.abilities.forEach { add(JsonPrimitive(it.phrase)) } }
        if (facts.partial.isNotEmpty()) putJsonArray("partly") { facts.partial.forEach { add(JsonPrimitive(it.phrase)) } }
        putJsonArray("not_allowed") { facts.missing.forEach { add(JsonPrimitive(it.phrase)) } }
        facts.confirmPolicy?.let {
            put("asks_user_before", askScope(it))
            put("approvals", approvalLine(it, facts.asksFor))
        }
        put("change_in", GrantText.accessPath(facts.agentName))
    }

    private fun appsLine(apps: AppAccess, label: (String) -> String?): String {
        val except = if (apps.neverAllow.isEmpty()) "" else ", except any the user never allows for agents"
        return when (apps.mode) {
            AppAccessMode.ALL -> "every app$except."
            AppAccessMode.BLOCKLIST -> "every app except ${apps.packages.size} the user blocked for you$except."
            AppAccessMode.ALLOWLIST -> {
                val allowed = apps.packages.filter(apps::isAllowed).sorted()
                if (allowed.isEmpty()) {
                    "none: the user hasn't allowed any app for you (contacts, SMS, calendar and the other abilities below still work)."
                } else {
                    val named = allowed.take(MAX_NAMED_APPS).joinToString(", ") { pkg ->
                        label(pkg)?.trim()?.takeIf { it.isNotEmpty() && it != pkg }?.let { "$it ($pkg)" } ?: pkg
                    }
                    val more = if (allowed.size > MAX_NAMED_APPS) " and ${allowed.size - MAX_NAMED_APPS} more (see bridge_capabilities)" else ""
                    "only $named$more. Other apps are hidden from you and refused."
                }
            }
        }
    }

    private fun abilitiesLine(facts: Facts): String {
        val full = facts.abilities.map { it.phrase }
        val partly = facts.partial.map { "partly ${it.phrase}" }
        val all = full + partly
        return when {
            facts.missing.isEmpty() && facts.partial.isEmpty() -> "everything DroidHands offers (${all.joinToString("; ")})."
            all.isEmpty() -> "nothing beyond describing this connection."
            else -> all.joinToString("; ") + "."
        }
    }

    private fun capLine(cap: Risk): String = when (cap) {
        Risk.READ -> "read-only tools: you may look but not change anything on the phone."
        Risk.ACTION -> "no sending, calling or other hard-to-undo actions."
        Risk.SENSITIVE -> "none."
    }

    private fun askScope(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.NEVER -> "nothing"
        ConfirmPolicy.SENSITIVE -> "sensitive actions"
        ConfirmPolicy.ALL_ACTIONS -> "every action"
    }

    private fun approvalLine(policy: ConfirmPolicy, asksFor: List<String>): String {
        val base = when (policy) {
            ConfirmPolicy.NEVER -> "none. Actions inside this access run without asking the user, so be sure of the request first."
            ConfirmPolicy.SENSITIVE ->
                "sending messages, calls, payments and other sensitive actions wait for the user to approve them on the phone; " +
                    "everything else runs without asking."
            ConfirmPolicy.ALL_ACTIONS -> "every action that changes something waits for the user to approve it on the phone; reading doesn't."
        }
        return if (asksFor.isEmpty()) base else "$base The user also asked to approve each call of: ${asksFor.joinToString(", ")}."
    }
}
