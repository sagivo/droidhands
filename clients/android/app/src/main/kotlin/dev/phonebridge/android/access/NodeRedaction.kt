package dev.phonebridge.android.access

/**
 * Applies a text redactor (the core `OneTimeCodes.redact` when the user hides one-time codes) to captured nodes, so
 * everything the screen tools derive from them (output, text matching, labels) only sees the masked text (pure).
 * Node identity ([UiNode.handle]) is kept, so refs and actions still work on the copies.
 */
object NodeRedaction {
    /**
     * [root] with [redact] applied to the text, description, hint and state description of every node. Returns [root]
     * itself (same instance) when nothing changed; otherwise a rebuilt tree that replaces it (unchanged subtrees are
     * re-parented into the copy, as with [UiNode.withChildren]).
     */
    fun tree(root: UiNode, redact: (String) -> String): UiNode {
        val children = root.children.map { tree(it, redact) }
        val childrenChanged = children.indices.any { children[it] !== root.children[it] }
        return copy(root, redact, children, force = childrenChanged)
    }

    /** [node] (a shallow capture, e.g. after a refresh) with its texts redacted; [node] itself when nothing changed. */
    fun node(node: UiNode, redact: (String) -> String): UiNode = copy(node, redact, node.children, force = false)

    /** True if a visible node of [roots] shows a text (text, description or state description) for which [test] holds. */
    fun anyVisibleText(roots: List<UiNode>, test: (String) -> Boolean): Boolean =
        NodeQueries.visibleNodes(roots).any { n -> listOfNotNull(n.text, n.description, n.stateDescription).any(test) }

    private fun copy(n: UiNode, redact: (String) -> String, children: List<UiNode>, force: Boolean): UiNode {
        val text = n.text?.let(redact)
        val description = n.description?.let(redact)
        val hint = n.hint?.let(redact)
        val state = n.stateDescription?.let(redact)
        val same = text == n.text && description == n.description && hint == n.hint && state == n.stateDescription
        if (same && !force) return n
        return UiNode(
            text = text, description = description, hint = hint, stateDescription = state, viewId = n.viewId,
            className = n.className, packageName = n.packageName, bounds = n.bounds, visible = n.visible, clickable = n.clickable,
            longClickable = n.longClickable, editable = n.editable, scrollable = n.scrollable, checkable = n.checkable,
            checked = n.checked, selected = n.selected, focused = n.focused, enabled = n.enabled, password = n.password,
            actions = n.actions, children = children, handle = n.handle,
        )
    }
}
