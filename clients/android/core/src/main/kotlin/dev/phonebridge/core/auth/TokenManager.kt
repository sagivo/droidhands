package dev.phonebridge.core.auth

import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/**
 * Issues and verifies agent tokens of the form `pb1.<deviceId>.<tokenId>.<secret>`
 * (docs/PROTOCOL.md §2). Thread-safe. Persists through [store] on every mutation.
 *
 * STUB — implemented by the core agent.
 */
class TokenManager(
    private val store: TokenStore,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** All non-deleted tokens (including revoked/expired, for display), newest first. */
    val tokens: StateFlow<List<TokenRecord>> get() = TODO()

    /** @param ttlMillis null = never expires. */
    fun issue(name: String, scopes: Set<String>, confirmPolicy: ConfirmPolicy, ttlMillis: Long?): IssuedToken = TODO()

    /**
     * Accepts `Bearer <token>` (case-insensitive scheme) or a bare token. Verifies format, device id,
     * token id, constant-time secret hash, not revoked, not expired. Updates lastUsedAt
     * (persisted at most once per minute per token).
     */
    fun authenticate(authorization: String?): AuthResult = TODO()

    fun revoke(id: String): Unit = TODO()

    fun delete(id: String): Unit = TODO()
}
