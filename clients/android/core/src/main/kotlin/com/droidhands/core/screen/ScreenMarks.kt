package com.droidhands.core.screen

/**
 * Which elements screen_observe marks (pure): what an agent can act on, the way "browser use" numbers the interactive
 * elements of a page. Nothing here knows any app: it only looks at what the platform's accessibility layer reports.
 */
internal object ScreenMarks {
    /** Boxes covering at least this share of the screen are listed but not drawn (they would hide everything else). */
    private const val MAX_DRAWN_SCREEN_SHARE = 0.6

    /** A thin tree: fewer informative elements than this in the foreground app while it shows something. */
    private const val THIN_TREE_NODES = 3

    /** One marked element and whether its box is drawn on the screenshot. */
    class Mark(val node: Node, val box: Rect, val drawn: Boolean)

    /** An element the agent can act on: tap, type into or scroll. */
    fun markable(e: UiElement): Boolean = e.actionable || e.editable || e.scrollable

    /**
     * The markable, visible elements (on-screen part of their bounds), in reading order: overlays first (they are on top
     * of the app and take the touches), then the app; within a window top to bottom, left to right. An element is
     * skipped when a markable ancestor has the same box (one target, one number). At most [max].
     */
    fun select(capture: Capture, max: Int): List<Mark> {
        val out = ArrayList<Mark>()
        val screen = capture.screen
        val screenArea = (screen.width.toLong() * screen.height).coerceAtLeast(1)
        val ordered = capture.windows.filter { !it.primary } + capture.windows.filter { it.primary }
        for (w in ordered) {
            val inWindow = ArrayList<Mark>()
            for (n in w.root.walk()) {
                if (!markable(n.el)) continue
                val box = n.visibleBounds
                if (box.isEmpty) continue
                if (n.ancestors().any { markable(it.el) && it.visibleBounds == box }) continue
                inWindow += Mark(n, box, drawn = box.width.toLong() * box.height < screenArea * MAX_DRAWN_SCREEN_SHARE)
            }
            inWindow.sortWith(compareBy({ it.box.y }, { it.box.x }))
            out += inWindow
            if (out.size >= max) break
        }
        return out.take(max.coerceAtLeast(0))
    }

    /**
     * True when the app in front exposes almost nothing to accessibility (games, Flutter, canvas and some video apps):
     * the agent should work from the screenshot and tap by coordinates instead. False on the lock screen and when the
     * foreground app is unknown.
     */
    fun thinTree(capture: Capture): Boolean {
        if (capture.locked || capture.foreground == null || capture.blockedForeground != null) return false
        val root = capture.primaryRoot ?: return true
        return root.walk().count { it.visible && ScreenFormat.isInformative(it.el) } < THIN_TREE_NODES
    }

    /**
     * For an element without a label or value of its own (a tappable row, a card): the texts inside it, so a text-only
     * agent can tell such elements apart (`"carol@example.com | Offsite | Are you joining?"`). Null otherwise.
     */
    fun innerText(n: Node, maxParts: Int = 3, maxLength: Int = 120): String? {
        if (!n.el.label.isNullOrBlank() || !n.el.value.isNullOrBlank()) return null
        val parts = n.walk().drop(1).filter { it.visible }
            .flatMap { sequenceOf(it.el.label, if (it.el.secure) null else it.el.value) }
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
 * (`space=screenshot`) and map them back to screen points (pure).
 */
internal data class ScreenshotGeometry(val imageWidth: Int, val imageHeight: Int, val screenWidth: Int, val screenHeight: Int) {
    /** Screen points of the image point ([x], [y]); null when the point is outside the image. */
    fun toScreen(x: Int, y: Int): Pair<Int, Int>? {
        if (x !in 0 until imageWidth || y !in 0 until imageHeight) return null
        val sx = (x.toDouble() * screenWidth / imageWidth).toInt().coerceIn(0, screenWidth - 1)
        val sy = (y.toDouble() * screenHeight / imageHeight).toInt().coerceIn(0, screenHeight - 1)
        return sx to sy
    }

    /** False once the screen size changed (rotation): the image no longer maps onto the screen. */
    fun matches(screen: Size): Boolean = screen.width == screenWidth && screen.height == screenHeight
}

/**
 * What the screen looked like, reduced to what tells an agent whether its action did anything (pure): the app in
 * front, the overlays on top, the keyboard and the visible texts.
 */
internal data class ScreenSignature(
    val app: String?,
    val windows: List<String>,
    val keyboard: Boolean,
    val texts: List<String>,
    /** Value of the focused text field, if any (shows what typing did). */
    val focusedText: String?,
) {
    companion object {
        private const val MAX_TEXTS = 80
        private const val MAX_TEXT_LENGTH = 80

        fun of(capture: Capture): ScreenSignature {
            val focused = capture.visibleNodes().firstOrNull { it.el.focused && it.el.editable && !it.el.secure }
            return ScreenSignature(
                app = capture.foreground,
                windows = capture.windows.filter { !it.primary }.map { it.title },
                keyboard = capture.snapshot.keyboardShown,
                texts = Queries.topTexts(capture.allNodes(), MAX_TEXTS, MAX_TEXT_LENGTH),
                focusedText = focused?.let { TextNorm.truncate(TextNorm.clean(it.el.value).trim(), MAX_TEXT_LENGTH) },
            )
        }
    }
}

/** Describes the difference between two [ScreenSignature]s in one short line for the agent (pure; Android's wording). */
internal object ScreenChange {
    private const val SHOWN_TEXTS = 6

    fun describe(before: ScreenSignature, after: ScreenSignature): String {
        val parts = ArrayList<String>()
        if (before.app != after.app) parts += "now in app ${after.app ?: "unknown"} (was ${before.app ?: "unknown"})"
        val opened = after.windows - before.windows.toSet()
        val closed = before.windows - after.windows.toSet()
        if (opened.isNotEmpty()) parts += "opened ${opened.joinToString { "\"$it\"" }}"
        if (closed.isNotEmpty()) parts += "closed ${closed.joinToString { "\"$it\"" }}"
        if (after.keyboard != before.keyboard) parts += if (after.keyboard) "keyboard opened" else "keyboard closed"
        if (after.focusedText != before.focusedText && after.focusedText != null) parts += "focused field shows \"${after.focusedText}\""
        val beforeTexts = before.texts.toSet()
        val afterTexts = after.texts.toSet()
        val added = after.texts.filter { it !in beforeTexts }
        val removed = before.texts.filter { it !in afterTexts }
        if (added.isNotEmpty()) parts += "new text: " + quoted(added)
        if (removed.isNotEmpty()) parts += "gone: " + quoted(removed)
        return if (parts.isEmpty()) ScreenTexts.NO_CHANGE else "Screen changed: ${parts.joinToString("; ")}."
    }

    private fun quoted(texts: List<String>): String {
        val shown = texts.take(SHOWN_TEXTS).joinToString(", ") { "\"$it\"" }
        return if (texts.size > SHOWN_TEXTS) "$shown (+${texts.size - SHOWN_TEXTS} more)" else shown
    }
}
