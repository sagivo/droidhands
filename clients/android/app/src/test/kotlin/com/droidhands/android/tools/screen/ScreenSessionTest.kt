package com.droidhands.android.tools.screen

import com.droidhands.android.access.UiNode
import com.droidhands.core.tools.ToolException
import com.droidhands.core.util.OneTimeCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSessionTest {
    @Test
    fun `each agent has its own refs and ids are unique across agents`() {
        val a = ScreenSession.refs("token-a-${System.nanoTime()}")
        val b = ScreenSession.refs("token-b-${System.nanoTime()}")
        assertNotSame(a, b)
        val refA = a.register(UiNode(text = "Tania Cohen", handle = "row"))
        val refB = b.register(UiNode(text = "Delete chat", handle = "delete"))
        assertNotEquals(refA, refB)
        assertThrows(ToolException::class.java) { b.lookup(refA) }
        assertThrows(ToolException::class.java) { a.lookup(refB) }
    }

    @Test
    fun `the same agent keeps its registry`() {
        val token = "token-${System.nanoTime()}"
        assertSame(ScreenSession.refs(token), ScreenSession.refs(token))
    }

    @Test
    fun `capture notes tell the agent what was left out or masked`() {
        val state = ScreenState(emptyList(), "com.android.systemui", null, locked = false, screenOn = true, omittedWindows = 1, ownContentHidden = true, codesHidden = true)
        assertEquals(3, state.notes.size)
        assertTrue(state.notes[0].contains("1 window(s)"))
        assertEquals(ScreenState.OWN_CONTENT_NOTE, state.notes[1])
        assertTrue(state.notes[2].contains(OneTimeCodes.MASK))
        assertTrue(ScreenState(emptyList(), null, null, locked = false, screenOn = true).notes.isEmpty())
    }
}
