package dev.phonebridge.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemOverlaysTest {
    private val systemUi = ForegroundTracker.SYSTEM_UI
    private val launcher = "com.android.launcher3"

    private fun systemWindow(title: String?, bounds: Bounds = SCREEN, vararg children: UiNode) =
        WindowSnapshot(title, WindowKind.SYSTEM, systemUi, bounds, false, group(*children, bounds = bounds, pkg = systemUi))

    private val statusBar = systemWindow("Status bar", rect(0, 0, 1080, 80))
    private val navBar = systemWindow("Navigation bar", rect(0, 2300, 1080, 2400))
    private val app = appWindow(group(label("Inbox", pkg = "com.mail"), pkg = "com.mail"), pkg = "com.mail")

    @Test
    fun `ordinary app with bars is not an overlay`() {
        assertNull(SystemOverlays.detect(listOf(app, statusBar, navBar), locked = false))
    }

    @Test
    fun `lock screen whenever locked`() {
        assertEquals(SystemOverlay.LOCK_SCREEN, SystemOverlays.detect(listOf(app), locked = true))
    }

    @Test
    fun `notification shade by title or by its ids`() {
        assertEquals(SystemOverlay.NOTIFICATION_SHADE, SystemOverlays.detect(listOf(systemWindow("Notification shade"), app), locked = false))
        assertEquals(SystemOverlay.NOTIFICATION_SHADE, SystemOverlays.detect(listOf(systemWindow("NotificationShade"), app), locked = false))
        val stack = UiNode(viewId = "$systemUi:id/notification_stack_scroller", packageName = systemUi, bounds = SCREEN)
        val qsHeader = UiNode(viewId = "$systemUi:id/quick_qs_panel", packageName = systemUi, bounds = rect(0, 0, 1080, 300))
        assertEquals(SystemOverlay.NOTIFICATION_SHADE, SystemOverlays.detect(listOf(systemWindow(null, SCREEN, qsHeader, stack)), locked = false))
    }

    @Test
    fun `quick settings by title or panel id`() {
        assertEquals(SystemOverlay.QUICK_SETTINGS, SystemOverlays.detect(listOf(systemWindow("Quick settings")), locked = false))
        val qs = UiNode(viewId = "$systemUi:id/quick_settings_panel", packageName = systemUi, bounds = SCREEN)
        assertEquals(SystemOverlay.QUICK_SETTINGS, SystemOverlays.detect(listOf(systemWindow(null, SCREEN, qs)), locked = false))
    }

    @Test
    fun `other large system ui windows are a generic panel`() {
        assertEquals(SystemOverlay.SYSTEM_PANEL, SystemOverlays.detect(listOf(systemWindow(null, rect(0, 600, 1080, 1800)), app), locked = false))
    }

    @Test
    fun `recents from the launcher's overview panel, its title or the activity`() {
        val overview = UiNode(viewId = "$launcher:id/overview_panel", packageName = launcher, bounds = SCREEN)
        val recents = appWindow(group(overview, pkg = launcher), pkg = launcher)
        assertEquals(SystemOverlay.RECENTS, SystemOverlays.detect(listOf(recents, statusBar), locked = false))
        val hidden = UiNode(viewId = "$launcher:id/overview_panel", packageName = launcher, bounds = SCREEN, visible = false)
        assertNull(SystemOverlays.detect(listOf(appWindow(group(hidden, pkg = launcher), pkg = launcher)), locked = false))
        assertEquals(SystemOverlay.RECENTS, SystemOverlays.detect(listOf(appWindow(group(), pkg = launcher, title = "Recents")), locked = false))
        assertEquals(SystemOverlay.RECENTS, SystemOverlays.detect(listOf(app), locked = false, activity = "com.android.quickstep.RecentsActivity"))
        assertNull(SystemOverlays.detect(listOf(app), locked = false, activity = "com.mail.InboxActivity"))
    }
}
