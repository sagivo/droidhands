package com.droidhands.core.auth

import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes

/**
 * What an agent may do, in plain words, as offered on the grant screen (docs/SECURITY.md "Access"). Each
 * ability is a fixed set of [Scopes]; together they cover every scope exactly once, so nothing is hidden.
 *
 * [label] and [description] are for the phone's UI; [phrase] completes "<agent> may / isn't allowed to …"
 * in agent-facing text.
 */
enum class Ability(val label: String, val description: String, val phrase: String, val scopes: Set<String>) {
    READ_MESSAGES(
        "Read messages & notifications",
        "SMS, call history and the notifications on this phone, including message previews.",
        "read messages, call history and notifications",
        setOf(Scopes.MESSAGES_READ, Scopes.NOTIFICATIONS_READ),
    ),
    READ_CONTACTS_CALENDAR(
        "Read contacts & calendar",
        "Look up contacts and read calendar events.",
        "read contacts and the calendar",
        setOf(Scopes.CONTACTS_READ, Scopes.CALENDAR_READ),
    ),
    LOCATION("Location", "Where the phone is right now.", "see the phone's location", setOf(Scopes.LOCATION)),
    SEE_SCREEN(
        "See the screen",
        "Read what the allowed apps show, and take screenshots.",
        "see the screen",
        setOf(Scopes.SCREEN_READ),
    ),
    USE_APPS(
        "Use apps",
        "Open the allowed apps and links, run their actions, and tap, type and scroll in them.",
        "use apps (open them, run their actions, tap and type)",
        setOf(Scopes.APPS, Scopes.SCREEN_CONTROL),
    ),
    SEND_MESSAGES(
        "Send messages",
        "Send SMS, WhatsApp and email, and reply to, act on or dismiss notifications.",
        "send messages or act on notifications",
        setOf(Scopes.MESSAGES_SEND, Scopes.NOTIFICATIONS_ACT),
    ),
    CALLS("Make calls", "Place, answer and end phone calls.", "make or answer calls", setOf(Scopes.CALLS)),
    CHANGE_CALENDAR("Change calendar", "Add events to your calendar.", "add calendar events", setOf(Scopes.CALENDAR_WRITE)),
    DEVICE(
        "Device settings",
        "Volume, ringer, flashlight, media, alarms and timers, the clipboard, and speaking out loud.",
        "change device settings (volume, ringer, alarms, clipboard…)",
        setOf(Scopes.DEVICE),
    ),
    ADVANCED(
        "Start any app screen (advanced)",
        "Open any screen of any app directly, with any data. For developers; it can do things the choices above don't.",
        "start Android activities directly",
        setOf(Scopes.INTENTS),
    ),
    ;

    /** Only phone use: not offered on a device without it (the Android store edition). */
    val phoneUseOnly: Boolean get() = scopes.all { it in Scopes.PHONE_USE }

    /** The scopes that count on a device with or without phone use. */
    fun effectiveScopes(phoneUse: Boolean): Set<String> = if (phoneUse) scopes else scopes - Scopes.PHONE_USE

    /** [description] for a device with or without phone use. */
    fun description(phoneUse: Boolean): String =
        if (!phoneUse && this == USE_APPS) "Open the allowed apps and links, and run their actions." else description

    /** [phrase] for a device with or without phone use. */
    fun phrase(phoneUse: Boolean): String =
        if (!phoneUse && this == USE_APPS) "use apps (open them and run their actions)" else phrase

    companion object {
        /** The abilities a device offers: all of them, or without the phone-use-only ones. */
        fun offered(phoneUse: Boolean): List<Ability> = if (phoneUse) entries else entries.filterNot { it.phoneUseOnly }

        /** The ability [scope] belongs to, or null for "" and unknown scopes. */
        fun of(scope: String): Ability? = entries.firstOrNull { scope in it.scopes }

        /** Every scope of [abilities]. */
        fun scopes(abilities: Iterable<Ability>): Set<String> = abilities.flatMapTo(LinkedHashSet()) { it.scopes }

        /** Abilities whose scopes are all in [scopes]. */
        fun fullIn(scopes: Set<String>): Set<Ability> = entries.filterTo(LinkedHashSet()) { scopes.containsAll(it.scopes) }

        /** Abilities with some but not all of their scopes in [scopes] (tokens made before the grant screen). */
        fun partialIn(scopes: Set<String>): Set<Ability> =
            entries.filterTo(LinkedHashSet()) { a -> a.scopes.any { it in scopes } && !scopes.containsAll(a.scopes) }

        /** What "Only what I choose" starts with for a new agent: looking and using apps, nothing that reaches other people. */
        val DEFAULT_CHOSEN: Set<Ability> = linkedSetOf(READ_MESSAGES, READ_CONTACTS_CALENDAR, SEE_SCREEN, USE_APPS)
    }
}

