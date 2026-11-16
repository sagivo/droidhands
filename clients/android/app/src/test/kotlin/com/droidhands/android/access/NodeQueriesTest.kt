package com.droidhands.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeQueriesTest {
    @Test
    fun `exact beats prefix beats contains`() {
        val send = button("Send")
        val later = button("Send later")
        val result = NodeQueries.resolveTapText(listOf(group(later, send)), "send")
        assertTrue(result is TextTarget.Found)
        assertSame(send, (result as TextTarget.Found).node)
        assertEquals(MatchLevel.EXACT, result.level)

        val settings = button("Settings")
        val app = button("App settings")
        val prefix = NodeQueries.resolveTapText(listOf(group(app, settings)), "sett") as TextTarget.Found
        assertSame(settings, prefix.node)
        assertEquals(MatchLevel.PREFIX, prefix.level)
    }

    @Test
    fun `content description matches and case and bidi marks are ignored`() {
        val icon = UiNode(description = "‪Tania Cohen‬", className = "android.widget.ImageView", clickable = true)
        val result = NodeQueries.resolveTapText(listOf(group(icon)), "  TANIA   cohen ")
        assertSame(icon, (result as TextTarget.Found).node)
    }

    @Test
    fun `clickable matches win over plain labels`() {
        val title = label("Chats")
        val tab = button("Chats")
        val result = NodeQueries.resolveTapText(listOf(group(title, tab)), "chats")
        assertSame(tab, (result as TextTarget.Found).node)
    }

    @Test
    fun `matches that click the same container count once`() {
        val name = label("Tania")
        val avatar = UiNode(description = "Tania", className = "android.widget.ImageView")
        val row = group(avatar, name, clickable = true)
        val result = NodeQueries.resolveTapText(listOf(group(row)), "tania")
        assertTrue(result is TextTarget.Found)
        assertSame(row, NodeQueries.clickTarget((result as TextTarget.Found).node))
    }

    @Test
    fun `equally good distinct targets are ambiguous`() {
        val a = button("OK")
        val b = button("ok")
        val result = NodeQueries.resolveTapText(listOf(group(a, b)), "OK")
        assertTrue(result is TextTarget.Ambiguous)
        assertEquals(listOf(a, b), (result as TextTarget.Ambiguous).candidates)
    }

    @Test
    fun `invisible and missing text is not found`() {
        val gone = UiNode(text = "Delete", clickable = true, visible = false)
        assertEquals(TextTarget.NotFound, NodeQueries.resolveTapText(listOf(group(gone)), "delete"))
        assertEquals(TextTarget.NotFound, NodeQueries.resolveTapText(listOf(group(button("Send"))), "archive"))
        assertEquals(TextTarget.NotFound, NodeQueries.resolveTapText(listOf(group(button("Send"))), "   "))
    }

    @Test
    fun `click target climbs to an enabled clickable ancestor`() {
        val text = label("Row title")
        val disabledInner = group(text, clickable = true).let { UiNode(clickable = true, enabled = false, children = listOf(it)) }
        assertSame(disabledInner.children[0], NodeQueries.clickTarget(text))
        assertNull(NodeQueries.clickTarget(label("alone")))
    }

    @Test
    fun `find combines criteria`() {
        val send = button(desc = "Send", id = "com.whatsapp:id/send")
        val entry = UiNode(text = "Hello there", viewId = "com.whatsapp:id/entry", editable = true)
        val inRow = label("Hello inside row")
        val row = group(inRow, clickable = true)
        val roots = listOf(group(send, entry, row, label("hello plain")))
        assertEquals(listOf(send), NodeQueries.find(roots, FindCriteria(viewId = "send")))
        assertEquals(listOf(send), NodeQueries.find(roots, FindCriteria(viewId = "com.whatsapp:id/send")))
        assertEquals(listOf(send), NodeQueries.find(roots, FindCriteria(viewId = "SEND")))
        assertEquals(listOf(send), NodeQueries.find(roots, FindCriteria(description = "sen")))
        assertEquals(3, NodeQueries.find(roots, FindCriteria(text = "hello")).size)
        assertEquals(listOf(inRow), NodeQueries.find(roots, FindCriteria(text = "hello", clickableOnly = true)))
        assertTrue(NodeQueries.find(roots, FindCriteria(text = "hello", viewId = "send")).isEmpty())
        assertTrue(FindCriteria().isEmpty)
        assertTrue(!FindCriteria(clickableOnly = true).isEmpty)
    }

    @Test
    fun `choose editable prefers focus, then the only field`() {
        val a = UiNode(editable = true, viewId = "a")
        val b = UiNode(editable = true, viewId = "b", focused = true)
        assertSame(b, (NodeQueries.chooseEditable(null, listOf(group(a, b))) as EditableChoice.Found).node)
        val focused = UiNode(editable = true, viewId = "f")
        assertSame(focused, (NodeQueries.chooseEditable(focused, listOf(group(a))) as EditableChoice.Found).node)
        assertSame(a, (NodeQueries.chooseEditable(null, listOf(group(a, label("x")))) as EditableChoice.Found).node)
        val c = UiNode(editable = true, viewId = "c")
        assertEquals(2, (NodeQueries.chooseEditable(null, listOf(group(a, c))) as EditableChoice.Ambiguous).candidates.size)
        assertEquals(EditableChoice.None, NodeQueries.chooseEditable(null, listOf(group(label("x")))))
        assertSame(a, NodeQueries.editableWithin(group(label("x"), a)))
    }

    @Test
    fun `largest scrollable and point hit testing`() {
        val small = UiNode(scrollable = true, bounds = rect(0, 0, 100, 100))
        val big = UiNode(actions = setOf(UiAction.SCROLL_FORWARD), bounds = rect(0, 200, 1080, 2000))
        val hidden = UiNode(scrollable = true, visible = false, bounds = SCREEN)
        assertSame(big, NodeQueries.largestScrollable(listOf(group(small, big, hidden))))
        assertNull(NodeQueries.largestScrollable(listOf(group(label("x")))))

        val inner = button("Inner", bounds = rect(10, 10, 50, 50))
        val outer = group(inner, bounds = rect(0, 0, 100, 100))
        assertSame(inner, NodeQueries.deepestAt(outer, 20, 20))
        assertSame(outer, NodeQueries.deepestAt(outer, 80, 80))
        assertNull(NodeQueries.deepestAt(outer, 500, 500))
    }

    @Test
    fun `top texts are distinct and truncated`() {
        val roots = listOf(group(label("Hello"), label("Hello"), button(desc = "Menu"), label("y".repeat(100))))
        val texts = NodeQueries.topTexts(roots, limit = 8, maxLength = 10)
        assertEquals(listOf("Hello", "Menu", "yyyyyyyyyy…"), texts)
    }
}
