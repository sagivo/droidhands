package dev.phonebridge.core.auth

import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.util.Encoding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Issues and verifies agent tokens of the form `pb1.<deviceId>.<tokenId>.<secret>`
 * (docs/PROTOCOL.md §2). Thread-safe. Persists through [store] on every mutation.
 *
 * Mutations ([issue], [update], [revoke], [revokeAll], [delete]) save first and publish to [tokens]
 * only if the save succeeded, so a failing store never leaves memory and disk silently out of sync;
 * the store's exception propagates to the caller. Saves happen on the calling thread: call from a
 * background dispatcher when the store does disk I/O.
 *
 * The constructor loads [store] once; an exception from [TokenStore.load] propagates (failing
 * closed rather than starting with an empty list that the next save would persist).
 */
class TokenManager(
    private val store: TokenStore,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val state: MutableStateFlow<List<TokenRecord>>

    init {
        require(DeviceIdentity.isValidDeviceId(deviceId)) { "Invalid device id" }
        state = MutableStateFlow(normalize(store.load()))
    }

    /** All non-deleted tokens (including revoked/expired, for display), newest first. */
    val tokens: StateFlow<List<TokenRecord>> get() = state.asStateFlow()

    /**
     * Creates a token. The returned [IssuedToken.plaintext] is the only copy of the secret.
     * @param name non-blank display name (trimmed).
     * @param scopes a subset of [dev.phonebridge.core.tools.Scopes.ALL].
     * @param ttlMillis null = never expires; otherwise must be positive.
     * @param origin "manual" (created in the app) or "pairing" (one-tap pairing); non-blank.
     * @param clientHost for paired agents, the OAuth redirect host (blank = null).
     * @param appPolicy which apps the agent may see/operate.
     * @param maxRisk highest tool risk the agent may use (null = no cap); see
     *   [dev.phonebridge.core.tools.Scopes.PRESET_MAX_RISK] for the presets' values.
     * @throws IllegalArgumentException on invalid arguments; any exception from [TokenStore.save].
     */
    fun issue(
        name: String,
        scopes: Set<String>,
        confirmPolicy: ConfirmPolicy,
        ttlMillis: Long?,
        origin: String = "manual",
        clientHost: String? = null,
        appPolicy: AppPolicy = AppPolicy(),
        maxRisk: Risk? = null,
    ): IssuedToken {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Token name must not be blank" }
        require(ttlMillis == null || ttlMillis > 0) { "ttlMillis must be positive" }
        val trimmedOrigin = origin.trim()
        require(trimmedOrigin.isNotEmpty()) { "Token origin must not be blank" }
        TokenValidation.requireKnownScopes(scopes)

        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val secret = Encoding.base64Url(secretBytes)
        val secretHash = Encoding.hex(Encoding.sha256(secretBytes))
        secretBytes.fill(0)

        synchronized(lock) {
            val current = state.value
            val now = clock()
            val record = TokenRecord(
                id = newTokenId(current),
                name = trimmedName,
                secretHash = secretHash,
                scopes = LinkedHashSet(scopes),
                confirmPolicy = confirmPolicy,
                createdAt = now,
                expiresAt = ttlMillis?.let { saturatedAdd(now, it) },
                origin = trimmedOrigin,
                clientHost = clientHost?.trim()?.takeIf { it.isNotEmpty() },
                appPolicy = appPolicy.copy(packages = LinkedHashSet(appPolicy.packages)),
                maxRisk = maxRisk,
            )
            commit(listOf(record) + current)
            return IssuedToken(record, TokenFormat.format(deviceId, record.id, secret))
        }
    }

    /**
     * Accepts `Bearer <token>` (case-insensitive scheme) or a bare token. Verifies format, device id,
     * token id, constant-time secret hash, not revoked, not expired. Updates lastUsedAt
     * (persisted at most once per minute per token).
     *
     * [AuthResult.Denied.reason] is meant for the local audit log only.
     */
    fun authenticate(authorization: String?): AuthResult {
        val raw = TokenFormat.fromAuthorization(authorization) ?: return AuthResult.Denied("missing token")
        val parsed = TokenFormat.parse(raw) ?: return AuthResult.Denied("malformed token")
        if (parsed.deviceId != deviceId) return AuthResult.Denied("token is for another device")

        val secretBytes = try {
            Encoding.base64UrlDecode(parsed.secret)
        } catch (e: IllegalArgumentException) {
            return AuthResult.Denied("malformed token secret")
        }
        // Hash before the lookup so unknown ids and wrong secrets take similar time.
        val presentedHash = Encoding.hex(Encoding.sha256(secretBytes))

        val record = state.value.firstOrNull { it.id == parsed.tokenId }
            ?: return AuthResult.Denied("unknown token id ${parsed.tokenId}")
        if (!Encoding.constantTimeEquals(presentedHash, record.secretHash)) {
            return AuthResult.Denied("wrong secret for token ${record.id}")
        }
        if (record.revoked) return AuthResult.Denied("token ${record.id} is revoked")
        val now = clock()
        val expiresAt = record.expiresAt
        if (expiresAt != null && now >= expiresAt) return AuthResult.Denied("token ${record.id} expired")

        return AuthResult.Ok(touch(record.id, now) ?: record)
    }

    /** Marks a token revoked (kept for display). No-op if unknown or already revoked. */
    fun revoke(id: String) {
        synchronized(lock) {
            val current = state.value
            if (current.none { it.id == id && !it.revoked }) return
            commit(current.map { if (it.id == id) it.copy(revoked = true) else it })
        }
    }

    /**
     * Edits a token's mutable settings (name, scopes, confirmPolicy, expiresAt, appPolicy, toolRules,
     * activeHours, maxRisk). [transform]'s changes to id/secretHash/createdAt/origin/lastUsedAt/revoked are
     * ignored, and so are changes to clientHost (provenance, like origin). Persists; returns the
     * updated record (the stored one, without saving, when nothing changed).
     *
     * [transform] runs under the manager's lock: keep it a quick, pure `copy(...)` and do not call
     * back into this manager from it.
     *
     * @throws NoSuchElementException for unknown ids.
     * @throws IllegalArgumentException if the result is invalid: blank name, scopes outside
     *   [dev.phonebridge.core.tools.Scopes.ALL], or active-hours minutes outside 0..1439 or start == end.
     *   Nothing changes then.
     * @throws Exception from [TokenStore.save] (nothing changes then either).
     */
    fun update(id: String, transform: (TokenRecord) -> TokenRecord): TokenRecord {
        synchronized(lock) {
            val current = state.value
            val existing = current.firstOrNull { it.id == id } ?: throw NoSuchElementException("Unknown token id $id")
            val edited = transform(existing)
            val merged = edited.copy(
                id = existing.id,
                name = edited.name.trim(),
                secretHash = existing.secretHash,
                scopes = LinkedHashSet(edited.scopes),
                createdAt = existing.createdAt,
                lastUsedAt = existing.lastUsedAt,
                revoked = existing.revoked,
                origin = existing.origin,
                clientHost = existing.clientHost,
                appPolicy = edited.appPolicy.copy(packages = LinkedHashSet(edited.appPolicy.packages)),
                toolRules = LinkedHashMap(edited.toolRules),
            )
            TokenValidation.requireValid(merged)
            if (merged == existing) return existing
            commit(current.map { if (it.id == id) merged else it })
            return merged
        }
    }

    /**
     * Revokes every token that is not revoked yet (expired ones included, so none can come back if
     * the clock moves). Returns how many tokens were newly revoked; when that is 0 nothing is saved.
     */
    fun revokeAll(): Int {
        synchronized(lock) {
            val current = state.value
            val count = current.count { !it.revoked }
            if (count == 0) return 0
            commit(current.map { if (it.revoked) it else it.copy(revoked = true) })
            return count
        }
    }

    /** Removes a token entirely. No-op if unknown. */
    fun delete(id: String) {
        synchronized(lock) {
            val current = state.value
            if (current.none { it.id == id }) return
            commit(current.filterNot { it.id == id })
        }
    }

    /**
     * Records use of [id] at [now] if the stored value is older than [LAST_USED_RESOLUTION_MS].
     * Returns the current record. A failing save must not fail authentication: the newer
     * timestamp stays in memory (display only) and is persisted by the next successful save.
     */
    private fun touch(id: String, now: Long): TokenRecord? = synchronized(lock) {
        val current = state.value
        val record = current.firstOrNull { it.id == id } ?: return null
        val last = record.lastUsedAt
        if (last != null && now - last < LAST_USED_RESOLUTION_MS && now >= last) return record
        val updated = record.copy(lastUsedAt = now)
        val next = current.map { if (it.id == id) updated else it }
        try {
            store.save(next)
        } catch (e: Exception) {
            // Intentionally swallowed; see KDoc.
        }
        state.value = next
        updated
    }

    /** Saves, then publishes. Must hold [lock]. */
    private fun commit(next: List<TokenRecord>) {
        store.save(next)
        state.value = next
    }

    private fun newTokenId(existing: List<TokenRecord>): String {
        val ids = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val bytes = ByteArray(TOKEN_ID_RANDOM_BYTES).also(random::nextBytes)
            val id = Encoding.base32(bytes).substring(0, TOKEN_ID_CHARS)
            if (id !in ids) return id
        }
    }

    private companion object {
        const val SECRET_BYTES = 32
        const val TOKEN_ID_RANDOM_BYTES = 8
        const val TOKEN_ID_CHARS = 12
        const val LAST_USED_RESOLUTION_MS = 60_000L

        /** Newest first; drops later duplicates of an id (a corrupt or hand-edited store). */
        fun normalize(loaded: List<TokenRecord>): List<TokenRecord> =
            loaded.distinctBy { it.id }.sortedByDescending { it.createdAt }

        fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }
}