/** The two choices of the grant screen. */
enum class GrantMode {
    /** Every scope, every app (except the never-allow list), no risk cap. */
    EVERYTHING,

    /** The chosen abilities on all apps or only the chosen ones. */
    CHOSEN,
}

/**
 * The grant screen's state for one agent, and its mapping onto a token (docs/SECURITY.md "Access"). Pure and
 * immutable; the same model backs pairing, the manual token form and Agents > (agent) > Access.
 *
 * Mapping, applied by [applyTo] / [settings]:
 * * [GrantMode.EVERYTHING]: scopes = [Scopes.ALL], apps = all, no risk cap.
 * * [GrantMode.CHOSEN]: scopes = [scopes] (whole [Ability]s when picked here; older tokens may hold part of
 *   one, which stays as it is until the user ticks or clears that ability), apps = [apps] (`ALL` or
 *   `ALLOWLIST`; a `BLOCKLIST` from an older token is kept until the user picks another option),
 *   risk cap = [maxRisk] (only older tokens have one; kept until the user removes it).
 * * Approvals: [ConfirmPolicy.NEVER], or [ConfirmPolicy.SENSITIVE] with "Ask me before it sends messages, calls
 *   or pays". An older token's [ConfirmPolicy.ALL_ACTIONS] is kept until the user changes the switch.
 *
 * [from] a record followed by [applyTo] the same record changes nothing, so opening the editor never
 * migrates an agent silently. The grant never creates per-tool rules.
 */
