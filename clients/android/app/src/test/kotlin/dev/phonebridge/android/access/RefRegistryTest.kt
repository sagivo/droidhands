package dev.phonebridge.android.access

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RefRegistryTest {
    @Test
    fun `parses lenient ref forms`() {
        assertEquals(12, RefRegistry.parseRef("n12"))
        assertEquals(3, RefRegistry.parseRef("N3"))
        assertEquals(7, RefRegistry.parseRef("7"))
        assertEquals(4, RefRegistry.parseRef("[n4]"))
        assertEquals(5, RefRegistry.parseRef(" n5 "))
        assertNull(RefRegistry.parseRef("x1"))
        assertNull(RefRegistry.parseRef("n0"))
        assertNull(RefRegistry.parseRef(""))
        assertNull(RefRegistry.parseRef("n1.5"))
    }

    @Test
    fun `registers in order, reuses refs for the same platform node, resets per snapshot`() {
        val refs = RefRegistry()
        val a = button("A")
        val b = button("B")
        refs.newSnapshot { register ->
            assertEquals("n1", register(a))
            assertEquals("n2", register(b))
            assertEquals("n1", register(a))
        }
        // Same handle (equal AccessibilityNodeInfo) captured again: same ref, newer node.
        val h1 = UiNode(text = "X", handle = "node-1")
        val h1Again = UiNode(text = "X", handle = "node-1")
        assertEquals("n3", refs.register(h1))
        assertEquals("n3", refs.register(h1Again))
        assertSame(h1Again, refs.lookup("n3").node)
        refs.newSnapshot { register -> assertEquals("n1", register(b)) }
        assertEquals(1, refs.size)
    }

    @Test
    fun `lookup errors guide the agent`() {
        val refs = RefRegistry()
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("n1") }.message!!.contains("screen_read"))
        refs.register(button("A"))
        refs.register(button("B"))
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("n9") }.message!!.contains("n1..n2"))
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("send") }.message!!.contains("not a valid element ref"))
    }

    @Test
    fun `fingerprint ignores editable text but catches changed content`() {
        val field = UiNode(text = "draft", viewId = "app:id/entry", className = "android.widget.EditText", editable = true)
        val typed = UiNode(text = "draft and more", viewId = "app:id/entry", className = "android.widget.EditText", editable = true)
        assertTrue(NodeFingerprint.of(field).matches(NodeFingerprint.of(typed)))
        val row = label("Alice", id = "app:id/name")
        val recycled = label("Bob", id = "app:id/name")
        assertFalse(NodeFingerprint.of(row).matches(NodeFingerprint.of(recycled)))
        assertTrue(NodeFingerprint.of(row).matches(NodeFingerprint.of(label("  alice ", id = "app:id/name"))))
    }
}
