package dev.phonebridge.core.auth

import kotlinx.serialization.Serializable

/** Which tool risks need on-phone approval for a token. */
@Serializable
enum class ConfirmPolicy {
    /** Never prompt. */
    NEVER,

    /** Prompt for Risk.SENSITIVE tools (default). */
    SENSITIVE,

    /** Prompt for Risk.ACTION and Risk.SENSITIVE tools. */
    ALL_ACTIONS,
}

/** Persisted token metadata. The secret itself is never stored, only its SHA-256 (hex). */
@Serializable
data class TokenRecord(
    val id: String,
    val name: String,
    val secretHash: String,
    val scopes: Set<String>,
    val confirmPolicy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val lastUsedAt: Long? = null,
    val revoked: Boolean = false,
)

/** Returned once at creation; [plaintext] must be shown to the user and then forgotten. */
data class IssuedToken(val record: TokenRecord, val plaintext: String)

sealed interface AuthResult {
    data class Ok(val token: TokenRecord) : AuthResult

    /** [reason] is for the local audit log only; never sent to the caller. */
    data class Denied(val reason: String) : AuthResult
}

/** Persistence for token records (small list; load/save whole). */
interface TokenStore {
    fun load(): List<TokenRecord>
    fun save(tokens: List<TokenRecord>)
}
