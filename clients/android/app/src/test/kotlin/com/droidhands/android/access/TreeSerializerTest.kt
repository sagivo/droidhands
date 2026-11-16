package com.droidhands.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSerializerTest {
    private fun chatRoot(): UiNode {
        val entry = UiNode(
            className = "android.widget.EditText", hint = "Message", viewId = "com.whatsapp:id/entry",
            editable = true, clickable = true, focused = true, bounds = rect(100, 2200, 900, 2320),
        )
        val send = UiNode(
            className = "android.widget.ImageButton", description = "Send", viewId = "com.whatsapp:id/send",
            clickable = true, bounds = rect(948, 2200, 1068, 2320),
        )
        val bar = UiNode(className = "android.widget.LinearLayout", bounds = rect(0, 2180, 1080, 2340), children = listOf(entry, send))
        return UiNode(className = "android.widget.FrameLayout", viewId = "android:id/content", bounds = SCREEN, children = listOf(bar))
    }

    private fun serialize(windows: List<WindowSnapshot>, options: SerializeOptions = SerializeOptions()): TreeSerializer.Output {
        val refs = RefRegistry()
        return refs.newSnapshot { register -> TreeSerializer.serialize(windows, options, register) }
    }

    @Test
    fun `default output hoists non-informative containers`() {
        val out = serialize(listOf(appWindow(chatRoot())))
        assertEquals(
            """
            n1 FrameLayout id=content (0,0,1080,2400)
              n2 EditText hint="Message" id=entry [clickable,editable,focused] (100,2200,800,120)
              n3 ImageButton desc="Send" id=send [clickable] (948,2200,120,120)
            """.trimIndent(),
            out.text,
        )
        assertEquals(3, out.printed)
        assertEquals(0, out.truncated)
    }

    @Test
    fun `include_all keeps layout containers`() {
        val out = serialize(listOf(appWindow(chatRoot())), SerializeOptions(includeAll = true))
        assertEquals(
            """
            n1 FrameLayout id=content (0,0,1080,2400)
              n2 LinearLayout (0,2180,1080,160)
                n3 EditText hint="Message" id=entry [clickable,editable,focused] (100,2200,800,120)
                n4 ImageButton desc="Send" id=send [clickable] (948,2200,120,120)
            """.trimIndent(),
            out.text,
        )
    }

    @Test
    fun `refs follow output order and map back to nodes`() {
        val root = chatRoot()
        val refs = RefRegistry()
        refs.newSnapshot { register -> TreeSerializer.serialize(listOf(appWindow(root)), SerializeOptions(), register) }
        assertEquals("send", refs.lookup("n3").node.shortId)
        assertEquals("entry", refs.lookup("n2").node.shortId)
        assertEquals(3, refs.size)
    }

    @Test
    fun `max_nodes truncates with a summary line`() {
        val root = group(*Array(5) { button("B$it") }, bounds = SCREEN).let {
            UiNode(className = "android.widget.FrameLayout", viewId = "android:id/content", bounds = SCREEN, children = listOf(it))
        }
        val out = serialize(listOf(appWindow(root)), SerializeOptions(maxNodes = 3))
        val lines = out.text.lines()
        assertEquals(4, lines.size)
        assertEquals("... truncated 3 nodes (raise max_nodes or use screen_find)", lines.last())
        assertEquals(3, out.printed)
        assertEquals(3, out.truncated)
    }

    @Test
    fun `long text is truncated and special characters escaped`() {
        val long = "x".repeat(250)
        val line = TreeSerializer.formatNode(label(long), "n1")
        assertTrue(line.contains("\"" + "x".repeat(200) + "…\""))
        val quoted = TreeSerializer.formatNode(label("He said \"hi\"\nbye\\"), "n2")
        assertEquals("n2 TextView \"He said \\\"hi\\\"\\nbye\\\\\" (0,0,100,50)", quoted)
    }

    @Test
    fun `description identical to text is shown once, different one is kept`() {
        assertEquals("n1 Button \"OK\" [clickable] (0,0,100,100)", TreeSerializer.formatNode(button("OK", desc = "OK"), "n1"))
        assertEquals("n1 Button \"OK\" desc=\"Confirm\" [clickable] (0,0,100,100)", TreeSerializer.formatNode(button("OK", desc = "Confirm"), "n1"))
    }

    @Test
    fun `state flags are listed in order`() {
        val n = UiNode(
            text = "Wi-Fi", className = "android.widget.Switch", stateDescription = "On", clickable = true, longClickable = true,
            checkable = true, checked = true, selected = true, enabled = false, bounds = rect(0, 0, 10, 10),
        )
        assertEquals(
            "n7 Switch \"Wi-Fi\" state=\"On\" [clickable,long-clickable,checked,selected,disabled] (0,0,10,10)",
            TreeSerializer.formatNode(n, "n7"),
        )
        val pwd = UiNode(className = "android.widget.EditText", editable = true, password = true, hint = "Password", bounds = rect(0, 0, 10, 10))
        assertEquals("n1 EditText hint=\"Password\" [editable,password] (0,0,10,10)", TreeSerializer.formatNode(pwd, "n1"))
        val list = UiNode(className = "androidx.recyclerview.widget.RecyclerView", actions = setOf(UiAction.SCROLL_FORWARD), bounds = rect(0, 0, 10, 10))
        assertEquals("n1 RecyclerView [scrollable] (0,0,10,10)", TreeSerializer.formatNode(list, "n1"))
        val box = UiNode(className = "android.widget.CheckBox", text = "Remember", checkable = true, bounds = rect(0, 0, 10, 10))
        assertEquals("n1 CheckBox \"Remember\" [unchecked] (0,0,10,10)", TreeSerializer.formatNode(box, "n1"))
    }

    @Test
    fun `invisible nodes are skipped but their visible children are kept`() {
        val hidden = UiNode(text = "Hidden", className = "android.widget.TextView", visible = false, children = listOf(label("Shown")))
        val out = serialize(listOf(appWindow(group(hidden))))
        assertFalse(out.text.contains("Hidden"))
        assertTrue(out.text.contains("\"Shown\""))
    }

    @Test
    fun `secondary windows get a header and own windows are hidden`() {
        val dialog = WindowSnapshot(
            "Allow access?", WindowKind.APPLICATION, "com.google.android.permissioncontroller", rect(100, 900, 980, 1500),
            primary = false, root = group(button("Allow"), bounds = rect(100, 900, 980, 1500)),
        )
        val own = WindowSnapshot("DroidHands", WindowKind.APPLICATION, "com.droidhands.android", SCREEN, primary = false, root = null, hidden = true)
        val out = serialize(listOf(appWindow(group(label("Main"))), dialog, own))
        assertEquals(
            """
            n1 TextView "Main" (0,0,100,50)
            window: Allow access? (com.google.android.permissioncontroller)
              n2 Button "Allow" [clickable] (0,0,100,100)
            window: DroidHands (com.droidhands.android)
              ${TreeSerializer.HIDDEN_NOTICE}
            """.trimIndent(),
            out.text,
        )
    }

    @Test
    fun `header line`() {
        assertEquals("app=com.whatsapp activity=com.whatsapp.Conversation locked=false", TreeSerializer.header("com.whatsapp", "com.whatsapp.Conversation", false))
        assertEquals("app=unknown activity=unknown locked=true", TreeSerializer.header(null, null, true))
    }

    @Test
    fun `label is short`() {
        assertEquals("Button \"Send\"", TreeSerializer.label(button("Send")))
        assertEquals("ImageButton desc=\"Send\"", TreeSerializer.label(UiNode(className = "android.widget.ImageButton", description = "Send")))
        assertEquals("View id=send", TreeSerializer.label(UiNode(viewId = "com.whatsapp:id/send")))
    }
}
