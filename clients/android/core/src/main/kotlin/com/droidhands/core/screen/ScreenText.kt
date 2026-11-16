package com.droidhands.core.screen

import java.util.Locale

/**
 * Text normalization for matching on-screen strings (pure). Apps wrap phone numbers and RTL text in invisible
 * bidi/zero-width marks and use non-breaking spaces, so raw comparisons fail.
 */
internal object TextNorm {
    // Zero-width chars, LRM/RLM, bidi embeddings/isolates, word joiner, BOM.
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]")
    private val ODD_SPACES = Regex("[\\u00A0\\u2007\\u202F]")
    private val WHITESPACE = Regex("\\s+")

    /** Removes invisible marks and odd spaces but keeps case and line breaks. */
    fun clean(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return ODD_SPACES.replace(INVISIBLE.replace(s, ""), " ").replace("\r\n", "\n").replace('\r', '\n')
    }

    /** Matching key: [clean], whitespace collapsed, trimmed, lower-cased (locale-neutral). */
    fun key(s: String?): String = WHITESPACE.replace(clean(s), " ").trim().lowercase(Locale.ROOT)

    /** Truncates to at most [max] chars (never splitting a surrogate pair), appending an ellipsis. */
    fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end) + "…"
    }
}

/** How well a candidate string matches a query. Ordered: higher is better. */
internal enum class MatchLevel { NONE, CONTAINS, PREFIX, EXACT }

/** Compares already-[TextNorm.key]ed strings. */
internal fun matchLevel(queryKey: String, candidateKey: String): MatchLevel = when {
    queryKey.isEmpty() || candidateKey.isEmpty() -> MatchLevel.NONE
    candidateKey == queryKey -> MatchLevel.EXACT
    candidateKey.startsWith(queryKey) -> MatchLevel.PREFIX
    candidateKey.contains(queryKey) -> MatchLevel.CONTAINS
    else -> MatchLevel.NONE
}

/**
 * The screen tools' text formats (pure), the same as on Android:
 *
 * ```
 * app=com.apple.MobileSMS (Messages) locked=false
 * n1 NavigationBar "Messages" (0,47,393,96)
 *   n2 Button "Compose" [clickable] (343,55,44,44)
 * window: Allow “Maps” to use your location? (com.apple.springboard)
 *   n3 Button "Allow Once" [clickable] (60,520,273,44)
 * ```
 */
internal object ScreenFormat {
    /** First line of screen_read and screen_observe. */
    fun header(app: String?, label: String?, locked: Boolean): String {
        val name = label?.trim()?.takeIf { it.isNotEmpty() && it != app }?.let { " ($it)" }.orEmpty()
        return "app=${app ?: "unknown"}$name locked=$locked"
    }

    /** Result of [serialize]. */
    class Output(val text: String, val printed: Int, val truncated: Int)

