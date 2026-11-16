package com.droidhands.core.screen

import com.droidhands.core.tools.ToolException
import kotlinx.coroutines.sync.Mutex
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identity of an element as the agent saw it, to detect refs that now point at different content (a recycled list row
 * after scrolling). The value is left out since typing changes it.
 */
internal data class Fingerprint(val type: String, val identifier: String?, val label: String?, val placeholder: String?) {
    companion object {
        fun of(e: UiElement) = Fingerprint(
            type = e.type,
            identifier = e.identifier?.takeIf { it.isNotBlank() },
            label = TextNorm.key(e.label).ifEmpty { null },
            placeholder = TextNorm.key(e.placeholder).ifEmpty { null },
        )
    }
}

/**
 * Maps element refs (`n<id>`) to the elements of one agent's latest snapshot. screen_observe and screen_read start a
 * new snapshot; screen_find, ui_wait and ambiguity errors add to it (an element already listed keeps its ref). Ids
 * come from one process-wide counter and are never reused, neither across snapshots nor across agents: a ref from an
 * older snapshot or another agent is rejected instead of silently pointing at a different element. Thread-safe.
 */
internal class RefRegistry {
    /** A registered element and the fingerprint it had when the agent saw it. */
    class Entry(val ref: String, val node: Node, val fingerprint: Fingerprint)

    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    private val byKey = HashMap<String, Int>()
    private var started = false

    /** Discards all refs, then runs [block] with a registering function, atomically. */
    fun <T> newSnapshot(block: (register: (Node) -> String) -> T): T = synchronized(lock) {
        started = true
        entries.clear()
        byKey.clear()
        block(::registerLocked)
    }

    /** Adds [node] to the current snapshot; an element already listed keeps its ref (its entry is updated). */
    fun register(node: Node): String = synchronized(lock) { registerLocked(node) }

    private fun registerLocked(node: Node): String {
        started = true
        val key = node.key + "|" + Fingerprint.of(node.el).hashCode()
        byKey[key]?.let { id ->
            val ref = entries.getValue(id).ref
            entries[id] = Entry(ref, node, Fingerprint.of(node.el))
            return ref
        }
        val id = IDS.incrementAndGet()
        val ref = "n$id"
        byKey[key] = id
        entries[id] = Entry(ref, node, Fingerprint.of(node.el))
        return ref
    }

    /** Looks up a ref; throws [ToolException] with guidance for malformed, stale, foreign or unknown refs. */
    fun lookup(raw: String): Entry {
        val id = parseRef(raw)
            ?: throw ToolException("'$raw' is not a valid element ref. Refs look like n12 and come from screen_observe, screen_read or screen_find.")
        synchronized(lock) {
            if (!started) throw ToolException("No element refs yet. Call screen_observe (or screen_read) first, then use the refs it returns.")
            return entries[id] ?: throw ToolException(
                "Ref n$id is not from your latest screen_observe/screen_read/screen_find: each screen_observe or screen_read " +
                    "replaces the refs, and refs only work for the agent that read them. Call screen_observe again and use the refs it returns.",
            )
        }
    }

    companion object {
        /** Shared by every agent and device, so no id is ever handed out twice in this process. */
        private val IDS = AtomicInteger()

        private val REF = Regex("^\\s*\\[?#?[nN]?(\\d{1,9})]?\\s*$")

        /** Parses `n12`, `N12`, `12` or `[n12]` to 12; null if malformed. */
        fun parseRef(raw: String): Int? = REF.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

        /**
         * [entry]'s element in a newer [capture]: at the same place with the same fingerprint, else the one element with
         * that fingerprint (and the same bounds when several have it). Throws "call screen_observe again" when it is
         * gone or now shows something else.
         */
        fun locate(entry: Entry, capture: Capture): Node {
            val candidates = capture.allNodes().filter { it.owner == entry.node.owner }.toList()
            val atPath = candidates.firstOrNull { it.path == entry.node.path }
            if (atPath != null && Fingerprint.of(atPath.el) == entry.fingerprint) return atPath
            val same = candidates.filter { Fingerprint.of(it.el) == entry.fingerprint }
            same.singleOrNull()?.let { return it }
            same.firstOrNull { it.bounds == entry.node.bounds }?.let { return it }
            if (atPath != null && same.isEmpty()) {
                throw ToolException(
                    "Element ${entry.ref} changed since it was read (it now shows ${ScreenFormat.label(atPath.el)}); call screen_observe again.",
                )
            }
            throw ToolException("Element ${entry.ref} is no longer on screen; call screen_observe again.")
        }
    }
}

/**
 * Per-device state of phone use, shared by its screen tools and screen recipes: one [mutex] so gestures never
 * interleave, each agent's refs, and the geometry of each agent's last screenshot. Kept per driver instance.
 */
internal class DriverState {
    val mutex = Mutex()

    private val registries = lru<RefRegistry>()
    private val screenshots = lru<ScreenshotGeometry>()

    /** The refs of the agent with [tokenId]. */
    fun refs(tokenId: String): RefRegistry = synchronized(registries) { registries.getOrPut(tokenId) { RefRegistry() } }

    /** Remembers the size of the screenshot just sent to the agent with [tokenId] (for `space=screenshot` points). */
    fun recordScreenshot(tokenId: String, geometry: ScreenshotGeometry) {
        synchronized(screenshots) { screenshots[tokenId] = geometry }
    }

    fun lastScreenshot(tokenId: String): ScreenshotGeometry? = synchronized(screenshots) { screenshots[tokenId] }

    companion object {
        /** Agents remembered at most (least recently used dropped first). */
        private const val MAX_AGENTS = 32

        private val states = WeakHashMap<ScreenDriver, DriverState>()

        fun of(driver: ScreenDriver): DriverState = synchronized(states) { states.getOrPut(driver) { DriverState() } }

        private fun <V> lru(): LinkedHashMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > MAX_AGENTS
        }
    }
}
