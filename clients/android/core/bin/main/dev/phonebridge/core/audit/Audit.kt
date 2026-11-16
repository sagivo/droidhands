package dev.phonebridge.core.audit

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class AuditOutcome {
    OK,
    TOOL_ERROR,
    UNKNOWN_TOOL,
    DENIED_SCOPE,
    RATE_LIMITED,
    PAUSED,
    DENIED_BY_USER,
    CONFIRM_TIMEOUT,
    AUTH_FAILED,
    TIMEOUT,
    EXCEPTION,

    /** Refused by the token's policy: a per-tool BLOCK rule, or outside the token's active hours. */
    DENIED_POLICY,

    /** The tool touched an app the user blocked for this agent (app access / never-allow list). */
    DENIED_APP,

    /**
     * Stopped after it began: access was paused, the agent was revoked or removed (or its token expired), or
     * the tool was taken away from it while the call was waiting or running. The detail says why and whether
     * the tool had started (then the action may have partly completed).
     */
    CANCELLED,
}

@Serializable
data class AuditEntry(
    val timestamp: Long,
    val tokenId: String?,
    val tokenName: String?,
    /** JSON-RPC method, e.g. "tools/call" or "initialize". */
    val method: String,
    val tool: String? = null,
    /** Truncated (<= 300 chars) JSON of the arguments. Stored on-device only. */
    val argsPreview: String? = null,
    val outcome: AuditOutcome,
    val durationMs: Long = 0,
    val transport: String,
    val detail: String? = null,
)

interface AuditLog {
    fun record(entry: AuditEntry)

    /** Most recent first, at most the log's capacity. */
    val entries: StateFlow<List<AuditEntry>>

    fun clear()
}
