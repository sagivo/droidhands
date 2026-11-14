package dev.phonebridge.android.access

/** Options for [TreeSerializer.serialize]. */
data class SerializeOptions(
    /** Keep every visible node instead of hoisting the children of non-informative containers. */
    val includeAll: Boolean = false,
    /** Max node lines; the rest is summarized as `... truncated N nodes`. */
    val maxNodes: Int = 300,
    /** Max chars of text/description/hint per node. */
    val maxTextLength: Int = 200,
)

/**
 * Renders captured windows as the compact, LLM-friendly screen_read tree (pure):
 *
 * ```
 * app=com.whatsapp activity=com.whatsapp.Conversation locked=false
 * n1 FrameLayout id=content (0,0,1080,2400)
 *   n2 ImageButton desc="Send" id=send [clickable] (948,2200,120,120)
 * window: Permission (com.google.android.permissioncontroller)
 *   n3 Button "Allow" [clickable] (100,1500,880,140)
 * ```
 */
object TreeSerializer {
    const val HIDDEN_NOTICE = "(PhoneBridge's own screen: content is hidden from agents)"

    /** First line of screen_read. */
    fun header(packageName: String?, activity: String?, locked: Boolean): String =
        "app=${packageName ?: "unknown"} activity=${activity ?: "unknown"} locked=$locked"

    /** Result of [serialize]. */
    data class Output(val text: String, val printed: Int, val truncated: Int)

    /**
     * Serializes [windows] (primary first). [assignRef] is called once per printed node, in output
     * order, and returns its ref (e.g. `n12`).
     */
    fun serialize(windows: List<WindowSnapshot>, options: SerializeOptions, assignRef: (UiNode) -> String): Output {
        val out = StringBuilder()
        var printed = 0
        var truncated = 0
        val max = options.maxNodes.coerceAtLeast(1)

        fun line(depth: Int, s: String) {
            repeat(depth) { out.append("  ") }
            out.append(s).append('\n')
        }

        fun visit(node: UiNode, depth: Int) {
            val show = node.visible && (options.includeAll || isInformative(node))
            if (show) {
                if (printed < max) {
                    line(depth, formatNode(node, assignRef(node), options.maxTextLength))
                    printed++
                } else {
                    truncated++
                }
            }
            val childDepth = if (show) depth + 1 else depth
            for (c in node.children) visit(c, childDepth)
        }

        for (w in windows) {
            val base = if (w.primary) 0 else 1
            if (!w.primary) line(0, "window: ${windowTitle(w)}")
            when {
                w.hidden -> line(base, HIDDEN_NOTICE)
                w.root != null -> visit(w.root, base)
            }
        }
        if (truncated > 0) line(0, "... truncated $truncated nodes (raise max_nodes or use screen_find)")
        return Output(out.toString().trimEnd('\n'), printed, truncated)
    }

    /** A node worth showing by default: has text, description, id or hint, or can be acted on. */
    fun isInformative(n: UiNode): Boolean =
        !n.text.isNullOrBlank() || !n.description.isNullOrBlank() || !n.viewId.isNullOrBlank() ||
            !n.hint.isNullOrBlank() || n.clickable || n.longClickable || n.editable || n.checkable ||
            NodeQueries.isScrollContainer(n)

    /** `n12 Button "Send" desc="Send" id=send [clickable] (x,y,w,h)`. Description is omitted when equal to the text. */
    fun formatNode(n: UiNode, ref: String, maxText: Int = 200): String {
        val sb = StringBuilder(ref).append(' ').append(n.simpleClassName)
        val text = display(n.text, maxText)
        val desc = display(n.description, maxText)
        if (text != null) sb.append(" \"").append(text).append('"')
        if (desc != null && desc != text) sb.append(" desc=\"").append(desc).append('"')
        if (n.editable) display(n.hint, maxText)?.let { if (it != text && it != desc) sb.append(" hint=\"").append(it).append('"') }
        display(n.stateDescription, maxText)?.let { sb.append(" state=\"").append(it).append('"') }
        n.shortId?.let { sb.append(" id=").append(it) }
        val flags = flags(n)
        if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
        sb.append(' ').append(n.bounds.compact())
        return sb.toString()
    }

    /** Short human label used in action confirmations, e.g. `Button "Send"` or `ImageButton desc="Send"`. */
    fun label(n: UiNode): String {
        val sb = StringBuilder(n.simpleClassName)
        val text = display(n.text, 60)
        val desc = display(n.description, 60)
        when {
            text != null -> sb.append(" \"").append(text).append('"')
            desc != null -> sb.append(" desc=\"").append(desc).append('"')
            n.shortId != null -> sb.append(" id=").append(n.shortId)
        }
        return sb.toString()
    }

    /** State flags in a fixed order. */
    fun flags(n: UiNode): List<String> = buildList {
        if (n.clickable) add("clickable")
        if (n.longClickable) add("long-clickable")
        if (n.editable) add("editable")
        if (NodeQueries.isScrollContainer(n)) add("scrollable")
        if (n.checkable) add(if (n.checked) "checked" else "unchecked")
        if (n.selected) add("selected")
        if (n.focused) add("focused")
        if (!n.enabled) add("disabled")
        if (n.password) add("password")
    }

    private fun windowTitle(w: WindowSnapshot): String {
        val title = w.title?.let { TextNorm.clean(it).trim() }?.takeIf { it.isNotEmpty() }
        val pkg = w.packageName
        val kind = w.kind.name.lowercase()
        return when {
            title != null && pkg != null && title != pkg -> "$title ($pkg)"
            title != null -> "$title ($kind)"
            pkg != null -> "$pkg ($kind)"
            else -> kind
        }
    }

    /** Cleaned, escaped and truncated text, or null when blank. */
    private fun display(s: String?, max: Int): String? {
        val clean = TextNorm.clean(s).trim()
        if (clean.isEmpty()) return null
        return escape(TextNorm.truncate(clean, max))
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append(' ')
            else -> if (ch.code < 0x20) append(' ') else append(ch)
        }
    }
}
