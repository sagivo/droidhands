package dev.phonebridge.android.tools.whatsapp

import dev.phonebridge.android.access.Bounds
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowKind
import dev.phonebridge.android.access.WindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppScreensTest {
    private val wa = "com.whatsapp"
    private val screen = Bounds(0, 0, 1080, 2400)
    private val digits = "972541234567"
    private val tania = ExpectedChat(digits, listOf("Tania Cohen"), namesAuthoritative = true)

    private fun text(s: String, top: Int = 1000, id: String? = null, clickable: Boolean = false) = UiNode(
        text = s, viewId = id, className = "android.widget.TextView", packageName = wa,
        bounds = Bounds(100, top, 900, top + 60), clickable = clickable,
    )

    private fun window(vararg children: UiNode, bounds: Bounds = screen, pkg: String = wa, primary: Boolean = true) = WindowSnapshot(
        "WhatsApp", WindowKind.APPLICATION, pkg, bounds, primary,
        UiNode(className = "android.widget.FrameLayout", packageName = pkg, bounds = bounds, children = children.toList()),
    )

    private fun chat(
        title: String? = "Tania Cohen",
        titleById: Boolean = true,
        entryText: String? = null,
        sendVisible: Boolean = true,
        bubbles: List<String> = emptyList(),
        entryById: Boolean = true,
        pkg: String = wa,
    ): WindowSnapshot {
        val toolbar = UiNode(
            className = "android.view.ViewGroup", viewId = "$pkg:id/toolbar", packageName = pkg, bounds = Bounds(0, 80, 1080, 240),
            children = listOfNotNull(
                UiNode(className = "android.widget.ImageButton", description = "Navigate up", clickable = true, bounds = Bounds(0, 80, 120, 240)),
                title?.let {
                    UiNode(
                        text = it, viewId = if (titleById) "$pkg:id/conversation_contact_name" else null,
                        className = "android.widget.TextView", packageName = pkg, bounds = Bounds(150, 100, 700, 170),
                    )
                },
            ),
        )
        val list = UiNode(
            className = "android.widget.ListView", viewId = "android:id/list", scrollable = true, packageName = pkg, bounds = Bounds(0, 240, 1080, 2150),
            children = bubbles.mapIndexed { i, b ->
                UiNode(text = b, viewId = "$pkg:id/message_text", className = "android.widget.TextView", packageName = pkg, bounds = Bounds(300, 260 + i * 100, 1000, 340 + i * 100))
            },
        )
        val entry = UiNode(
            text = entryText, hint = "Message", viewId = if (entryById) "$pkg:id/entry" else null, className = "android.widget.EditText",
            packageName = pkg, editable = true, clickable = true, bounds = Bounds(100, 2200, 900, 2320),
        )
        val button = if (sendVisible) {
            UiNode(description = "Send", viewId = "$pkg:id/send", className = "android.widget.ImageButton", packageName = pkg, clickable = true, bounds = Bounds(948, 2200, 1068, 2320))
        } else {
            UiNode(description = "Voice message", viewId = "$pkg:id/voice_note_btn", className = "android.widget.ImageButton", packageName = pkg, clickable = true, bounds = Bounds(948, 2200, 1068, 2320))
        }
        return window(toolbar, list, entry, button, pkg = pkg)
    }

    private fun dialog(vararg texts: String) = WindowSnapshot(
        null, WindowKind.APPLICATION, wa, Bounds(80, 900, 1000, 1500), primary = true,
        UiNode(
            className = "android.widget.FrameLayout", packageName = wa, bounds = Bounds(80, 900, 1000, 1500),
            children = texts.map { UiNode(text = it, className = "android.widget.TextView", packageName = wa, bounds = Bounds(100, 950, 980, 1100)) } +
                UiNode(text = "OK", viewId = "android:id/button1", className = "android.widget.Button", packageName = wa, clickable = true, bounds = Bounds(700, 1400, 980, 1480)),
        ),
    )

    private fun home() = window(
        UiNode(viewId = "$wa:id/fab", description = "New chat", clickable = true, className = "android.widget.ImageButton", packageName = wa, bounds = Bounds(900, 2000, 1040, 2140)),
        text("Bob: see you", top = 600),
        primary = false,
    )

    @Test
    fun `conversation is detected by ids`() {
        val s = WhatsAppScreens.classify(wa, listOf(chat()), digits) as WaScreen.Conversation
        assertTrue(s.entryById)
        assertEquals("", s.entryText)
        assertEquals("$wa:id/send", s.send?.viewId)
        assertEquals("Tania Cohen", s.title)
        assertEquals(TitleVerdict.MATCH, WhatsAppScreens.judge(s, tania))
        val typed = WhatsAppScreens.classify(wa, listOf(chat(entryText = "hi ")), digits) as WaScreen.Conversation
        assertEquals("hi", typed.entryText)
        val noSend = WhatsAppScreens.classify(wa, listOf(chat(sendVisible = false)), digits) as WaScreen.Conversation
        assertNull(noSend.send)
    }

    @Test
    fun `business package uses its own ids`() {
        val s = WhatsAppScreens.classify(WhatsAppApps.BUSINESS, listOf(chat(pkg = WhatsAppApps.BUSINESS)), digits)
        assertTrue(s is WaScreen.Conversation && s.entryById)
        assertEquals(WaScreen.NotVisible, WhatsAppScreens.classify(WhatsAppApps.BUSINESS, listOf(chat()), digits))
    }

    @Test
    fun `entry falls back to the only editable in the bottom half`() {
        val s = WhatsAppScreens.classify(wa, listOf(chat(entryById = false)), digits) as WaScreen.Conversation
        assertFalse(s.entryById)
        assertEquals("Message", s.entry.hint)
    }

    @Test
    fun `title verdicts`() {
        fun verdict(title: String, expected: ExpectedChat = tania) = WhatsAppScreens.judgeTitle(title, expected)
        assertEquals(TitleVerdict.MATCH, verdict("tania  COHEN"))
        assertEquals(TitleVerdict.MATCH, verdict("Tania Cohen ❤️", ExpectedChat(digits, listOf("Tania Cohen"), true)))
        assertEquals(TitleVerdict.MISMATCH, verdict("Bob Levi"))
        assertEquals(TitleVerdict.UNKNOWN, verdict("Bob Levi", ExpectedChat(digits, emptyList(), namesAuthoritative = false)))
        assertEquals(TitleVerdict.MATCH, verdict("‪+972 54-123-4567‬"))
        assertEquals(TitleVerdict.MATCH, verdict("054-123-4567"))
        assertEquals(TitleVerdict.MISMATCH, verdict("+972 54-999-0000"))
        assertEquals(TitleVerdict.UNKNOWN, verdict("   "))
    }

    @Test
    fun `toolbar text matches when the title id is missing, bubbles never count`() {
        val noId = WhatsAppScreens.classify(wa, listOf(chat(titleById = false)), digits) as WaScreen.Conversation
        assertNull(noId.title)
        assertEquals(TitleVerdict.MATCH, WhatsAppScreens.judge(noId, tania))

        val wrongChat = WhatsAppScreens.classify(wa, listOf(chat(title = "Bob", titleById = false, bubbles = listOf("Tania Cohen"))), digits) as WaScreen.Conversation
        assertEquals(TitleVerdict.UNKNOWN, WhatsAppScreens.judge(wrongChat, tania))
        val wrongById = WhatsAppScreens.classify(wa, listOf(chat(title = "Bob", bubbles = listOf("Tania Cohen"))), digits) as WaScreen.Conversation
        assertEquals(TitleVerdict.MISMATCH, WhatsAppScreens.judge(wrongById, tania))
    }

    @Test
    fun `not on whatsapp dialog in english and hebrew`() {
        val en = WhatsAppScreens.classify(wa, listOf(home(), dialog("The phone number +972 54-123-4567 isn’t on WhatsApp.")), digits)
        assertTrue(en is WaScreen.NotOnWhatsApp)
        val he = WhatsAppScreens.classify(wa, listOf(home(), dialog("מספר הטלפון ‎+972 54-123-4567‎ לא נמצא ב־WhatsApp.")), digits)
        assertTrue(he is WaScreen.NotOnWhatsApp)
        val invite = WhatsAppScreens.classify(wa, listOf(home(), dialog("Invite to WhatsApp")), digits)
        assertTrue(invite is WaScreen.NotOnWhatsApp)
        val es = WhatsAppScreens.classify(wa, listOf(home(), dialog("El número +972 54-123-4567 no está en WhatsApp.")), digits)
        assertTrue(es is WaScreen.NotOnWhatsApp)
    }

    @Test
    fun `a chat message quoting the phrase is not the dialog`() {
        val s = WhatsAppScreens.classify(wa, listOf(chat(bubbles = listOf("Dan isn't on WhatsApp anymore"))), digits)
        assertTrue(s is WaScreen.Conversation)
        // In the main window the phrase counts only together with the recipient's number.
        val noNumber = WhatsAppScreens.classify(wa, listOf(window(text("Dan isn't on WhatsApp"))), digits)
        assertEquals(WaScreen.Unknown, noNumber)
        val withNumber = WhatsAppScreens.classify(wa, listOf(window(text("+972 54-123-4567 isn't on WhatsApp"))), digits)
        assertTrue(withNumber is WaScreen.NotOnWhatsApp)
    }

    @Test
    fun `blocked contact dialog over the chat`() {
        val s = WhatsAppScreens.classify(wa, listOf(chat(entryText = "hi"), dialog("Unblock Tania Cohen to send a message?")), digits)
        assertTrue(s is WaScreen.Blocked)
        val banner = WhatsAppScreens.classify(wa, listOf(window(text("You blocked this contact", top = 2000), text("Unblock", top = 2100, clickable = true))), digits)
        assertTrue(banner is WaScreen.Blocked)
    }

    @Test
    fun `registration, app lock and home screens`() {
        assertEquals(WaScreen.Registration, WhatsAppScreens.classify(wa, listOf(window(text("Welcome to WhatsApp"), text("Agree and continue", clickable = true))), digits))
        assertEquals(WaScreen.Registration, WhatsAppScreens.classify(wa, listOf(window(text("הסכמה והמשך", clickable = true))), digits))
        assertEquals(WaScreen.Locked, WhatsAppScreens.classify(wa, listOf(window(text("WhatsApp locked"))), digits))
        assertEquals(WaScreen.Home, WhatsAppScreens.classify(wa, listOf(home()), digits))
        assertEquals(WaScreen.Home, WhatsAppScreens.classify(wa, listOf(window(text("Chats", 2300), text("Updates", 2300), text("Calls", 2300))), digits))
        assertEquals(WaScreen.Home, WhatsAppScreens.classify(wa, listOf(window(text("צ׳אטים", 2300), text("עדכונים", 2300))), digits))
    }

    @Test
    fun `no whatsapp window, hidden windows ignored`() {
        assertEquals(WaScreen.NotVisible, WhatsAppScreens.classify(wa, listOf(window(text("Launcher"), pkg = "com.google.android.apps.nexuslauncher")), digits))
        val hidden = WindowSnapshot("x", WindowKind.APPLICATION, wa, screen, true, root = null, hidden = true)
        assertEquals(WaScreen.NotVisible, WhatsAppScreens.classify(wa, listOf(hidden), digits))
    }

    @Test
    fun `send button by localized description when the id changed`() {
        val send = UiNode(description = "שליחה", className = "android.widget.ImageButton", clickable = true, bounds = Bounds(948, 2200, 1068, 2320))
        val root = UiNode(children = listOf(UiNode(description = "Send", className = "android.widget.ImageView"), send))
        assertSame(send, WhatsAppScreens.findSend(wa, listOf(root)))
        assertNull(WhatsAppScreens.findSend(wa, listOf(UiNode(children = listOf(UiNode(description = "Send"))))))
    }

    @Test
    fun `message comparison and bubble counting`() {
        assertTrue(WhatsAppScreens.sameMessage("‫שלום‬  ", "שלום"))
        assertTrue(WhatsAppScreens.sameMessage("a\r\nb", "a\nb"))
        assertFalse(WhatsAppScreens.sameMessage("Hi", "hi"))
        assertFalse(WhatsAppScreens.sameMessage(null, "hi"))
        val windows = listOf(chat(entryText = "see you", bubbles = listOf("see you", "other", "see you  ")))
        assertEquals(2, WhatsAppScreens.bubbleCount(wa, windows, "see you"))
        assertEquals(0, WhatsAppScreens.bubbleCount(wa, windows, "   "))
    }

    @Test
    fun `number and name keys`() {
        assertTrue(WhatsAppScreens.digitsMatch("972541234567", "972541234567"))
        assertTrue(WhatsAppScreens.digitsMatch("0541234567", "972541234567"))
        assertFalse(WhatsAppScreens.digitsMatch("972541234568", "972541234567"))
        assertFalse(WhatsAppScreens.digitsMatch("4567", "972541234567"))
        assertFalse(WhatsAppScreens.digitsMatch("", "972541234567"))
        assertEquals("tania cohen", WhatsAppScreens.nameKey("~ Tania, Cohen 🌸"))
        assertEquals("טניה כהן", WhatsAppScreens.nameKey("‫טניה כהן‬"))
    }

    @Test
    fun `sending only accepts fresh, verified chats`() {
        fun gate(v: TitleVerdict, strict: Boolean = true, fresh: Boolean = true, byId: Boolean = true) =
            WhatsAppScreens.acceptance(v, strict, fresh, byId)
        assertEquals(ChatAcceptance.ACCEPT, gate(TitleVerdict.MATCH))
        assertEquals(ChatAcceptance.WAIT, gate(TitleVerdict.MATCH, fresh = false))
        assertEquals(ChatAcceptance.WAIT, gate(TitleVerdict.MISMATCH))
        assertEquals(ChatAcceptance.ACCEPT_IF_STABLE, gate(TitleVerdict.UNKNOWN))
        assertEquals(ChatAcceptance.WAIT, gate(TitleVerdict.UNKNOWN, byId = false))
        assertEquals(ChatAcceptance.WAIT, gate(TitleVerdict.UNKNOWN, fresh = false))
        // Browsing (whatsapp_open_chat) only refuses contradicting chats.
        assertEquals(ChatAcceptance.ACCEPT, gate(TitleVerdict.UNKNOWN, strict = false, fresh = false, byId = false))
        assertEquals(ChatAcceptance.WAIT, gate(TitleVerdict.MISMATCH, strict = false))
    }
}
