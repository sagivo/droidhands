package dev.phonebridge.android.access

// Platform-neutral, immutable snapshot of an accessibility tree. Captured once from
// AccessibilityNodeInfo (see NodeCapture) so that serialization, matching and the WhatsApp
// heuristics are pure Kotlin and unit-testable without Android.

/** Rectangle in screen pixels. [right] and [bottom] are exclusive, as in `android.graphics.Rect`. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Intersection with [other], or [EMPTY] when they don't overlap. */
    fun intersect(other: Bounds): Bounds {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) Bounds(l, t, r, b) else EMPTY
    }

    /** Smallest rectangle containing both. */
    fun union(other: Bounds): Bounds = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> Bounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
    }

    /** `(x,y,w,h)`, the form used in screen_read output. */
    fun compact(): String = "($left,$top,$width,$height)"

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/** Accessibility actions a node advertises (subset relevant to the tools). */
enum class UiAction {
    CLICK, LONG_CLICK, FOCUS, SET_TEXT, IME_ENTER,
    SCROLL_FORWARD, SCROLL_BACKWARD, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT,
}

/**
 * One node of a captured UI tree. Plain values only, except [handle], which carries the platform
 * object (an `AccessibilityNodeInfo` on Android, null in tests) so actions can be performed later.
 *
 * [text] is null when an editable is merely showing its [hint].
 */
class UiNode(
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val stateDescription: String? = null,
    /** Full resource name, e.g. `com.whatsapp:id/send`. */
    val viewId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    /** Has input focus. */
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val actions: Set<UiAction> = emptySet(),
    children: List<UiNode> = emptyList(),
    val handle: Any? = null,
) {
    val children: List<UiNode> = children

    /** Parent in the captured tree (null for a window root). */
    var parent: UiNode? = null
        private set

    init {
        for (child in children) child.parent = this
    }

    /** View id after `:id/` (e.g. `send`), or the raw id when it has no package part. */
    val shortId: String? get() = viewId?.let { if (it.contains(":id/")) it.substringAfter(":id/") else it }?.takeIf { it.isNotBlank() }

    /** `Button` for `android.widget.Button`; `View` when unknown. */
    val simpleClassName: String
        get() = className?.substringAfterLast('.')?.substringAfterLast('$')?.takeIf { it.isNotBlank() } ?: "View"

    /** Identity used to de-duplicate refs: the platform node (equal across fetches) or this object. */
    val key: Any get() = handle ?: this

    /** This node and all descendants, depth-first pre-order. */
    fun walk(): Sequence<UiNode> = sequence {
        val stack = ArrayDeque<UiNode>()
        stack.addLast(this@UiNode)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            yield(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }

    /** Parent, grand-parent, ... up to the window root. */
    fun ancestors(): Sequence<UiNode> = generateSequence(parent) { it.parent }

    /**
     * A copy of this node (same [handle], so the same [key]) with [children] instead. The given children are
     * re-parented to the copy, so only use it while rebuilding a tree that replaces this one.
     */
    fun withChildren(children: List<UiNode>): UiNode = UiNode(
        text = text, description = description, hint = hint, stateDescription = stateDescription, viewId = viewId,
        className = className, packageName = packageName, bounds = bounds, visible = visible, clickable = clickable,
        longClickable = longClickable, editable = editable, scrollable = scrollable, checkable = checkable,
        checked = checked, selected = selected, focused = focused, enabled = enabled, password = password,
        actions = actions, children = children, handle = handle,
    )

    override fun toString(): String = "UiNode($simpleClassName text=$text desc=$description id=$viewId ${bounds.compact()})"
}

/** Coarse window types (mirrors `AccessibilityWindowInfo.TYPE_*`). */
enum class WindowKind { APPLICATION, SYSTEM, INPUT_METHOD, OVERLAY, OTHER }

/**
 * A captured window. [hidden] windows (PhoneBridge's own screens) have no [root]: their content is
 * withheld from agents. Lists of snapshots are ordered primary window first, then by z-order.
 */
data class WindowSnapshot(
    val title: String?,
    val kind: WindowKind,
    val packageName: String?,
    val bounds: Bounds,
    val primary: Boolean,
    val root: UiNode?,
    val hidden: Boolean = false,
)
