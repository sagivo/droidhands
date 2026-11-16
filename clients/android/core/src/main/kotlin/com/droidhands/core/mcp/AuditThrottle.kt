package com.droidhands.core.mcp

import com.droidhands.core.audit.AuditEntry
import com.droidhands.core.audit.AuditLog
import com.droidhands.core.audit.AuditOutcome

/**
 * Keeps refused requests from flooding the audit log. The log is a ring buffer, so without this an agent
 * (or, with local mode on, any app on the phone) could push the record of what it really did out of the
 * log with cheap junk calls: unknown tool names, out-of-scope tools, calls while paused, rate-limited
 * calls, bad tokens.
 *
 * [record] writes an entry as is: everything that ran (successful or not) and every decision the user
 * made. [recordRefusal] is for requests refused before anything ran: per (source, outcome) at most
 * [perKeyBudget] of them are written in each [windowMs] window, and at most [globalBudget] across all
 * sources. Further ones are counted instead, and when their window has ended one summary entry
 * ("N more like this…", with the same outcome) is written in their place. Summaries are written by the
 * next [record] or [recordRefusal] call, so they appear just before the next logged activity. At most
 * [maxKeys] sources are tracked separately; further sources share one bucket per outcome.
 *
 * Thread-safe. Never throws: auditing must not break request handling.
 */
internal class AuditThrottle(
    private val audit: AuditLog,
    private val clock: () -> Long,
    private val perKeyBudget: Int = 5,
    private val globalBudget: Int = 30,
    private val windowMs: Long = 60_000,
    private val maxKeys: Int = 32,
) {
    private data class Key(val source: String, val outcome: AuditOutcome)

    /** One source's refusals with one outcome in the window that began at [start]. */
    private class Window(val start: Long) {
        var written = 0
        var suppressed = 0
        var firstSuppressedAt = 0L
        var lastSuppressedAt = 0L
        var last: AuditEntry? = null
        val tools = LinkedHashSet<String>()
        var moreTools = false
        var mixedMethods = false
        var mixedTokens = false
    }

    private val lock = Any()
    private val windows = LinkedHashMap<Key, Window>() // Guarded by lock.
    private var globalStart = 0L // Guarded by lock.
    private var globalWritten = 0 // Guarded by lock.

    init {
        require(perKeyBudget > 0 && globalBudget > 0 && windowMs > 0 && maxKeys > 0) { "limits must be positive" }
    }

    /** Writes [entry] (after any summaries that are due). */
    fun record(entry: AuditEntry) {
        val due = synchronized(lock) { takeDueSummaries(clock()) }
        due.forEach(::write)
        write(entry)
    }

    /**
     * Writes [entry], a refused request from [source] (e.g. `token:<id>` or the caller's address), unless
     * its budget is used up; then it is only counted toward a later summary.
     */
    fun recordRefusal(entry: AuditEntry, source: String) {
        val out = ArrayList<AuditEntry>(2)
        synchronized(lock) {
            val now = clock()
            out += takeDueSummaries(now)
            if (isOver(globalStart, now)) {
                globalStart = now
                globalWritten = 0
            }
            val key = keyFor(source, entry.outcome)
            val window = windows.getOrPut(key) { Window(now) }
            if (window.written < perKeyBudget && globalWritten < globalBudget) {
                window.written++
                globalWritten++
                out += entry
            } else {
                suppress(window, entry, now)
            }
        }
        out.forEach(::write)
    }

    /** Sources with live state (tests). */
    internal fun trackedKeys(): Int = synchronized(lock) { windows.size }

    /** Must hold [lock]. */
    private fun keyFor(source: String, outcome: AuditOutcome): Key {
        val key = Key(source.take(MAX_SOURCE_CHARS), outcome)
        return if (key in windows || windows.size < maxKeys) key else Key(OVERFLOW_SOURCE, outcome)
    }

    /** Must hold [lock]. */
    private fun suppress(window: Window, entry: AuditEntry, now: Long) {
        val previous = window.last
        if (window.suppressed == 0) window.firstSuppressedAt = now
        window.suppressed++
        window.lastSuppressedAt = now
        if (previous != null) {
            if (previous.method != entry.method) window.mixedMethods = true
            if (previous.tokenId != entry.tokenId) window.mixedTokens = true
        }
        entry.tool?.take(MAX_TOOL_CHARS)?.let { tool ->
            if (tool in window.tools) return@let
            if (window.tools.size < MAX_TOOLS_LISTED) window.tools += tool else window.moreTools = true
        }
        window.last = entry
    }

    /** Removes the windows that have ended and returns the summaries of those with suppressed entries. Must hold [lock]. */
    private fun takeDueSummaries(now: Long): List<AuditEntry> {
        if (windows.isEmpty()) return emptyList()
        val due = ArrayList<AuditEntry>(0)
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val (key, window) = iterator.next()
            if (!isOver(window.start, now)) continue
            iterator.remove()
            summary(key, window)?.let(due::add)
        }
        return due
    }

    private fun summary(key: Key, window: Window): AuditEntry? {
        val last = window.last ?: return null
        val n = window.suppressed
        val spanSeconds = ((window.lastSuppressedAt - window.firstSuppressedAt) / 1000).coerceAtLeast(0)
        val detail = buildString {
            append("Flood protection: ").append(n).append(if (n == 1) " more request like this was" else " more requests like this were")
            append(" not logged one by one")
            if (spanSeconds > 0) append(" (over the ").append(spanSeconds).append(" s up to this entry)")
            if (window.tools.size > 1 || window.moreTools) {
                append("; tools: ").append(window.tools.joinToString(", "))
                if (window.moreTools) append(", …")
            }
            last.detail?.let { append("; last: ").append(it) }
        }
        return AuditEntry(
            timestamp = window.lastSuppressedAt,
            tokenId = if (window.mixedTokens) null else last.tokenId,
            tokenName = if (window.mixedTokens) null else last.tokenName,
            method = if (window.mixedMethods) "multiple" else last.method,
            tool = window.tools.singleOrNull()?.takeIf { !window.moreTools },
            outcome = key.outcome,
            transport = last.transport,
            detail = ToolText.truncate(detail, ToolText.PREVIEW_CHARS),
        )
    }

    /** True once [now] is [windowMs] past [start], or before it (the wall clock moved back). */
    private fun isOver(start: Long, now: Long): Boolean = now - start >= windowMs || now < start

    private fun write(entry: AuditEntry) {
        try {
            audit.record(entry)
        } catch (e: Exception) {
            // Auditing must never break request handling.
        }
    }

    private companion object {
        const val OVERFLOW_SOURCE = "*"
        const val MAX_SOURCE_CHARS = 160
        const val MAX_TOOLS_LISTED = 3
        const val MAX_TOOL_CHARS = 40
    }
}
