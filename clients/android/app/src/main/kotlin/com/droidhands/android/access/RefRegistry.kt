package com.droidhands.android.access

import com.droidhands.core.tools.ToolException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identity of a node as the agent saw it. Used to detect refs that now point at different content
 * (e.g. a recycled list row after scrolling). Editable text is excluded since typing changes it.
 */
data class NodeFingerprint(val className: String?, val viewId: String?, val text: String?, val description: String?) {
    fun matches(other: NodeFingerprint): Boolean = this == other

    companion object {
        fun of(n: UiNode) = NodeFingerprint(
            className = n.className,
            viewId = n.viewId,
            text = if (n.editable || n.password) null else TextNorm.key(n.text).ifEmpty { null },
            description = TextNorm.key(n.description).ifEmpty { null },
        )
    }
}

/** Source of element ref ids. Registries of all agents share one, so no id is ever handed out twice. Thread-safe. */
class RefIds {
    private val last = AtomicInteger()

    fun next(): Int = last.incrementAndGet()
}

/**
 * Maps element refs (`n<id>`) to the nodes of one agent's latest snapshot. screen_read starts a new
 * snapshot; screen_find and friends append to it (re-using the ref of a node already listed).
 * Ids come from [ids] and are never reused, neither across snapshots nor across agents (whose
 * registries share one [RefIds]): a ref from an older snapshot or from another agent is rejected
 * instead of silently pointing at a different element. Thread-safe.
 */
class RefRegistry(private val ids: RefIds = RefIds()) {
    /** A registered node and the fingerprint it had when the agent saw it. */
    data class Entry(val ref: String, val node: UiNode, val fingerprint: NodeFingerprint)

    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    private val byKey = HashMap<Any, Int>()
    private var started = false

    /** Number of refs in the current snapshot. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Discards all refs, then runs [block] with a registering function, atomically. */
    fun <T> newSnapshot(block: (register: (UiNode) -> String) -> T): T = synchronized(lock) {
        started = true
        entries.clear()
        byKey.clear()
        block(::registerLocked)
    }

    /**
     * Adds [node] to the current snapshot. A node that is already listed keeps its ref, but its
     * entry is updated to this newer capture (the agent is being shown the new content).
     */
    fun register(node: UiNode): String = synchronized(lock) { registerLocked(node) }

    private fun registerLocked(node: UiNode): String {
        started = true
        byKey[node.key]?.let { id ->
            val ref = entries.getValue(id).ref
            entries[id] = Entry(ref, node, NodeFingerprint.of(node))
            return ref
        }
        val id = ids.next()
        val ref = "n$id"
        byKey[node.key] = id
        entries[id] = Entry(ref, node, NodeFingerprint.of(node))
        return ref
    }

    /** Looks up a ref; throws [ToolException] with guidance for malformed, stale, foreign or unknown refs. */
    fun lookup(raw: String): Entry {
        val id = parseRef(raw) ?: throw ToolException("'$raw' is not a valid element ref. Refs look like n12 and come from screen_observe, screen_read or screen_find.")
        synchronized(lock) {
            if (!started) throw ToolException("No element refs yet. Call screen_observe (or screen_read) first, then use the refs it returns.")
            return entries[id] ?: throw ToolException(
                "Ref n$id is not from your latest screen_observe/screen_read/screen_find: each screen_observe or screen_read replaces the refs, and refs only " +
                    "work for the agent that read them. Call screen_observe again and use the refs it returns.",
            )
        }
    }

    companion object {
        private val REF = Regex("^\\s*\\[?#?[nN]?(\\d{1,9})]?\\s*$")

        /** Parses `n12`, `N12`, `12` or `[n12]` to 12; null if malformed. */
        fun parseRef(raw: String): Int? = REF.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}
