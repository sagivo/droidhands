package dev.phonebridge.android.ui.tokens

import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.tools.Scopes

/** State of the "New token" form. Immutable; edited through the copy helpers. Pure. */
data class TokenDraft(
    val name: String = "Claude",
    val scopes: Set<String> = Scopes.PRESET_ASSISTANT,
    val policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
    val expiry: ExpiryOption = ExpiryOption.NEVER,
) {
    val trimmedName: String get() = name.trim()

    val nameError: String?
        get() = when {
            trimmedName.isEmpty() -> "Give the token a name, e.g. the agent or computer using it."
            trimmedName.length > MAX_NAME_LENGTH -> "Keep the name under $MAX_NAME_LENGTH characters."
            else -> null
        }

    val scopesError: String? get() = if (scopes.isEmpty()) "Select at least one scope." else null

    val canCreate: Boolean get() = nameError == null && scopesError == null

    /** Preset matching the current selection exactly, or null for a custom selection. */
    val selectedPreset: String? get() = TokenPresentation.presetFor(scopes)

    val highRiskScopes: Set<String> get() = scopes intersect TokenPresentation.HIGH_RISK_SCOPES

    fun withPreset(preset: String): TokenDraft = Scopes.PRESETS[preset]?.let { copy(scopes = it) } ?: this

    fun toggle(scope: String): TokenDraft = copy(scopes = if (scope in scopes) scopes - scope else scopes + scope)

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
