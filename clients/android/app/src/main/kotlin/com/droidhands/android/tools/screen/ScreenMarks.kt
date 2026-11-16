package com.droidhands.android.tools.screen

import com.droidhands.android.access.Bounds
import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.TextNorm
import com.droidhands.android.access.TreeSerializer
import com.droidhands.android.access.UiNode
import com.droidhands.android.access.WindowKind
import com.droidhands.android.access.WindowSnapshot

/**
 * Which elements screen_observe marks (pure): what an agent can act on, the way "browser use" numbers the interactive
 * elements of a page. Nothing here knows any app: it only looks at what the accessibility layer reports.
 */
object ScreenMarks {
    /** Boxes covering at least this share of the screen are listed but not drawn (they would hide everything else). */
    private const val MAX_DRAWN_SCREEN_SHARE = 0.6

    /** A thin tree: fewer informative elements than this in the app's windows while it shows something. */
    private const val THIN_TREE_NODES = 3

    /** One marked element and whether its box is drawn on the screenshot. */
    data class Mark(val node: UiNode, val box: Bounds, val drawn: Boolean)

    /** A node the agent can act on: tap, long-press, type into, scroll or toggle. */
    fun isActionable(n: UiNode): Boolean =
        n.clickable || n.longClickable || n.editable || n.checkable || NodeQueries.isScrollContainer(n)

    /**
     * The actionable, visible elements of [windows] (on-screen part of their bounds), in reading order: window by window,
     * then top to bottom, left to right. An element is skipped when an actionable ancestor has the same box (one target,
     * one number). At most [max].
     */
    fun select(windows: List<WindowSnapshot>, screen: Bounds, max: Int): List<Mark> {
        val out = ArrayList<Mark>()
        val screenArea = screen.area.coerceAtLeast(1)
        for (w in windows) {
            val root = w.root ?: continue
            val inWindow = ArrayList<Mark>()
            for (n in root.walk()) {
                if (!n.visible || !isActionable(n)) continue
                val box = n.bounds.intersect(screen)
                if (box.isEmpty) continue
                if (n.ancestors().any { isActionable(it) && it.visible && it.bounds.intersect(screen) == box }) continue
                inWindow += Mark(n, box, drawn = box.area < screenArea * MAX_DRAWN_SCREEN_SHARE)
            }
            inWindow.sortWith(compareBy({ it.box.top }, { it.box.left }))
            out += inWindow
            if (out.size >= max) break
        }
        return out.take(max.coerceAtLeast(0))
    }

    /**
     * True when the app in front exposes almost nothing to accessibility (typical of games, Flutter, canvas and some
     * video apps): the agent should work from the screenshot and tap by coordinates instead.
     */
    fun thinTree(windows: List<WindowSnapshot>): Boolean {
        val app = windows.filter { it.kind == WindowKind.APPLICATION && !it.hidden && it.root != null }
        if (app.isEmpty()) return false
        val informative = app.sumOf { w -> w.root!!.walk().count { it.visible && TreeSerializer.isInformative(it) } }
        return informative < THIN_TREE_NODES
    }

    /**
     * For an element without text or description of its own (a clickable row, a card): the texts inside it, so a
     * text-only agent can tell such elements apart (`"carol@example.com | Offsite | Are you joining?"`). Null otherwise.
     */
    fun innerText(n: UiNode, maxParts: Int = 3, maxLength: Int = 120): String? {
        if (!n.text.isNullOrBlank() || !n.description.isNullOrBlank()) return null
        val parts = n.walk().drop(1).filter { it.visible }
            .flatMap { sequenceOf(it.text, it.description) }
            .mapNotNull { TextNorm.clean(it).replace('\n', ' ').trim().takeIf { t -> t.isNotEmpty() } }
            .distinct()
            .take(maxParts)
            .toList()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.let { TextNorm.truncate(it, maxLength) }
    }

    /** The number drawn on the screenshot for [ref] (`n12` → `12`). */
    fun tag(ref: String): String = ref.removePrefix("n")
}

/**
 * Where the calling agent's last screenshot came from, so ui_tap and ui_swipe can take points in image pixels
 * (`space=screenshot`, like computer-use agents expect) and map them back to screen pixels (pure).
 */
data class ScreenshotGeometry(val imageWidth: Int, val imageHeight: Int, val screenWidth: Int, val screenHeight: Int) {
    /** Screen pixels of the image point ([x], [y]); null when the point is outside the image. */
    fun toScreen(x: Int, y: Int): Pair<Int, Int>? {
        if (x !in 0 until imageWidth || y !in 0 until imageHeight) return null
        val sx = (x.toDouble() * screenWidth / imageWidth).toInt().coerceIn(0, screenWidth - 1)
        val sy = (y.toDouble() * screenHeight / imageHeight).toInt().coerceIn(0, screenHeight - 1)
        return sx to sy
    }

    /** False once the screen size changed (rotation, folding): the image no longer maps onto the screen. */
    fun matches(screen: Bounds): Boolean = screen.width == screenWidth && screen.height == screenHeight
}
