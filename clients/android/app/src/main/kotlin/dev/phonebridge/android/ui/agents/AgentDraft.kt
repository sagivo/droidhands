package dev.phonebridge.android.ui.agents

import dev.phonebridge.android.ui.tokens.ExpiryOption
import dev.phonebridge.android.ui.tokens.TokenDraft
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.tools.Risk

/**
 * Editable copy of an agent's settings on the detail page. Immutable and pure (unit-tested): the
 * screen edits it through the copy helpers, and [applyTo] produces the record passed to
 * `TokenManager.update`.
 */
data class AgentDraft(
    val name: String,
    val scopes: Set<String>,
    val policy: ConfirmPolicy,
    /** New expiry counted from the moment of saving; null keeps the current expiry. */
    val expiry: ExpiryOption? = null,
    val appPolicy: AppPolicy = AppPolicy(),
    val toolRules: Map<String, ToolRule> = emptyMap(),
    val activeHours: ActiveHours? = null,
    /** Highest tool risk the agent may use ([TokenRecord.maxRisk]); set with the access level. */
    val maxRisk: Risk? = null,
) {
    val trimmedName: String get() = name.trim()

    val accessLevel: AccessLevel get() = AccessLevel.of(scopes, maxRisk)

    /** What the risk cap means (or that the Read-only scopes are uncapped), for the Access section. */
    val capNotice: String? get() = TokenPresentation.capNotice(scopes, maxRisk)

    val nameError: String? get() = nameError(name)

    val scopesError: String? get() = if (scopes.isEmpty()) "Select at least one capability." else null

    val activeHoursError: String? get() = Schedule.error(activeHours)

    /** Allowed but probably a mistake: an allowlist without apps blocks every app-facing tool. */
    val appPolicyWarning: String?
        get() = if (appPolicy.mode == AppAccessMode.ALLOWLIST && appPolicy.packages.isEmpty()) {
            "No apps selected: this agent can't see or open any app. Add apps, or choose All apps."
        } else {
            null
        }

    val canSave: Boolean get() = nameError == null && scopesError == null && activeHoursError == null

    /** A preset sets its scopes and its cap; [AccessLevel.CUSTOM] changes nothing (the scopes are picked next). */
    fun withLevel(level: AccessLevel): AgentDraft = level.scopes?.let { copy(scopes = it, maxRisk = level.maxRisk) } ?: this

    /** A hand-picked selection gets a preset's cap only when it is exactly that preset's scopes. */
    fun toggleScope(scope: String): AgentDraft {
        val toggled = if (scope in scopes) scopes - scope else scopes + scope
        return copy(scopes = toggled, maxRisk = TokenPresentation.capForScopes(toggled))
    }

    fun withRule(tool: String, choice: RuleChoice): AgentDraft = copy(toolRules = ToolRules.withChoice(toolRules, tool, choice))

    /** Switching modes keeps the selected packages, so going back and forth loses nothing. */
    fun withAppMode(mode: AppAccessMode): AgentDraft = copy(appPolicy = appPolicy.copy(mode = mode))

    fun toggleApp(packageName: String): AgentDraft {
        val packages = appPolicy.packages
        return copy(appPolicy = appPolicy.copy(packages = if (packageName in packages) packages - packageName else packages + packageName))
    }

    /** Applies the edits to [record]; [now] anchors a newly chosen expiry. Call only when [canSave]. */
    fun applyTo(record: TokenRecord, now: Long): TokenRecord = record.copy(
        name = trimmedName,
        scopes = LinkedHashSet(scopes),
        confirmPolicy = policy,
        expiresAt = when (val option = expiry) {
            null -> record.expiresAt
            else -> option.ttlMillis?.let { saturatedAdd(now, it) }
        },
        appPolicy = normalizedAppPolicy(),
        toolRules = toolRules,
        activeHours = activeHours,
        maxRisk = maxRisk,
    )

    /** True if saving would change [record]'s settings. */
    fun differsFrom(record: TokenRecord): Boolean =
        copy(appPolicy = normalizedAppPolicy()) != from(record).let { it.copy(appPolicy = it.normalizedAppPolicy()) }

    /** ALL ignores packages; drop them so the stored policy stays minimal (selections survive mode switches in the draft). */
    private fun normalizedAppPolicy(): AppPolicy = if (appPolicy.mode == AppAccessMode.ALL) AppPolicy() else appPolicy

    companion object {
        /** Problem with a display name, or null if it is fine. */
        fun nameError(name: String): String? {
            val trimmed = name.trim()
            return when {
                trimmed.isEmpty() -> "Give the agent a name, e.g. \"Claude on my laptop\"."
                trimmed.length > TokenDraft.MAX_NAME_LENGTH -> "Keep the name under ${TokenDraft.MAX_NAME_LENGTH} characters."
                else -> null
            }
        }

        fun from(record: TokenRecord) = AgentDraft(
            name = record.name,
            scopes = record.scopes,
            policy = record.confirmPolicy,
            expiry = null,
            appPolicy = record.appPolicy,
            toolRules = record.toolRules,
            activeHours = record.activeHours,
            maxRisk = record.maxRisk,
        )

        private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