data class GrantDraft(
    val mode: GrantMode,
    /** Scopes for [GrantMode.CHOSEN] (ignored for EVERYTHING, kept so switching back loses nothing). */
    val scopes: Set<String>,
    /** App access for [GrantMode.CHOSEN]; packages survive switching to "All apps" and back. */
    val apps: AppPolicy = AppPolicy(),
    val confirmPolicy: ConfirmPolicy = ConfirmPolicy.NEVER,
    /** Risk cap of an older token (the Read-only preset); null for grants made here. */
    val maxRisk: Risk? = null,
) {
    /** "Ask me before it sends messages, calls or pays" (also on for an older token's "ask before every action"). */
    val asksBeforeSensitive: Boolean get() = confirmPolicy != ConfirmPolicy.NEVER

    /** Abilities fully granted under [GrantMode.CHOSEN]. */
    val abilities: Set<Ability> get() = Ability.fullIn(scopes)

    /** Abilities partly granted (older tokens only). */
    val partialAbilities: Set<Ability> get() = Ability.partialIn(scopes)

    /** The scopes the agent gets. */
    val effectiveScopes: Set<String> get() = if (mode == GrantMode.EVERYTHING) Scopes.ALL else scopes

    /** The app access the agent gets. */
    val effectiveApps: AppPolicy get() = if (mode == GrantMode.EVERYTHING) AppPolicy() else normalized(apps)

    /** The risk cap the agent gets. */
    val effectiveMaxRisk: Risk? get() = if (mode == GrantMode.EVERYTHING) null else maxRisk

    /** Why the grant can't be saved, or null. */
    val error: String?
        get() = if (mode == GrantMode.CHOSEN && scopes.none { it in Scopes.ALL }) "Choose at least one thing it can do." else null

    /** Allowed but probably a mistake: only chosen apps, and none chosen. */
    val warning: String?
        get() = if (mode == GrantMode.CHOSEN && apps.mode == AppAccessMode.ALLOWLIST && apps.packages.isEmpty()) {
            "No apps chosen: it can't see or open any app. Choose apps, or pick All apps."
        } else {
            null
        }

    val isValid: Boolean get() = error == null

    /** Ticks [ability] (all its scopes) or, when it is fully granted, clears it. A partly granted one gets ticked. */
    fun toggle(ability: Ability): GrantDraft =
        copy(scopes = if (scopes.containsAll(ability.scopes)) scopes - ability.scopes else scopes + ability.scopes)

    /**
     * Picks [mode]. Choosing Everything also turns an older token's "ask before every action" into the switch's
     * "ask before it sends messages, calls or pays", so what the screen shows is what the agent gets.
     */
    fun withMode(mode: GrantMode): GrantDraft {
        val policy = if (mode == GrantMode.EVERYTHING && confirmPolicy == ConfirmPolicy.ALL_ACTIONS) ConfirmPolicy.SENSITIVE else confirmPolicy
        return copy(mode = mode, confirmPolicy = policy)
    }

    /** An older token's "ask before every action" (shown with a way to narrow it to the switch's meaning). */
    val asksBeforeEveryAction: Boolean get() = confirmPolicy == ConfirmPolicy.ALL_ACTIONS

    fun withAsk(ask: Boolean): GrantDraft = copy(confirmPolicy = if (ask) ConfirmPolicy.SENSITIVE else ConfirmPolicy.NEVER)

    /**
     * Picks all apps or only chosen ones. Chosen packages survive switching to "All apps" and back; an older
     * token's blocklist does not turn into an allowlist (its packages mean the opposite), so leaving or entering
     * BLOCKLIST starts from no packages.
     */
    fun withAppMode(mode: AppAccessMode): GrantDraft = when {
        mode == apps.mode -> this
        mode == AppAccessMode.BLOCKLIST || apps.mode == AppAccessMode.BLOCKLIST -> copy(apps = AppPolicy(mode))
        else -> copy(apps = apps.copy(mode = mode))
    }

    fun toggleApp(packageName: String): GrantDraft {
        val packages = apps.packages
        return copy(apps = apps.copy(packages = if (packageName in packages) packages - packageName else packages + packageName))
    }

    /** Drops an older token's risk cap. */
    fun withoutRiskCap(): GrantDraft = copy(maxRisk = null)

    /** The token settings this grant stands for. */
    fun settings(): GrantSettings = GrantSettings(effectiveScopes, effectiveApps, confirmPolicy, effectiveMaxRisk)

    /**
     * [record] with this grant's scopes, app access, approvals and risk cap; everything else (name, expiry,
     * tool rules, active hours…) unchanged. An untouched [from] draft returns [record] as it was.
     */
    fun applyTo(record: TokenRecord): TokenRecord {
        val s = settings()
        // Keep the stored app policy byte for byte when the user didn't change it (no silent normalization).
        val apps = if (normalized(record.appPolicy) == s.appPolicy) record.appPolicy else s.appPolicy
        return record.copy(scopes = LinkedHashSet(s.scopes), appPolicy = apps, confirmPolicy = s.confirmPolicy, maxRisk = s.maxRisk)
    }

    /** True if saving would change [record]. */
    fun differsFrom(record: TokenRecord): Boolean = applyTo(record) != record

    companion object {
        /** A new agent's grant before the user chooses: Everything, no prompts. */
        fun forNewAgent(): GrantDraft = GrantDraft(GrantMode.EVERYTHING, Ability.scopes(Ability.DEFAULT_CHOSEN))

        /** [record]'s access in the grant's terms. */
        fun from(record: TokenRecord): GrantDraft {
            val everything = record.scopes.containsAll(Scopes.ALL) && record.appPolicy.mode == AppAccessMode.ALL &&
                record.maxRisk == null && record.confirmPolicy != ConfirmPolicy.ALL_ACTIONS
            return GrantDraft(
                mode = if (everything) GrantMode.EVERYTHING else GrantMode.CHOSEN,
                scopes = record.scopes,
                apps = record.appPolicy,
                confirmPolicy = record.confirmPolicy,
                maxRisk = record.maxRisk,
            )
        }

        private fun normalized(apps: AppPolicy): AppPolicy = if (apps.mode == AppAccessMode.ALL) AppPolicy() else apps
    }
}

/** What a [GrantDraft] stores on the token. */
data class GrantSettings(val scopes: Set<String>, val appPolicy: AppPolicy, val confirmPolicy: ConfirmPolicy, val maxRisk: Risk?)
