package dev.phonebridge.android.tools.screen

import dev.phonebridge.android.access.NodeFingerprint
import dev.phonebridge.android.access.RefIds
import dev.phonebridge.android.access.RefRegistry
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.core.tools.ToolException

/**
 * Element refs of the screen tools, one [RefRegistry] per agent (token). Refs are valid until that agent's next
 * screen_read, and ids are never reused (one [RefIds] for all agents), so an agent can neither use another agent's
 * refs nor have an old ref silently point at a different element.
 */
object ScreenSession {
    /** Registries kept at most (least recently used dropped first); each holds one snapshot of nodes. */
    private const val MAX_AGENTS = 32

    private val ids = RefIds()
    private val registries = object : LinkedHashMap<String, RefRegistry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RefRegistry>?): Boolean = size > MAX_AGENTS
    }

    /** The refs of the agent with [tokenId]. */
    fun refs(tokenId: String): RefRegistry = synchronized(registries) { registries.getOrPut(tokenId) { RefRegistry(ids) } }

    /** The refs of the calling agent. */
    fun refs(access: ScreenAccess): RefRegistry = refs(access.tokenId)

    private val screenshots = object : LinkedHashMap<String, ScreenshotGeometry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScreenshotGeometry>?): Boolean = size > MAX_AGENTS
    }

    /** Remembers the size of the screenshot just sent to the agent with [tokenId] (for `space=screenshot` points). */
    fun recordScreenshot(tokenId: String, geometry: ScreenshotGeometry) {
        synchronized(screenshots) { screenshots[tokenId] = geometry }
    }

    /** The calling agent's last screenshot, or null if it has taken none. */
    fun lastScreenshot(access: ScreenAccess): ScreenshotGeometry? = synchronized(screenshots) { screenshots[access.tokenId] }

    /** A ref resolved against the live UI: [node] keeps the captured ancestors, [fresh] has current state. */
    class Live(val ref: String, val node: UiNode, val fresh: UiNode)

    /**
     * Resolves [raw] among the calling agent's refs and re-validates it with `refresh()`. A ref that points into an app
     * blocked for this agent (e.g. read before the user changed its app access) is refused before anything about it is
     * revealed. Blocking IPC: call on Dispatchers.IO.
     */
    fun resolve(raw: String, access: ScreenAccess): Live {
        val entry = refs(access).lookup(raw)
        entry.node.packageName?.let { if (!access.allows(it)) access.refuse(it, "Element ${entry.ref} belongs to a blocked app.") }
        val fresh = refresh(entry.node, access)
            ?: throw ToolException("Element ${entry.ref} is no longer on screen; call screen_read again.")
        if (!entry.fingerprint.matches(NodeFingerprint.of(fresh))) {
            throw ToolException(
                "Element ${entry.ref} changed since it was read (it now shows ${TreeSerializer.label(fresh)}); call screen_read again.",
            )
        }
        return Live(entry.ref, entry.node, fresh)
    }

    /**
     * [node]'s current state as the agent may see it (one-time codes masked like in its snapshot, see
     * [ScreenAccess.redact]), or null when it is gone. Blocking IPC: call on Dispatchers.IO.
     */
    fun refresh(node: UiNode, access: ScreenAccess): UiNode? = NodeActions.refresh(node)?.let(access::redact)
}
