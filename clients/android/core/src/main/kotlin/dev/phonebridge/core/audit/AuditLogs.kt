package dev.phonebridge.core.audit

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Ring buffer. STUB — implemented by the core agent. */
open class InMemoryAuditLog(private val capacity: Int = 500) : AuditLog {
    override val entries: StateFlow<List<AuditEntry>> get() = TODO()
    override fun record(entry: AuditEntry): Unit = TODO()
    override fun clear(): Unit = TODO()
}

/**
 * Ring buffer persisted as JSON lines in [file] (loaded at construction, compacted to [capacity]).
 * Writes must not block the caller for long (append is fine). STUB — implemented by the core agent.
 */
class FileAuditLog(private val file: File, private val capacity: Int = 1000) : AuditLog {
    override val entries: StateFlow<List<AuditEntry>> get() = TODO()
    override fun record(entry: AuditEntry): Unit = TODO()
    override fun clear(): Unit = TODO()
}
