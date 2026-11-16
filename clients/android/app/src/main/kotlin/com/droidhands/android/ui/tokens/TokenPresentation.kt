package com.droidhands.android.ui.tokens

import com.droidhands.android.ui.format.TimeFormat
import com.droidhands.core.auth.TokenRecord
import com.droidhands.core.tools.Scopes

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
    fun state(record: TokenRecord, now: Long): TokenState {
        val expiresAt = record.expiresAt
        return when {
            record.revoked -> TokenState.REVOKED
            expiresAt != null && expiresAt <= now -> TokenState.EXPIRED
            else -> TokenState.ACTIVE
        }
    }

    /** [scopes] in the catalog order of [Scopes.DESCRIPTIONS]; unknown scopes last, alphabetically. */
    fun orderedScopes(scopes: Set<String>): List<String> {
        val known = Scopes.DESCRIPTIONS.keys.filter { it in scopes }
        return known + (scopes - Scopes.DESCRIPTIONS.keys).sorted()
    }

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
