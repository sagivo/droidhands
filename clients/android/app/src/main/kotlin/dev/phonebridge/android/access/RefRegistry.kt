package dev.phonebridge.android.access

import dev.phonebridge.core.tools.ToolException

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

/**
 * Maps element refs (`n1`..`nN`) to nodes of the latest snapshot. screen_read starts a new
 * snapshot; screen_find and friends append to it (re-using the ref of a node already listed).
 * Thread-safe.
 */
class RefRegistry {
    /** A registered node and the fingerprint it had when the agent saw it. */
    data class Entry(val ref: String, val node: UiNode, val fingerprint: NodeFingerprint)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val byKey = HashMap<Any, Int>()

    /** Number of refs in the current snapshot. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** Discards all refs, then runs [block] with a registering function, atomically. */
    fun <T> newSnapshot(block: (register: (UiNode) -> String) -> T): T = synchronized(lock) {
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
        byKey[node.key]?.let { index ->
            val ref = entries[index].ref
            entries[index] = Entry(ref, node, NodeFingerprint.of(node))
            return ref
        }
        val ref = "n${entries.size + 1}"
        byKey[node.key] = entries.size
        entries += Entry(ref, node, NodeFingerprint.of(node))
        return ref
    }

    /** Looks up a ref; throws [ToolException] with guidance for malformed or unknown refs. */
    fun lookup(raw: String): Entry {
        val index = parseRef(raw) ?: throw ToolException("'$raw' is not a valid element ref. Refs look like n12 and come from screen_read or screen_find.")
        synchronized(lock) {
            if (entries.isEmpty()) throw ToolException("No element refs yet. Call screen_read (or screen_find) first, then use the refs it returns.")
            return entries.getOrNull(index - 1)
                ?: throw ToolException("Unknown ref n$index: the latest screen_read/screen_find returned n1..n${entries.size}. Call screen_read again.")
        }
    }

    companion object {
        private val REF = Regex("^\\s*\\[?#?[nN]?(\\d{1,6})]?\\s*$")

        /** Parses `n12`, `N12`, `12` or `[n12]` to 12; null if malformed. */
        fun parseRef(raw: String): Int? = REF.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}
