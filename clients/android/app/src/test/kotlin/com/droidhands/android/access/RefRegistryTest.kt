package com.droidhands.android.access

import com.droidhands.core.tools.ToolException
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
        assertEquals(123_456_789, RefRegistry.parseRef("n123456789")) // Ids keep growing; long refs must parse.
    }

    @Test
    fun `registers in order, reuses refs for the same platform node, never reuses ids across snapshots`() {
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
        // A new snapshot gets new numbers: an old ref can never silently point at another element.
        refs.newSnapshot { register -> assertEquals("n4", register(b)) }
        assertEquals(1, refs.size)
        val stale = assertThrows(ToolException::class.java) { refs.lookup("n1") }.message!!
        assertTrue(stale, stale.contains("not from your latest screen_observe/screen_read") && stale.contains("Call screen_observe again"))
        assertSame(b, refs.lookup("n4").node)
    }

    @Test
    fun `agents sharing an id source never see each other's refs`() {
        val ids = RefIds()
        val agentA = RefRegistry(ids)
        val agentB = RefRegistry(ids)
        val row = label("Tania Cohen")
        val delete = button("Delete chat")
        val refA = agentA.newSnapshot { register -> register(row) }
        val refB = agentB.newSnapshot { register -> register(delete) }
        assertEquals("n1", refA)
        assertEquals("n2", refB)
        // A's ref only resolves for A; B's ref (or A's in B's registry) is rejected, never mapped to another element.
        assertSame(row, agentA.lookup(refA).node)
        val foreign = assertThrows(ToolException::class.java) { agentA.lookup(refB) }.message!!
        assertTrue(foreign, foreign.contains("only work for the agent that read them"))
        assertThrows(ToolException::class.java) { agentB.lookup(refA) }
        // B reading again doesn't disturb A's refs.
        agentB.newSnapshot { register -> register(button("Block")) }
        assertSame(row, agentA.lookup(refA).node)
    }

    @Test
    fun `lookup errors guide the agent`() {
        val refs = RefRegistry()
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("n1") }.message!!.contains("screen_observe"))
        refs.register(button("A"))
        refs.register(button("B"))
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("n9") }.message!!.contains("not from your latest screen_observe"))
        assertTrue(assertThrows(ToolException::class.java) { refs.lookup("send") }.message!!.contains("not a valid element ref"))
        // A read that listed nothing still counts as the latest snapshot.
        val empty = RefRegistry()
        empty.newSnapshot { }
        assertTrue(assertThrows(ToolException::class.java) { empty.lookup("n1") }.message!!.contains("not from your latest"))
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
