package dev.phonebridge.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAndProtectionTest {
    private fun facts(kind: WindowKind, active: Boolean = false, focused: Boolean = false) = WindowFacts(kind, active, focused, SCREEN)

    @Test
    fun `primary window prefers the active application window`() {
        assertEquals(2, WindowSelection.primaryIndex(listOf(facts(WindowKind.SYSTEM, active = true), facts(WindowKind.APPLICATION), facts(WindowKind.APPLICATION, active = true))))
        assertEquals(1, WindowSelection.primaryIndex(listOf(facts(WindowKind.SYSTEM), facts(WindowKind.APPLICATION, focused = true))))
        assertEquals(1, WindowSelection.primaryIndex(listOf(facts(WindowKind.INPUT_METHOD), facts(WindowKind.APPLICATION))))
        assertEquals(1, WindowSelection.primaryIndex(listOf(facts(WindowKind.INPUT_METHOD), facts(WindowKind.SYSTEM, active = true))))
        assertEquals(0, WindowSelection.primaryIndex(listOf(facts(WindowKind.SYSTEM), facts(WindowKind.SYSTEM))))
        assertEquals(-1, WindowSelection.primaryIndex(emptyList()))
    }

    @Test
    fun `reading drops keyboards, bars and overlays unless include_all`() {
        val app = appWindow(group(), pkg = "com.whatsapp")
        val statusBar = WindowSnapshot("Status bar", WindowKind.SYSTEM, "com.android.systemui", rect(0, 0, 1080, 80), false, group())
        val shade = WindowSnapshot("Notification shade", WindowKind.SYSTEM, "com.android.systemui", SCREEN, false, group())
        val ime = WindowSnapshot(null, WindowKind.INPUT_METHOD, "com.google.android.inputmethod.latin", rect(0, 1500, 1080, 2400), false, group())
        val overlay = WindowSnapshot(null, WindowKind.OVERLAY, "other.a11y", SCREEN, false, group())
        val all = listOf(app, statusBar, shade, ime, overlay)
        assertEquals(listOf(app, shade), WindowSelection.forReading(all, includeAll = false))
        assertEquals(listOf(app, statusBar, shade, ime), WindowSelection.forReading(all, includeAll = true))
        assertTrue(WindowSelection.isSystemBar(WindowKind.SYSTEM, rect(0, 2300, 1080, 2400), SCREEN))
        assertTrue(WindowSelection.isSystemBar(WindowKind.SYSTEM, rect(2300, 0, 2400, 1080), rect(0, 0, 2400, 1080)))
        assertFalse(WindowSelection.isSystemBar(WindowKind.APPLICATION, rect(0, 0, 1080, 80), SCREEN))
    }

    @Test
    fun `approval-like buttons of our notifications are protected only in system ui`() {
        val labels = setOf("Allow", "Deny")
        val allow = button("ALLOW", pkg = ForegroundTracker.SYSTEM_UI)
        assertTrue(SelfProtection.isProtectedSystemUiNode(allow, labels))
        val inner = label("x", pkg = ForegroundTracker.SYSTEM_UI)
        UiNode(text = "Allow", packageName = ForegroundTracker.SYSTEM_UI, clickable = true, children = listOf(inner))
        assertTrue(SelfProtection.isProtectedSystemUiNode(inner, labels))
        assertFalse(SelfProtection.isProtectedSystemUiNode(button("Allow", pkg = "com.whatsapp"), labels))
        assertFalse(SelfProtection.isProtectedSystemUiNode(button("Reply", pkg = ForegroundTracker.SYSTEM_UI), labels))
        assertFalse(SelfProtection.isProtectedSystemUiNode(allow, emptySet()))
        assertTrue(SelfProtection.isOwnPackage("dev.phonebridge.android", "dev.phonebridge.android"))
        assertFalse(SelfProtection.isOwnPackage(null, "dev.phonebridge.android"))
    }

    @Test
    fun `text normalization`() {
        assertEquals("tania cohen", TextNorm.key("‏‪Tania  \n Cohen‬ "))
        assertEquals("a\nb", TextNorm.clean("a\r\nb"))
        assertEquals("972541234567", TextNorm.digits("+972 54-123-4567"))
        assertEquals("ab…", TextNorm.truncate("abcdef", 2))
        assertEquals("a…", TextNorm.truncate("a😀b", 2)) // never splits the emoji's surrogate pair
        assertEquals(MatchLevel.EXACT, matchLevel("send", "send"))
        assertEquals(MatchLevel.NONE, matchLevel("", "send"))
    }

    @Test
    fun `bounds math`() {
        val b = rect(10, 20, 110, 220)
        assertEquals(100, b.width)
        assertEquals(60, b.centerX)
        assertEquals(120, b.centerY)
        assertEquals("(10,20,100,200)", b.compact())
        assertEquals(Bounds.EMPTY, b.intersect(rect(500, 500, 600, 600)))
        assertEquals(rect(50, 50, 110, 220), b.intersect(rect(50, 50, 1000, 1000)))
        assertEquals(rect(0, 0, 110, 220), b.union(rect(0, 0, 5, 5)))
        assertTrue(b.contains(10, 20))
        assertFalse(b.contains(110, 20))
    }
}
