package dev.phonebridge.core.audit

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    /** DENIED_SCOPE, RATE_LIMITED, PAUSED, DENIED_BY_USER, CONFIRM_TIMEOUT, DENIED_POLICY, DENIED_APP. */
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
 * Counting rules ([UsageCounting]): every counted entry adds to `totalCalls` and to exactly one of
 * `okCalls` / `errorCalls` / `deniedCalls` as documented on [TokenUsage]. AUTH_FAILED entries are not
 * counted: the caller did not prove it holds the token, so they must not inflate its usage. `perDay`
 * uses the entry's timestamp in [zone] and keeps the 31 local days ending today ([clock]); `perTool`
 * holds at most [UsageCounting.MAX_TOOLS] names (unknown tool names are not added).
 *
 * Persistence: changes are written at most every [debounceMs] (atomic replace) on a background
 * thread, and immediately by [flush]. Write failures are retried with the next change or flush.
 * Loading is tolerant: a missing file is empty; an unreadable or corrupt file is moved aside to
 * `<name>.corrupt`; a single broken token entry is skipped.
 */
class UsageTracker(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Delay between the first unsaved change and the write. */
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val lock = Any()
    private val writeLock = Any()
    private val state: MutableStateFlow<Map<String, TokenUsage>>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("phonebridge-usage"))

    // Guarded by lock.
    private var version = 0L
    private var writeScheduled = false

    // Guarded by writeLock.
    private var savedVersion = 0L

    init {
        require(debounceMs >= 0) { "debounceMs must not be negative" }
        state = MutableStateFlow(UsageCounting.trimAll(file?.let(UsageFile::load).orEmpty(), today()))
    }

    /** tokenId -> usage. */
    val usage: StateFlow<Map<String, TokenUsage>> get() = state.asStateFlow()

    fun record(entry: AuditEntry) {
        try {
            if (!UsageCounting.counts(entry)) return
            val zoneId = zoneOrDefault()
            val day = UsageCounting.day(entry.timestamp, zoneId)
            val today = UsageCounting.day(clock(), zoneId)
            synchronized(lock) {
                val current = state.value
                val tokenId = entry.tokenId ?: return
                val updated = UsageCounting.add(current[tokenId] ?: TokenUsage(tokenId), entry, day, today)
                state.value = current + (tokenId to updated)
                changedLocked()
            }
        } catch (e: Exception) {
            // Usage is best effort: it must never break a call.
        }
    }

    fun forget(tokenId: String) {
        synchronized(lock) {
            val current = state.value
            if (tokenId !in current) return
            state.value = current - tokenId
            changedLocked()
        }
    }

    /**
     * Writes pending changes now (e.g. from onTrimMemory / service stop). Best effort: never throws.
     * Does disk I/O on the calling thread, so call it off the main thread.
     */
    fun flush() {
        val target = file ?: return
        synchronized(writeLock) {
            val (snapshot, snapshotVersion) = synchronized(lock) { state.value to version }
            if (snapshotVersion == savedVersion) return
            try {
                UsageFile.save(target, snapshot)
                savedVersion = snapshotVersion
            } catch (e: Exception) {
                // Kept dirty: retried by the next change or flush.
            }
        }
    }

    /** Must hold [lock]. */
    private fun changedLocked() {
        version++
        if (file == null || writeScheduled) return
        writeScheduled = true
        scope.launch {
            delay(debounceMs)
            synchronized(lock) { writeScheduled = false }
            flush()
        }
    }

    /** The injected zone, or the system's if it fails (counting must not stop over a zone lookup). */
    private fun zoneOrDefault(): ZoneId = try {
        zone()
    } catch (e: Exception) {
        ZoneId.systemDefault()
    }

    /** Today's local date; the system clock if [clock] fails. */
    private fun today(): String {
        val now = try {
            clock()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
        return UsageCounting.day(now, zoneOrDefault())
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}

/** AuditLog decorator that also feeds a [UsageTracker]. Clearing the log keeps the usage counters. */
class TrackingAuditLog(private val delegate: AuditLog, private val tracker: UsageTracker) : AuditLog {
    override val entries: StateFlow<List<AuditEntry>> get() = delegate.entries

    /** Records in [delegate] (whose exceptions propagate), and counts the entry in any case. */
    override fun record(entry: AuditEntry) {
        try {
            delegate.record(entry)
        } finally {
            tracker.record(entry)
        }
    }

    override fun clear() = delegate.clear()
}
