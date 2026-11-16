package com.droidhands.android.tools.screen

import com.droidhands.android.access.SCREEN
import com.droidhands.android.access.UiNode
import com.droidhands.android.access.WindowKind
import com.droidhands.android.access.WindowSnapshot
import com.droidhands.android.access.appWindow
import com.droidhands.android.access.button
import com.droidhands.android.access.group
import com.droidhands.android.access.label
import com.droidhands.android.access.rect
import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarksTest {
    @Test
    fun `marks actionable elements in reading order`() {
        val send = button("Send", bounds = rect(900, 2200, 1060, 2300))
        val back = button(desc = "Back", bounds = rect(0, 100, 100, 200))
        val field = UiNode(className = "android.widget.EditText", bounds = rect(0, 2200, 880, 2300), editable = true)
        val tree = group(label("Hello"), send, field, back)
        val marks = ScreenMarks.select(listOf(appWindow(tree)), SCREEN, 10)
        assertEquals(listOf(back, field, send), marks.map { it.node })
        assertTrue(marks.all { it.drawn })
    }

    @Test
    fun `a clickable row and its clickable child with the same box get one mark`() {
        val inner = button("Dana", bounds = rect(0, 300, 1080, 450))
        val row = group(inner, clickable = true, bounds = rect(0, 300, 1080, 450))
        val marks = ScreenMarks.select(listOf(appWindow(group(row))), SCREEN, 10)
        assertEquals(listOf(row), marks.map { it.node })
    }

    @Test
    fun `full-screen containers are listed but not drawn, and off-screen parts are clipped`() {
        val list = group(button("Item", bounds = rect(0, 2300, 1080, 2600)), scrollable = true)
        val marks = ScreenMarks.select(listOf(appWindow(list)), SCREEN, 10)
        assertFalse(marks.first { it.node === list }.drawn)
        assertEquals(rect(0, 2300, 1080, 2400), marks.first { it.node !== list }.box)
    }

    @Test
    fun `invisible and off-screen elements are skipped, and the count is capped`() {
        val hidden = UiNode(text = "x", clickable = true, visible = false, bounds = rect(0, 0, 10, 10))
        val offscreen = button("far", bounds = rect(0, 3000, 100, 3100))
        val many = (0 until 20).map { button("b$it", bounds = rect(0, it * 100, 100, it * 100 + 90)) }
        val marks = ScreenMarks.select(listOf(appWindow(group(hidden, offscreen, *many.toTypedArray()))), SCREEN, 5)
        assertEquals(many.take(5), marks.map { it.node })
    }

    @Test
    fun `an app that exposes almost nothing has a thin tree`() {
        val canvas = UiNode(className = "android.view.SurfaceView", bounds = SCREEN)
        assertTrue(ScreenMarks.thinTree(listOf(appWindow(group(canvas)))))
        assertFalse(ScreenMarks.thinTree(listOf(appWindow(group(button("a"), button("b"), label("c"))))))
        // Only System UI on screen (no app window): not an app problem.
        assertFalse(ScreenMarks.thinTree(listOf(WindowSnapshot(null, WindowKind.SYSTEM, "com.android.systemui", SCREEN, true, group()))))
    }

    @Test
    fun `rows without their own text carry the texts inside them`() {
        val row = group(label("carol@example.com"), label("Offsite"), label("Are you joining?"), label("Offsite"), clickable = true)
        assertEquals("carol@example.com | Offsite | Are you joining?", ScreenMarks.innerText(row))
        assertNull(ScreenMarks.innerText(button("Send")))
        assertNull(ScreenMarks.innerText(group(clickable = true)))
    }

    @Test
    fun `tags are the ref numbers`() = assertEquals("12", ScreenMarks.tag("n12"))
}

class ScreenshotGeometryTest {
    private val shot = ScreenshotGeometry(imageWidth = 576, imageHeight = 1280, screenWidth = 1080, screenHeight = 2400)

    @Test
    fun `maps image pixels to screen pixels`() {
        assertEquals(0 to 0, shot.toScreen(0, 0))
        assertEquals(540 to 1200, shot.toScreen(288, 640))
        assertEquals(1078 to 2398, shot.toScreen(575, 1279))
    }

    @Test
    fun `points outside the image are refused`() {
        assertNull(shot.toScreen(576, 10))
        assertNull(shot.toScreen(-1, 10))
    }

    @Test
    fun `a rotated screen no longer matches`() {
        assertTrue(shot.matches(SCREEN))
        assertFalse(shot.matches(rect(0, 0, 2400, 1080)))
    }

    @Test
    fun `parses the point space`() {
        assertEquals(PointSpace.SCREEN, PointSpace.parse(null))
        assertEquals(PointSpace.SCREENSHOT, PointSpace.parse(" Screenshot "))
        assertThrows(ToolException::class.java) { PointSpace.parse("dp") }
    }
}

class ScreenChangeTest {
    private fun sig(
        pkg: String = "com.example",
        activity: String? = "com.example.Main",
        windows: List<String> = emptyList(),
        keyboard: Boolean = false,
        texts: List<String> = listOf("Inbox"),
        focused: String? = null,
    ) = ScreenSignature(pkg, activity, windows, keyboard, texts, focused)

    @Test
    fun `nothing changed says so, with what to check`() {
        val text = ScreenChange.describe(sig(), sig())
        assertTrue(text, text.startsWith("The screen did not visibly change"))
    }

    @Test
    fun `reports app, dialog, keyboard and text changes`() {
        val text = ScreenChange.describe(
            sig(texts = listOf("Inbox", "Draft")),
            sig(pkg = "com.other", windows = listOf("Allow access?"), keyboard = true, texts = listOf("Inbox", "Sent")),
        )
        assertEquals(
            "Screen changed: now in app com.other (was com.example); opened \"Allow access?\"; keyboard opened; " +
                "new text: \"Sent\"; gone: \"Draft\".",
            text,
        )
    }

    @Test
    fun `a new screen of the same app and a typed field are reported`() {
        val text = ScreenChange.describe(sig(), sig(activity = "com.example.Compose", focused = "hi"))
        assertEquals("Screen changed: new screen Compose; focused field shows \"hi\".", text)
    }

    @Test
    fun `long text lists are cut`() {
        val text = ScreenChange.describe(sig(texts = emptyList()), sig(texts = (1..9).map { "t$it" }))
        assertTrue(text, text.contains("(+3 more)"))
    }
}
