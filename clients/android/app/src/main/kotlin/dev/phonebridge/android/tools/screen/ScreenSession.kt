package dev.phonebridge.android.tools.screen

import dev.phonebridge.android.access.NodeFingerprint
import dev.phonebridge.android.access.RefRegistry
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.core.tools.ToolException

/** Element refs shared by all screen tools (refs are valid until the next screen_read). */
object ScreenSession {
    val refs = RefRegistry()

    /** A ref resolved against the live UI: [node] keeps the captured ancestors, [fresh] has current state. */
    class Live(val ref: String, val node: UiNode, val fresh: UiNode)

    /**
     * Resolves [raw] and re-validates it with `refresh()`. Refs are shared by all agents, so a ref read by
     * another agent that points into an app blocked for this one is refused before anything about it is
     * revealed. Blocking IPC: call on Dispatchers.IO.
     */
    fun resolve(raw: String, access: ScreenAccess): Live {
        val entry = refs.lookup(raw)
        entry.node.packageName?.let { if (!access.allows(it)) access.refuse(it, "Element ${entry.ref} belongs to a blocked app.") }
        val fresh = NodeActions.refresh(entry.node)
            ?: throw ToolException("Element ${entry.ref} is no longer on screen; call screen_read again.")
        if (!entry.fingerprint.matches(NodeFingerprint.of(fresh))) {
            throw ToolException(
                "Element ${entry.ref} changed since it was read (it now shows ${TreeSerializer.label(fresh)}); call screen_read again.",
            )
        }
        return Live(entry.ref, entry.node, fresh)
    }
}
