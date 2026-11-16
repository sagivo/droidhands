package com.droidhands.android.access

/** Minimal window facts needed to order windows (pure). */
data class WindowFacts(val kind: WindowKind, val active: Boolean, val focused: Boolean, val bounds: Bounds)

/** Picks the primary window and filters windows for reading (pure). */
object WindowSelection {
    /** A system window thinner than this fraction of the screen is a status/navigation bar. */
    private const val BAR_FRACTION = 0.12

    /**
     * Index of the primary window in [windows] (z-ordered, top first): the active application
     * window, else the input-focused one, else the top-most application window, else the active
     * window of any type, else the first. -1 when empty.
     */
    fun primaryIndex(windows: List<WindowFacts>): Int {
        if (windows.isEmpty()) return -1
        val app = { w: WindowFacts -> w.kind == WindowKind.APPLICATION }
        return windows.indexOfFirst { app(it) && it.active }.takeIf { it >= 0 }
            ?: windows.indexOfFirst { app(it) && it.focused }.takeIf { it >= 0 }
            ?: windows.indexOfFirst(app).takeIf { it >= 0 }
            ?: windows.indexOfFirst { it.active }.takeIf { it >= 0 }
            ?: 0
    }

    /** Status bar / navigation bar style system windows (noise in screen_read). */
    fun isSystemBar(kind: WindowKind, bounds: Bounds, screen: Bounds): Boolean {
        if (kind != WindowKind.SYSTEM || screen.isEmpty) return false
        return bounds.height <= screen.height * BAR_FRACTION || bounds.width <= screen.width * BAR_FRACTION
    }

    /**
     * Windows shown by screen_read and searched by find/tap/wait: keyboards, accessibility overlays
     * and other non-app windows are dropped; system bars too unless [includeAll].
     */
    fun forReading(windows: List<WindowSnapshot>, includeAll: Boolean): List<WindowSnapshot> {
        val screen = windows.fold(Bounds.EMPTY) { acc, w -> acc.union(w.bounds) }
        return windows.filter { keep(it.kind, it.primary, it.bounds, screen, includeAll) }
    }

    /** The per-window rule behind [forReading]; [screen] is the union of all window bounds. */
    fun keep(kind: WindowKind, primary: Boolean, bounds: Bounds, screen: Bounds, includeAll: Boolean): Boolean = when (kind) {
        WindowKind.APPLICATION -> true
        WindowKind.SYSTEM -> primary || includeAll || !isSystemBar(kind, bounds, screen)
        WindowKind.INPUT_METHOD -> includeAll
        WindowKind.OVERLAY, WindowKind.OTHER -> primary
    }

    /** Roots of the readable (non-hidden) windows. */
    fun roots(windows: List<WindowSnapshot>): List<UiNode> = windows.filter { !it.hidden }.mapNotNull { it.root }
}
