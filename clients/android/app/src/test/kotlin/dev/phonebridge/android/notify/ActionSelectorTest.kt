package dev.phonebridge.android.notify

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSelectorTest {
    private fun a(index: Int, title: String, canReply: Boolean = false, semantic: Boolean = false) =
        NotificationActionInfo(index, title, canReply, canReply, semantic)

    private val whatsapp = listOf(a(0, "Reply", canReply = true, semantic = true), a(1, "Mark as read"), a(2, "Mute"))

    @Test
    fun `reply action prefers semantic reply`() {
        val actions = listOf(a(0, "Quick", canReply = true), a(1, "Reply", canReply = true, semantic = true))
        assertEquals(1, ActionSelector.replyAction(actions)?.index)
    }

    @Test
    fun `reply action falls back to first free-form input`() {
        val actions = listOf(a(0, "Archive"), a(1, "Respond", canReply = true), a(2, "Answer", canReply = true))
        assertEquals(1, ActionSelector.replyAction(actions)?.index)
    }

    @Test
    fun `semantic reply without free-form input is not a reply action`() {
        assertNull(ActionSelector.replyAction(listOf(a(0, "Reply", canReply = false, semantic = true))))
        assertNull(ActionSelector.replyAction(emptyList()))
    }

    @Test
    fun `select by exact title is case-insensitive`() {
        assertEquals(1, ActionSelector.select(whatsapp, "MARK AS READ", null).index)
        assertEquals(2, ActionSelector.select(whatsapp, "  mute ", null).index)
    }

    @Test
    fun `exact title wins over contains`() {
        val actions = listOf(a(0, "Read more"), a(1, "Read"))
        assertEquals(1, ActionSelector.select(actions, "read", null).index)
    }

    @Test
    fun `unique contains match is accepted`() {
        assertEquals(1, ActionSelector.select(whatsapp, "read", null).index)
    }

    @Test
    fun `ambiguous contains match lists candidates`() {
        val actions = listOf(a(0, "Mark as read"), a(1, "Mark as spam"))
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(actions, "mark", null) }
        assertTrue(e.message!!.contains("several actions"))
        assertTrue(e.message!!.contains("0 \"Mark as read\""))
        assertTrue(e.message!!.contains("1 \"Mark as spam\""))
    }

    @Test
    fun `contains matches with identical titles are not ambiguous`() {
        val actions = listOf(a(0, "Reply"), a(1, "Reply", canReply = true))
        assertEquals(0, ActionSelector.select(actions, "rep", null).index)
    }

    @Test
    fun `select by index`() {
        assertEquals(2, ActionSelector.select(whatsapp, null, 2).index)
    }

    @Test
    fun `unknown index lists available actions`() {
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(whatsapp, null, 7) }
        assertTrue(e.message!!.contains("No action with index 7"))
        assertTrue(e.message!!.contains("0 \"Reply\" (reply), 1 \"Mark as read\", 2 \"Mute\""))
    }

    @Test
    fun `unknown title lists available actions`() {
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(whatsapp, "Delete", null) }
        assertTrue(e.message!!.contains("No action titled \"Delete\""))
        assertTrue(e.message!!.contains("Available actions"))
    }

    @Test
    fun `title and index must agree`() {
        assertEquals(1, ActionSelector.select(whatsapp, "Mark as read", 1).index)
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(whatsapp, "Mute", 1) }
        assertTrue(e.message!!.contains("different actions"))
    }

    @Test
    fun `missing title and index is an error`() {
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(whatsapp, "  ", null) }
        assertTrue(e.message!!.contains("Pass 'action'"))
    }

    @Test
    fun `notification without actions says so`() {
        val e = assertThrows(ToolException::class.java) { ActionSelector.select(emptyList(), "Reply", null) }
        assertEquals(ActionSelector.NO_ACTIONS, e.message)
    }
}
