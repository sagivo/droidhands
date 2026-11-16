package com.droidhands.android.access

/** screen_find criteria. Text and description match "contains", view id matches exactly (full or short id). */
data class FindCriteria(
    val text: String? = null,
    val viewId: String? = null,
    val description: String? = null,
    /** Only nodes that respond to taps themselves or through a clickable ancestor. */
    val clickableOnly: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && viewId.isNullOrBlank() && description.isNullOrBlank() && !clickableOnly
}

/** Outcome of resolving `ui_tap(text=...)`. */
sealed interface TextTarget {
    data class Found(val node: UiNode, val level: MatchLevel) : TextTarget

    /** Several equally good matches; [candidates] are de-duplicated by click target, best first. */
    data class Ambiguous(val candidates: List<UiNode>, val level: MatchLevel) : TextTarget

    data object NotFound : TextTarget
}

/** Outcome of picking the field for `ui_type` without a ref. */
sealed interface EditableChoice {
    data class Found(val node: UiNode) : EditableChoice
    data class Ambiguous(val candidates: List<UiNode>) : EditableChoice
    data object None : EditableChoice
}

/** Pure queries over captured trees. "Visible" means `isVisibleToUser`; invisible subtrees are still descended. */
object NodeQueries {
    /** How many ancestors to climb looking for a clickable container. */
    const val MAX_CLICK_ANCESTORS = 8

    /** Visible nodes of all [roots], pre-order. */
    fun visibleNodes(roots: List<UiNode>): Sequence<UiNode> = roots.asSequence().flatMap { it.walk() }.filter { it.visible }

    /** The node itself if clickable and enabled, else the nearest such ancestor (or null). */
    fun clickTarget(node: UiNode): UiNode? =
        (sequenceOf(node) + node.ancestors().take(MAX_CLICK_ANCESTORS)).firstOrNull { it.clickable && it.enabled }

    /** Like [clickTarget] for long-clicks. */
    fun longClickTarget(node: UiNode): UiNode? =
        (sequenceOf(node) + node.ancestors().take(MAX_CLICK_ANCESTORS)).firstOrNull { it.longClickable && it.enabled }

    /** screen_find: all visible nodes matching every given criterion, pre-order. */
    fun find(roots: List<UiNode>, criteria: FindCriteria): List<UiNode> {
        val textKey = criteria.text?.let(TextNorm::key)?.takeIf { it.isNotEmpty() }
        val descKey = criteria.description?.let(TextNorm::key)?.takeIf { it.isNotEmpty() }
        val idKey = criteria.viewId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return visibleNodes(roots).filter { n ->
            (textKey == null || TextNorm.key(n.text).contains(textKey)) &&
                (descKey == null || TextNorm.key(n.description).contains(descKey)) &&
                (idKey == null || idMatches(n, idKey)) &&
                (!criteria.clickableOnly || clickTarget(n) != null || longClickTarget(n) != null)
        }.toList()
    }

    private fun idMatches(n: UiNode, idKey: String): Boolean {
        val full = n.viewId?.lowercase() ?: return false
        return full == idKey || n.shortId?.lowercase() == idKey || (idKey.startsWith(":id/") && full.endsWith(idKey))
    }

    /** Best match level of [node]'s text or content description against [queryKey]. */
    fun textLevel(node: UiNode, queryKey: String): MatchLevel =
        maxOf(matchLevel(queryKey, TextNorm.key(node.text)), matchLevel(queryKey, TextNorm.key(node.description)))

    /**
     * Resolves `ui_tap(text=...)`: exact > starts-with > contains on text or description
     * (case-insensitive); among the best level, clickable nodes win. Matches that would click the
     * same target (a label and its clickable row) count once. Several distinct targets → ambiguous.
     */
    fun resolveTapText(roots: List<UiNode>, query: String): TextTarget {
        val q = TextNorm.key(query)
        if (q.isEmpty()) return TextTarget.NotFound
        val scored = visibleNodes(roots).map { it to textLevel(it, q) }.filter { it.second != MatchLevel.NONE }.toList()
        if (scored.isEmpty()) return TextTarget.NotFound
        val best = scored.maxOf { it.second }
        var candidates = scored.filter { it.second == best }.map { it.first }
        val tappable = candidates.filter { clickTarget(it) != null }
        if (tappable.isNotEmpty()) candidates = tappable
        val distinct = LinkedHashMap<Any, UiNode>()
        for (c in candidates) distinct.putIfAbsent((clickTarget(c) ?: c).key, c)
        val unique = distinct.values.toList()
        return if (unique.size == 1) TextTarget.Found(unique[0], best) else TextTarget.Ambiguous(unique, best)
    }

    /** Field for ui_type when no ref is given: the input-focused editable, else the only visible editable. */
    fun chooseEditable(focused: UiNode?, roots: List<UiNode>): EditableChoice {
        if (focused != null && focused.editable) return EditableChoice.Found(focused)
        val focusedInTree = visibleNodes(roots).firstOrNull { it.editable && it.focused }
        if (focusedInTree != null) return EditableChoice.Found(focusedInTree)
        val editables = visibleNodes(roots).filter { it.editable }.toList()
        return when (editables.size) {
            0 -> EditableChoice.None
            1 -> EditableChoice.Found(editables[0])
            else -> EditableChoice.Ambiguous(editables)
        }
    }

    /** First editable in [node]'s subtree (the node itself included), for refs that point at a field's container. */
    fun editableWithin(node: UiNode): UiNode? = node.walk().firstOrNull { it.editable && it.visible }

    /** The largest visible scrollable container (by on-screen area), or null. */
    fun largestScrollable(roots: List<UiNode>): UiNode? =
        visibleNodes(roots).filter { isScrollContainer(it) && !it.bounds.isEmpty }.maxByOrNull { it.bounds.area }

    fun isScrollContainer(n: UiNode): Boolean = n.scrollable || n.actions.any { it in SCROLL_ACTIONS }

    /** Deepest visible node whose bounds contain the point (top-most in drawing order wins ties). */
    fun deepestAt(root: UiNode, x: Int, y: Int): UiNode? {
        var found: UiNode? = null
        for (n in root.walk()) {
            if (n.visible && n.bounds.contains(x, y)) found = n
        }
        return found
    }

    /** The first [limit] distinct visible texts/descriptions (for error messages), truncated. */
    fun topTexts(roots: List<UiNode>, limit: Int = 8, maxLength: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        for (n in visibleNodes(roots)) {
            for (s in listOf(n.text, n.description)) {
                val clean = TextNorm.clean(s).replace('\n', ' ').trim()
                if (clean.isNotEmpty()) out += TextNorm.truncate(clean, maxLength)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }

    private val SCROLL_ACTIONS = setOf(
        UiAction.SCROLL_FORWARD, UiAction.SCROLL_BACKWARD,
        UiAction.SCROLL_UP, UiAction.SCROLL_DOWN, UiAction.SCROLL_LEFT, UiAction.SCROLL_RIGHT,
    )
}
