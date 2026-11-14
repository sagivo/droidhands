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
    /** "manual" (created in the app) or "pairing" (one-tap OAuth pairing, docs/PROTOCOL.md §5). */
    val origin: String = "manual",
    /** For paired agents: the OAuth redirect host, e.g. "claude.ai" or "localhost:33418". */
    val clientHost: String? = null,
    /** Which apps this agent may see/operate (docs/SECURITY.md "Advanced controls"). */
    val appPolicy: AppPolicy = AppPolicy(),
    /** Per-tool overrides by tool name. */
    val toolRules: Map<String, ToolRule> = emptyMap(),
    /** Daily local-time window in which the agent may act; null = always. */
    val activeHours: ActiveHours? = null,
    /**
     * Highest tool risk this agent may use; tools above it are hidden from tools/list and refused
     * (DENIED_POLICY). The "Read-only" preset sets READ. null = no cap (scopes decide).
     */
    val maxRisk: dev.phonebridge.core.tools.Risk? = null,
)

@Serializable
enum class AppAccessMode { ALL, ALLOWLIST, BLOCKLIST }

/** [packages] are the allowlisted (ALLOWLIST) or blocked (BLOCKLIST) package names; ignored for ALL. */
@Serializable
data class AppPolicy(val mode: AppAccessMode = AppAccessMode.ALL, val packages: Set<String> = emptySet())

@Serializable
enum class ToolRule {
    /** Never ask (still rate-limited). */
    ALLOW,

    /** Always ask on the phone. */
    ASK,

    /** Hidden from tools/list and refused. */
    BLOCK,
}

/**
 * Minutes after local midnight, 0..1439. start < end: same-day window (08:00-23:00);
 * start > end: overnight window (22:00-06:00); start == end is invalid (use null for "always").
 */
@Serializable
data class ActiveHours(val startMinute: Int, val endMinute: Int)

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
