package dev.phonebridge.android.ui.tokens

import dev.phonebridge.android.ui.format.TimeFormat
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes

/** Display state of a token. A revoked token stays REVOKED even after its expiry passes. */
enum class TokenState { ACTIVE, REVOKED, EXPIRED }

/** Expiry choices offered when creating a token. */
enum class ExpiryOption(val label: String, val ttlMillis: Long?) {
    NEVER("Never", null),
    DAY_1("1 day", 1 * DAY_MS),
    DAYS_7("7 days", 7 * DAY_MS),
    DAYS_30("30 days", 30 * DAY_MS),
    DAYS_90("90 days", 90 * DAY_MS),
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Pure text/state helpers for the Tokens screens (unit-tested). */
object TokenPresentation {
    /** Scopes that effectively hand over the whole phone; highlighted in the editor. */
    val HIGH_RISK_SCOPES: Set<String> = setOf(Scopes.SCREEN_CONTROL, Scopes.INTENTS)

    fun state(record: TokenRecord, now: Long): TokenState {
        val expiresAt = record.expiresAt
        return when {
            record.revoked -> TokenState.REVOKED
            expiresAt != null && expiresAt <= now -> TokenState.EXPIRED
            else -> TokenState.ACTIVE
        }
    }

    /**
     * Name of the preset whose scopes and risk cap equal [scopes] and [maxRisk] exactly, or null. The
     * Read-only scopes without the Read-only cap are not Read-only: such an agent can still act.
     */
    fun presetFor(scopes: Set<String>, maxRisk: Risk?): String? =
        Scopes.PRESETS.entries.firstOrNull { (name, presetScopes) -> presetScopes == scopes && presetCap(name) == maxRisk }?.key

    /** The risk cap that comes with preset [name] (Read-only: [Risk.READ]); null for no cap. */
    fun presetCap(name: String): Risk? = Scopes.PRESET_MAX_RISK[name]

    /**
     * Cap for a selection of [scopes] made by hand: the preset's cap when they are exactly a preset's
     * scopes, otherwise none (a custom selection gets what its scopes allow).
     */
    fun capForScopes(scopes: Set<String>): Risk? =
        Scopes.PRESETS.entries.firstOrNull { it.value == scopes }?.let { presetCap(it.key) }

    /**
     * What a risk cap means for the agent, or null for no cap. With [scopes] equal to the Read-only
     * scopes but no cap (agents created before the cap existed), warns that the agent can still act.
     */
    fun capNotice(scopes: Set<String>, maxRisk: Risk?): String? = when (maxRisk) {
        Risk.READ -> READ_ONLY_CAP
        Risk.ACTION -> "Sensitive tools (sending messages, calls, notification replies, raw intents) are hidden from this " +
            "agent and refused."
        Risk.SENSITIVE -> null
        null -> if (scopes == Scopes.PRESET_READ_ONLY) UNCAPPED_READ_ONLY else null
    }

    const val READ_ONLY_CAP =
        "Read-only: can look but never act. Tools that change anything (opening apps or links, volume, ringer, alarms, " +
            "clipboard, messages) are hidden from this agent and refused."

    const val UNCAPPED_READ_ONLY =
        "These are the Read-only capabilities without the Read-only limit, so this agent can still act: change volume, " +
            "ringer, clipboard and alarms, speak, and open apps and links. Choose Read-only to block every action."

    /** "Assistant", "contacts.read, messages.send", or "Custom · 5 scopes". */
    fun scopeSummary(scopes: Set<String>, maxRisk: Risk?): String {
        presetFor(scopes, maxRisk)?.let { return it }
        if (scopes.isEmpty()) return "No scopes"
        val ordered = orderedScopes(scopes)
        return if (ordered.size <= 3) ordered.joinToString(", ") else "Custom · ${ordered.size} scopes"
    }

    /** [scopes] in the catalog order of [Scopes.DESCRIPTIONS]; unknown scopes last, alphabetically. */
    fun orderedScopes(scopes: Set<String>): List<String> {
        val known = Scopes.DESCRIPTIONS.keys.filter { it in scopes }
        return known + (scopes - Scopes.DESCRIPTIONS.keys).sorted()
    }

    fun policyLabel(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.NEVER -> "Never ask"
        ConfirmPolicy.SENSITIVE -> "Ask for sensitive actions"
        ConfirmPolicy.ALL_ACTIONS -> "Ask for every action"
    }

    fun policyDescription(policy: ConfirmPolicy): String = when (policy) {
        ConfirmPolicy.NEVER -> "The agent acts without asking you. Use only for agents you fully trust."
        ConfirmPolicy.SENSITIVE ->
            "Recommended. You approve sending messages, calls, notification replies and raw intents on the phone."
        ConfirmPolicy.ALL_ACTIONS -> "You also approve taps, typing, opening apps and other changes. Reading never asks."
    }

    const val NEVER_POLICY_WARNING =
        "No approval prompts: if this agent is tricked by a message or web page it reads (prompt injection), " +
            "it can send messages and place calls as you without asking."

    fun expiryText(expiresAt: Long?, now: Long): String = when {
        expiresAt == null -> "Never expires"
        expiresAt <= now -> "Expired ${TimeFormat.relative(expiresAt, now)}"
        else -> "Expires ${TimeFormat.relative(expiresAt, now)}"
    }

    fun lastUsedText(lastUsedAt: Long?, now: Long): String =
        if (lastUsedAt == null) "Never used" else "Used ${TimeFormat.relative(lastUsedAt, now)}"

    fun createdText(createdAt: Long, now: Long): String = "Created ${TimeFormat.relative(createdAt, now)}"

    /** [base], or "[base] 2", "[base] 3"… so a new token doesn't collide with an existing name. */
    fun defaultName(existing: Collection<String>, base: String = "Claude"): String {
        val taken = existing.map { it.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }
}
