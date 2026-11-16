package com.droidhands.android.ui.tokens

import com.droidhands.core.auth.GrantDraft

/** State of the "New token" form. Immutable; edited through the copy helpers. Pure. */
data class TokenDraft(
    val name: String = "Claude",
    /** The agent's access, chosen with the same grant screen as pairing (Everything, no prompts by default). */
    val grant: GrantDraft = GrantDraft.forNewAgent(),
    val expiry: ExpiryOption = ExpiryOption.NEVER,
) {
    val trimmedName: String get() = name.trim()

    val nameError: String?
        get() = when {
            trimmedName.isEmpty() -> "Give the token a name, e.g. the agent or computer using it."
            trimmedName.length > MAX_NAME_LENGTH -> "Keep the name under $MAX_NAME_LENGTH characters."
            else -> null
        }

    val canCreate: Boolean get() = nameError == null && grant.isValid

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