    /**
     * Serializes [windows] (primary first; others under `window:` headers). [assignRef] is called once per printed
     * node, in output order, and returns its ref.
     */
    fun serialize(windows: List<Window>, includeAll: Boolean, maxNodes: Int, assignRef: (Node) -> String): Output {
        val out = StringBuilder()
        var printed = 0
        var truncated = 0
        val max = maxNodes.coerceAtLeast(1)

        fun line(depth: Int, s: String) {
            repeat(depth) { out.append("  ") }
            out.append(s).append('\n')
        }

        fun visit(node: Node, depth: Int) {
            val show = node.visible && (includeAll || isInformative(node.el))
            if (show) {
                if (printed < max) {
                    line(depth, formatNode(node.el, assignRef(node)))
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
            if (!w.primary) line(0, "window: ${w.title}")
            visit(w.root, base)
        }
        if (truncated > 0) line(0, "... truncated $truncated nodes (raise max_nodes or use screen_find)")
        return Output(out.toString().trimEnd('\n'), printed, truncated)
    }

    /** An element worth showing by default: has a label, value, id or placeholder, or can be acted on. */
    fun isInformative(e: UiElement): Boolean =
        !e.label.isNullOrBlank() || !e.value.isNullOrBlank() || !e.identifier.isNullOrBlank() || !e.placeholder.isNullOrBlank() ||
            e.actionable || e.editable || e.scrollable

    /** `n12 Button "Send" value="…" hint="…" id=send [clickable] (x,y,w,h)`. The value is omitted when equal to the label. */
    fun formatNode(e: UiElement, ref: String, maxText: Int = 200): String {
        val sb = StringBuilder(ref).append(' ').append(e.type)
        val label = display(e.label, maxText)
        val value = if (e.secure) null else display(e.value, maxText)
        val hint = display(e.placeholder, maxText)
        if (label != null) sb.append(" \"").append(label).append('"')
        if (value != null && value != label && !(e.editable && value == hint)) sb.append(" value=\"").append(value).append('"')
        if (e.editable && hint != null && hint != label) sb.append(" hint=\"").append(hint).append('"')
        display(e.identifier, maxText)?.let { sb.append(" id=").append(it) }
        val flags = flags(e)
        if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
        sb.append(' ').append(compact(e.bounds))
        return sb.toString()
    }

    /** Short human label used in action results, e.g. `Button "Send"` or `Button id=send`. */
    fun label(e: UiElement): String {
        val sb = StringBuilder(e.type)
        val label = display(e.label, 60)
        val value = if (e.secure) null else display(e.value, 60)
        val id = display(e.identifier, 60)
        when {
            label != null -> sb.append(" \"").append(label).append('"')
            value != null -> sb.append(" value=\"").append(value).append('"')
            id != null -> sb.append(" id=").append(id)
        }
        return sb.toString()
    }

    /** State flags in a fixed order (Android's vocabulary: `clickable` = the platform says it responds to taps). */
    fun flags(e: UiElement): List<String> = buildList {
        if (e.actionable) add("clickable")
        if (e.editable) add("editable")
        if (e.scrollable) add("scrollable")
        if (e.selected) add("selected")
        if (e.focused) add("focused")
        if (!e.enabled) add("disabled")
        if (e.secure) add("password")
    }

    fun compact(r: Rect): String = "(${r.x},${r.y},${r.width},${r.height})"

    /** Cleaned, escaped and truncated text, or null when blank. */
    fun display(s: String?, max: Int): String? {
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

/** Agent-facing sentences shared by the screen tools and screen recipes. */
internal object ScreenTexts {
    const val LOCKED = "The phone is locked. Ask the user to unlock it; screen control does not work on the lock screen."

    const val BUSY = "Another screen action is still running on the phone. Wait a moment and retry."

    const val OBSERVE_ARG = "Also return a new screen_observe (marked screenshot and elements) after the action"

    const val NO_CHANGE =
        "The screen did not visibly change (the tap may have missed, the element may need something else first, or the " +
            "app is still loading: check with screen_observe)."

    const val THIN_TREE =
        "This app exposes almost nothing to accessibility (common for games, Flutter, maps and video): work from the " +
            "screenshot and tap points with ui_tap x,y space=screenshot."

    const val CODES_NOTE =
        "(the user hides one-time codes: verification codes on screen appear as \"${com.droidhands.core.util.OneTimeCodes.MASK}\"; " +
            "ask the user to read a code on the phone if it is really needed)"

    const val CODE_VISIBLE =
        "Can't take a screenshot: the screen shows what looks like a verification (one-time) code, and the user hides those " +
            "from agents (a screenshot can't be masked). Use screen_read instead (codes appear as " +
            "\"${com.droidhands.core.util.OneTimeCodes.MASK}\"), or ask the user to read the code on the phone if it is really needed."

    const val LOCK_SCREEN_SHOT =
        "Can't take a screenshot while the lock screen is showing: it can show content from apps this agent may not access. " +
            "Ask the user to unlock the phone, or use notifications_list to read notifications."

    /** The error after an action brought a blocked app to the front. */
    fun blockedAfterAction(done: String, label: String, wentHome: Boolean): String = buildString {
        append(done).append(' ')
        append("This brought $label to the foreground, but this agent isn't allowed to use $label, so ")
        append(if (wentHome) "DroidHands went back to the home screen." else "DroidHands tried to go back to the home screen.")
        append(" Don't try to open $label another way. If it is needed, tell the user: they can allow it in DroidHands > Agents > (this agent) > Access.")
    }
}
