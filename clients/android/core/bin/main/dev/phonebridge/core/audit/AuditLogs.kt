package dev.phonebridge.core.audit

import dev.phonebridge.core.util.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Ring buffer of the last [capacity] entries. Thread-safe; [entries] is most-recent-first.
 * Open so platforms can add side effects (e.g. forwarding to a system log) by overriding [record].
 */
open class InMemoryAuditLog(private val capacity: Int = 500) : AuditLog {
    private val lock = Any()
    private val buffer = ArrayDeque<AuditEntry>()
    private val state = MutableStateFlow<List<AuditEntry>>(emptyList())

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    override val entries: StateFlow<List<AuditEntry>> get() = state.asStateFlow()

    override fun record(entry: AuditEntry) {
        synchronized(lock) {
            buffer.addFirst(entry)
            while (buffer.size > capacity) buffer.removeLast()
            state.value = buffer.toList()
        }
    }

    override fun clear() {
        synchronized(lock) {
            buffer.clear()
            state.value = emptyList()
        }
    }

    /** Replaces the contents with [oldestFirst] (keeping the newest [capacity]). */
    internal fun restore(oldestFirst: List<AuditEntry>) {
        synchronized(lock) {
            buffer.clear()
            for (entry in oldestFirst.takeLast(capacity)) buffer.addFirst(entry)
            state.value = buffer.toList()
        }
    }

    /** Current entries, oldest first. */
    internal fun snapshotOldestFirst(): List<AuditEntry> = synchronized(lock) { buffer.reversed() }
}

/**
 * Ring buffer persisted as JSON lines in [file] (loaded at construction, compacted to [capacity]).
 * Writes must not block the caller for long (append is fine).
 *
 * Each [record] appends one line; when the file holds more than 2 × [capacity] lines it is
 * rewritten atomically with the newest [capacity] entries. Corrupt or unknown lines (e.g. a line
 * torn by a crash) are skipped on load. [record] and [clear] never throw: if the file cannot be
 * written, entries are still kept in memory.
 */
class FileAuditLog(private val file: File, private val capacity: Int = 1000) : AuditLog {
    private val lock = Any()
    private val memory = InMemoryAuditLog(capacity)

    /** Lines currently in the file, valid or not (drives compaction). */
    private var linesInFile = 0

    /** The file does not end with a newline (torn last line): start the next append on a new line. */
    private var needsLeadingNewline = false

    init {
        load()
    }

    override val entries: StateFlow<List<AuditEntry>> get() = memory.entries

    override fun record(entry: AuditEntry) {
        synchronized(lock) {
            memory.record(entry)
            try {
                val line = json.encodeToString(AuditEntry.serializer(), entry)
                append((if (needsLeadingNewline) "\n" else "") + line + "\n")
                needsLeadingNewline = false
                linesInFile++
                if (linesInFile > capacity * 2) compact()
            } catch (e: Exception) {
                // Keep serving from memory; the next compaction rewrites the file.
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            memory.clear()
            try {
                AtomicFiles.write(file, ByteArray(0))
            } catch (e: Exception) {
                file.delete()
            }
            linesInFile = 0
            needsLeadingNewline = false
        }
    }

    private fun load() {
        if (!file.exists()) return
        val loaded = ArrayList<AuditEntry>()
        try {
            val text = file.readText(Charsets.UTF_8)
            needsLeadingNewline = text.isNotEmpty() && !text.endsWith('\n')
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                linesInFile++
                try {
                    loaded += json.decodeFromString(AuditEntry.serializer(), line)
                } catch (e: Exception) {
                    // Torn or foreign line: skip it.
                }
            }
        } catch (e: Exception) {
            AtomicFiles.quarantine(file)
            linesInFile = 0
            needsLeadingNewline = false
            return
        }
        memory.restore(loaded)
        if (linesInFile > capacity * 2) {
            try {
                compact()
            } catch (e: Exception) {
                // Retried on the next record().
            }
        }
    }

    private fun append(text: String) {
        file.absoluteFile.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun compact() {
        val kept = memory.snapshotOldestFirst()
        val text = buildString {
            for (entry in kept) append(json.encodeToString(AuditEntry.serializer(), entry)).append('\n')
        }
        AtomicFiles.write(file, text.toByteArray(Charsets.UTF_8))
        linesInFile = kept.size
        needsLeadingNewline = false
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}
