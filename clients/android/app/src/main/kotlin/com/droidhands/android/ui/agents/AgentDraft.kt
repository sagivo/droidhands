package com.droidhands.android.ui.agents

import com.droidhands.android.ui.tokens.ExpiryOption
import com.droidhands.android.ui.tokens.TokenDraft
import com.droidhands.core.auth.ActiveHours
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.auth.ToolRule

/**
 * Editable copy of an agent's settings on the detail page: name, expiry, per-tool rules and active hours.
 * Its access (apps, abilities, approvals) is edited on its own page with the grant editor and saved at once,
 * so [applyTo] never touches it. Immutable and pure (unit-tested): the screen edits it through the copy
 * helpers, and [applyTo] produces the record passed to `TokenManager.update`.
 */
data class AgentDraft(
    val name: String,
    /** New expiry counted from the moment of saving; null keeps the current expiry. */
    val expiry: ExpiryOption? = null,
    val toolRules: Map<String, ToolRule> = emptyMap(),
    val activeHours: ActiveHours? = null,
) {
    val trimmedName: String get() = name.trim()

    val nameError: String? get() = nameError(name)

    val activeHoursError: String? get() = Schedule.error(activeHours)

    val canSave: Boolean get() = nameError == null && activeHoursError == null

    fun withRule(tool: String, choice: RuleChoice): AgentDraft = copy(toolRules = ToolRules.withChoice(toolRules, tool, choice))

    /**
     * Applies the edits to [record] (the current stored record, so access saved meanwhile on the Access page is
     * kept); [now] anchors a newly chosen expiry. Call only when [canSave].
     */
    fun applyTo(record: TokenRecord, now: Long): TokenRecord = record.copy(
        name = trimmedName,
        expiresAt = when (val option = expiry) {
            null -> record.expiresAt
            else -> option.ttlMillis?.let { saturatedAdd(now, it) }
        },
        toolRules = toolRules,
        activeHours = activeHours,
    )

    /** True if saving would change [record]'s settings. */
    fun differsFrom(record: TokenRecord): Boolean = this != from(record)

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
            expiry = null,
            toolRules = record.toolRules,
            activeHours = record.activeHours,
        )

        private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
