package dev.phonebridge.core.audit

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.time.ZoneId

/** Aggregated usage of one token, kept beyond the audit log's ring buffer. */
@Serializable
data class TokenUsage(
    val tokenId: String,
    val totalCalls: Long = 0,
    val okCalls: Long = 0,
    /** TOOL_ERROR, TIMEOUT, EXCEPTION, UNKNOWN_TOOL. */
    val errorCalls: Long = 0,
    /** DENIED_SCOPE, RATE_LIMITED, PAUSED, DENIED_BY_USER, CONFIRM_TIMEOUT. */
    val deniedCalls: Long = 0,
    val lastCallAt: Long? = null,
    /** tool name -> calls. */
    val perTool: Map<String, Long> = emptyMap(),
    /** Local date "yyyy-MM-dd" -> calls; only the last 31 days are kept. */
    val perDay: Map<String, Long> = emptyMap(),
)

/**
 * Per-token usage counters, fed from audit entries (only `tools/call` entries with a tokenId count),
 * persisted as JSON to [file] (debounced; null = memory only). Thread-safe; never throws from record().
 *
 * STUB — implemented by the core agent (wave 2).
 */
class UsageTracker(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** tokenId -> usage. */
    val usage: StateFlow<Map<String, TokenUsage>> get() = TODO()

    fun record(entry: AuditEntry): Unit = TODO()

    fun forget(tokenId: String): Unit = TODO()

    /** Writes pending changes now (e.g. from onTrimMemory / service stop). */
    fun flush(): Unit = TODO()
}

/** AuditLog decorator that also feeds a [UsageTracker]. STUB — core agent (wave 2). */
class TrackingAuditLog(private val delegate: AuditLog, private val tracker: UsageTracker) : AuditLog {
    override val entries: StateFlow<List<AuditEntry>> get() = TODO()
    override fun record(entry: AuditEntry): Unit = TODO()
    override fun clear(): Unit = TODO()
}
