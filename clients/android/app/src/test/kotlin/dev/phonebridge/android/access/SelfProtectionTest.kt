package dev.phonebridge.android.access

import dev.phonebridge.android.tools.screen.NodeActions
import dev.phonebridge.android.tools.support.ApprovalGuard
import dev.phonebridge.core.tools.ToolException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfProtectionTest {
    private val own = "dev.phonebridge.android"
    private val sysui = ForegroundTracker.SYSTEM_UI

    @After
    fun resetGuard() = ApprovalGuard.set(SOURCE, false)

    // --- Approval pending: no gesture or node action at all (SEC-1 / PLAT-1) ---

    @Test
    fun `actions are refused while an approval is pending`() {
        assertEquals(ApprovalGuard.REFUSAL, assertThrows(ToolException::class.java) { SelfProtection.requireNoPendingApproval(true) }.message)
        SelfProtection.requireNoPendingApproval(false)

        ApprovalGuard.set(SOURCE, true)
        assertThrows(ToolException::class.java) { SelfProtection.requireNoPendingApproval() }
        // The node-action choke point refuses before touching the node, also for nodes it could act on.
        assertEquals(ApprovalGuard.REFUSAL, assertThrows(ToolException::class.java) { NodeActions.click(button("Allow")) }.message)
        assertThrows(ToolException::class.java) { NodeActions.longClick(button("Allow")) }

        ApprovalGuard.set(SOURCE, false)
        SelfProtection.requireNoPendingApproval()
        assertFalse(NodeActions.click(button("Allow"))) // No platform node behind it in tests: nothing to click.
    }

    // --- Fail-closed hit-testing (SEC-1 / PLAT-1) ---

    private data class W(val kind: WindowKind, val bounds: Bounds, val readable: Boolean, val name: String)

    private fun touched(windows: List<W>, x: Int, y: Int): W? =
        SelfProtection.touchedWindow(windows, x, y, { it.kind }, { it.bounds }, { it.readable })

    @Test
    fun `a touch on a window whose content is withheld is refused`() {
        // Android 14+: PhoneBridge's data-sensitive approval screen has no root for our service; only the bars are readable.
        val statusBar = W(WindowKind.SYSTEM, rect(0, 0, 1080, 80), readable = true, name = "status")
        val approval = W(WindowKind.APPLICATION, SCREEN, readable = false, name = "approval")
        val navBar = W(WindowKind.SYSTEM, rect(0, 2300, 1080, 2400), readable = true, name = "nav")
        val windows = listOf(statusBar, navBar, approval)
        val error = assertThrows(ToolException::class.java) { touched(windows, 810, 2200) }
        assertTrue(error.message!!, error.message!!.startsWith("Refusing to touch (810,2200): Android hides the content"))
        // The bars on top still work.
        assertEquals("status", touched(windows, 500, 40)?.name)
        assertEquals("nav", touched(windows, 500, 2350)?.name)
    }

    @Test
    fun `a recipe's gesture must land on its own app's window`() {
        val app = "com.chat"
        assertFalse(SelfProtection.coversConfinedApp(WindowKind.APPLICATION, app, app))
        assertTrue(SelfProtection.coversConfinedApp(WindowKind.INPUT_METHOD, "com.google.android.inputmethod.latin", app))
        assertTrue(SelfProtection.coversConfinedApp(WindowKind.SYSTEM, sysui, app)) // The shade or a heads-up.
        assertTrue(SelfProtection.coversConfinedApp(WindowKind.APPLICATION, "com.other", app))
        assertTrue(SelfProtection.coversConfinedApp(WindowKind.APPLICATION, null, app))
        assertTrue(SelfProtection.coversConfinedApp(null, null, app)) // No window there: fail closed.
        val e = SelfProtection.CoveredTouchException(540, 1200)
        assertTrue(e.javaClass != ToolException::class.java) // Not a plain step failure.
        assertEquals(
            "Stopped before touching (540,1200): another window (keyboard, notification or another app) covers the element. " +
                "Nothing was done there.",
            e.message,
        )
    }

    @Test
    fun `only the top-most non-overlay window at the point counts`() {
        val overlay = W(WindowKind.OVERLAY, SCREEN, readable = false, name = "overlay")
        val dialog = W(WindowKind.APPLICATION, rect(100, 800, 980, 1600), readable = true, name = "dialog")
        val hidden = W(WindowKind.APPLICATION, SCREEN, readable = false, name = "hidden")
        val windows = listOf(overlay, dialog, hidden)
        assertEquals("dialog", touched(windows, 500, 1000)?.name) // The unreadable window below is not touched.
        assertThrows(ToolException::class.java) { touched(windows, 500, 2000) }
        assertNull(touched(listOf(dialog), 10, 10))
        assertNull(touched(emptyList(), 10, 10))
    }

    // --- PhoneBridge notifications in System UI (SEC-1 / SEC-8) ---

    /** A shade with PhoneBridge's approval notification row and another app's row. */
    private class Shade(val own: String) {
        val title = label("Allow \"Bot\" to send an SMS?", pkg = own)
        val allow = button("Allow", pkg = own, bounds = rect(700, 500, 1000, 600))
        val ownContent = group(title, allow, pkg = own, bounds = rect(0, 300, 1080, 620))
        val padding = UiNode(className = "android.view.View", packageName = ForegroundTracker.SYSTEM_UI, bounds = rect(0, 300, 40, 620))
        val ownRow = UiNode(
            className = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            packageName = ForegroundTracker.SYSTEM_UI, bounds = rect(0, 300, 1080, 620), clickable = true, longClickable = true,
            children = listOf(
                UiNode(className = "android.widget.FrameLayout", packageName = ForegroundTracker.SYSTEM_UI, bounds = rect(0, 300, 1080, 620), children = listOf(ownContent)),
                padding,
            ),
        )
        val chat = label("Dana: see you at 8", pkg = "com.whatsapp")
        val otherRow = UiNode(
            className = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
            packageName = ForegroundTracker.SYSTEM_UI, bounds = rect(0, 640, 1080, 900), clickable = true, children = listOf(chat),
        )
        val clearAll = button("Clear all", pkg = ForegroundTracker.SYSTEM_UI, bounds = rect(800, 1000, 1000, 1080))
        val stack = group(ownRow, otherRow, clearAll, pkg = ForegroundTracker.SYSTEM_UI, scrollable = true)
    }

    @Test
    fun `clicks reaching a PhoneBridge notification row are refused`() {
        val shade = Shade(own)
        assertTrue(SelfProtection.containsOwnContent(shade.ownRow, own))
        assertFalse(SelfProtection.containsOwnContent(shade.otherRow, own))
        // A tap on the row's own System UI padding still opens the notification: refused.
        assertTrue(SelfProtection.touchReachesOwnContent(shade.padding, own))
        assertTrue(SelfProtection.touchReachesOwnContent(shade.ownRow, own))
        assertTrue(SelfProtection.touchReachesOwnContent(shade.allow, own))
        assertFalse(SelfProtection.touchReachesOwnContent(shade.chat, own))
        assertFalse(SelfProtection.touchReachesOwnContent(shade.clearAll, own))
    }

    @Test
    fun `PhoneBridge notifications are hidden with the row hosting them`() {
        val shade = Shade(own)
        val kept = SelfProtection.hideOwnContent(shade.stack, own)!!
        val texts = kept.walk().mapNotNull { it.text }.toList()
        assertEquals(listOf("Dana: see you at 8", "Clear all"), texts)
        assertTrue(kept.walk().none { it.packageName == own })
        assertEquals(2, kept.children.size) // The row itself is gone, so no ref can open it.
        assertSame(shade.otherRow.key, kept.children[0].key)
    }

    @Test
    fun `hiding leaves other trees untouched and drops windows that only show PhoneBridge content`() {
        val shade = Shade(own)
        assertSame(shade.otherRow, SelfProtection.hideOwnContent(shade.otherRow, own))
        // A heads-up window whose root is our row: nothing is left.
        assertNull(SelfProtection.hideOwnContent(Shade(own).ownRow, own))
        // Own content without a clickable host nearby: only the own subtree is removed.
        val widget = group(label("Status", pkg = own), label("Weather 21°", pkg = "com.weather"), pkg = "com.launcher")
        val kept = SelfProtection.hideOwnContent(widget, own)!!
        assertEquals(listOf("Weather 21°"), kept.walk().mapNotNull { it.text }.toList())
        // A clickable ancestor too far away (e.g. a whole panel) is not removed.
        var deep: UiNode = label("Pending", pkg = own)
        repeat(NodeQueries.MAX_CLICK_ANCESTORS + 1) { deep = group(deep, pkg = sysui) }
        val panel = group(deep, label("Wi-Fi", pkg = sysui), clickable = true, pkg = sysui)
        val keptPanel = SelfProtection.hideOwnContent(panel, own)!!
        assertEquals(listOf("Wi-Fi"), keptPanel.walk().mapNotNull { it.text }.toList())
    }

    private companion object {
        const val SOURCE = "test"
    }
}
