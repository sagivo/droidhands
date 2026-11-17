package com.droidhands.android.access

/**
 * System screens that can show other apps' content regardless of which app is in the foreground: notification
 * previews (shade, lock screen, heads-up) or task thumbnails (recents).
 */
enum class SystemOverlay(val description: String) {
    NOTIFICATION_SHADE("the notification shade"),
    QUICK_SETTINGS("Quick Settings"),
    LOCK_SCREEN("the lock screen"),
    RECENTS("the recent-apps screen"),

    /** A large System UI window we can't name (heads-up notification, system dialog). */
    SYSTEM_PANEL("a System UI panel"),
}

/**
 * Detects [SystemOverlay]s on captured windows (pure). Used to refuse screenshots for agents with app
 * restrictions: pixels can't be filtered per app the way node trees can.
 */
object SystemOverlays {
    /** Launcher3/Quickstep (AOSP, Pixel), System UI recents (older AOSP) and common OEM ids. */
    private val RECENTS_IDS = setOf("overview_panel", "recents_view", "recents_list", "recent_apps", "recents_container", "task_view")
    private val RECENTS_TITLES = setOf("recents", "recent apps", "overview")
    private val QS_ID_MARKERS = listOf("quick_settings", "qs_panel", "qs_frame", "quick_qs")
    private val SHADE_ID_MARKERS = listOf("notification_stack_scroller", "notification_panel", "notification_shade", "notification_container")

    /**
     * The overlay showing on [windows] (all windows, top first, with roots), or null. [locked] is the keyguard
     * state; [activity] the foreground activity class when known (recents is an activity on some launchers).
     * [display] is the full display: the windows alone may not cover it (an app window whose content Android
     * withholds isn't captured, which would make the status bar look like a full-screen panel).
     */
    fun detect(windows: List<WindowSnapshot>, locked: Boolean, activity: String? = null, display: Bounds = Bounds.EMPTY): SystemOverlay? {
        if (locked) return SystemOverlay.LOCK_SCREEN
        if (activity != null && activity.substringAfterLast('.').contains("Recents")) return SystemOverlay.RECENTS
        if (windows.any(::isRecents)) return SystemOverlay.RECENTS
        val screen = windows.fold(display) { acc, w -> acc.union(w.bounds) }
        val panel = windows.firstOrNull { w ->
            w.packageName == ForegroundTracker.SYSTEM_UI && !w.bounds.isEmpty && !WindowSelection.isSystemBar(WindowKind.SYSTEM, w.bounds, screen)
        } ?: return null
        // Window titles vary by version ("Notification shade", "NotificationShade", "Quick settings").
        val title = TextNorm.key(panel.title).replace(" ", "")
        val ids = panel.root?.walk()?.filter { it.visible }?.mapNotNull { it.shortId?.lowercase() }?.toList().orEmpty()
        val shadeTitle = title.contains("notification") || title.contains("shade")
        return when {
            title.contains("quicksettings") -> SystemOverlay.QUICK_SETTINGS
            shadeTitle || ids.any { id -> SHADE_ID_MARKERS.any(id::contains) } -> SystemOverlay.NOTIFICATION_SHADE
            ids.any { id -> QS_ID_MARKERS.any(id::startsWith) } -> SystemOverlay.QUICK_SETTINGS
            else -> SystemOverlay.SYSTEM_PANEL
        }
    }

    private fun isRecents(w: WindowSnapshot): Boolean {
        if (TextNorm.key(w.title) in RECENTS_TITLES) return true
        val root = w.root ?: return false
        return root.walk().any { n -> n.visible && n.shortId?.lowercase() in RECENTS_IDS }
    }
}
