package dev.phonebridge.android.tools.email

import dev.phonebridge.android.access.Bounds
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowKind
import dev.phonebridge.android.access.WindowSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compose-screen heuristics against the E2E fixture mail app's layout and a Gmail-like layout. */
class EmailComposeTest {
    private val mail = "dev.phonebridge.fixture.mail"
    private val gmail = EmailApps.GMAIL
    private val screen = Bounds(0, 0, 1080, 2400)
    private val tania = EmailRecipient("tania@x.com", "Tania Cohen")
    private val draft = EmailDraft(listOf(tania), emptyList(), "Offsite", "See you there")

    private fun row(top: Int) = Bounds(36, top, 1044, top + 130)

    private fun edit(pkg: String, id: String?, hint: String?, text: String? = null, top: Int, bounds: Bounds = row(top)) = UiNode(
        text = text, hint = hint, viewId = id?.let { "$pkg:id/$it" }, className = "android.widget.EditText", packageName = pkg,
        bounds = bounds, editable = true, clickable = true,
    )

    private fun window(pkg: String, vararg children: UiNode, bounds: Bounds = screen) = WindowSnapshot(
        "Compose", WindowKind.APPLICATION, pkg, bounds, primary = true,
        UiNode(className = "android.widget.LinearLayout", packageName = pkg, bounds = bounds, children = children.toList()),
    )

    private fun sendButton(pkg: String, id: String? = "send", desc: String? = "Send", enabled: Boolean = true) = UiNode(
        description = desc, viewId = id?.let { "$pkg:id/$it" }, className = "android.widget.ImageButton", packageName = pkg,
        bounds = Bounds(900, 80, 1044, 224), clickable = true, enabled = enabled,
    )

    /** fixtures/fakemail activity_compose.xml: ids to/cc/bcc/subject/body/send, hints, "Send" description. */
    private fun fixture(to: String? = "tania@x.com", cc: String? = null, subject: String? = "Offsite", body: String? = "See you there") = window(
        mail,
        UiNode(
            className = "android.widget.LinearLayout", packageName = mail, bounds = Bounds(0, 80, 1080, 224),
            children = listOf(
                UiNode(description = "Navigate up", viewId = "$mail:id/close", className = "android.widget.ImageButton", packageName = mail, clickable = true, bounds = Bounds(0, 80, 144, 224)),
                UiNode(text = "Compose", viewId = "$mail:id/compose_title", className = "android.widget.TextView", packageName = mail, bounds = Bounds(144, 120, 900, 180)),
                sendButton(mail),
            ),
        ),
        UiNode(text = "From  me@fixture.test", viewId = "$mail:id/from_account", className = "android.widget.TextView", packageName = mail, bounds = Bounds(0, 230, 1080, 300)),
        edit(mail, "to", "To", to, top = 300),
        edit(mail, "cc", "Cc", cc, top = 430),
        edit(mail, "bcc", "Bcc", null, top = 560),
        edit(mail, "subject", "Subject", subject, top = 690),
        edit(mail, "body", "Compose email", body, top = 820, bounds = Bounds(36, 820, 1044, 2300)),
    )

    /** Gmail-style: "To" label, chips plus an input with a non-obvious id, an icon to expand Cc/Bcc. */
    private fun gmailLike(chip: String?, input: String? = null) = window(
        gmail,
        sendButton(gmail),
        UiNode(
            className = "android.widget.LinearLayout", packageName = gmail, bounds = Bounds(0, 300, 1080, 430),
            children = listOf(
                UiNode(text = "To", className = "android.widget.TextView", packageName = gmail, bounds = Bounds(36, 330, 120, 400)),
                UiNode(
                    viewId = "$gmail:id/peoplekit_autocomplete_chip_group", className = "android.view.ViewGroup", packageName = gmail,
                    bounds = Bounds(130, 300, 960, 430),
                    children = listOfNotNull(
                        chip?.let { UiNode(text = it, className = "com.google.android.material.chip.Chip", packageName = gmail, clickable = true, bounds = Bounds(130, 310, 500, 420)) },
                        edit(gmail, "peoplekit_autocomplete_input", null, input, top = 300, bounds = Bounds(500, 300, 960, 430)),
                    ),
                ),
                UiNode(description = "Show Cc/Bcc", className = "android.widget.ImageView", packageName = gmail, clickable = true, bounds = Bounds(960, 300, 1080, 430)),
            ),
        ),
        edit(gmail, "subject", "Subject", "Offsite", top = 440),
        edit(gmail, "body", "Compose email", "See you there\n\n--\nSent from my phone", top = 580, bounds = Bounds(36, 580, 1044, 2300)),
    )

    @Test
    fun `fixture fields are found by id and a filled screen is ready`() {
        val compose = EmailCompose.find(mail, listOf(fixture()))!!
        assertEquals(setOf(ComposeField.TO, ComposeField.CC, ComposeField.BCC, ComposeField.SUBJECT, ComposeField.BODY), compose.fields.keys)
        assertEquals("$mail:id/send", compose.send?.viewId)
        assertEquals("tania@x.com", compose.shown[ComposeField.TO])
        assertTrue(ComposeChecks.allOk(ComposeChecks.check(compose, draft)))
        assertNull(EmailCompose.find("com.other", listOf(fixture())))
    }

    @Test
    fun `empty fields are filled, never overwritten`() {
        val empty = EmailCompose.find(mail, listOf(fixture(to = null, subject = null, body = null)))!!
        assertEquals(
            listOf(
                FieldCheck.Fill(ComposeField.TO, "tania@x.com"),
                FieldCheck.Fill(ComposeField.SUBJECT, "Offsite"),
                FieldCheck.Fill(ComposeField.BODY, "See you there"),
            ),
            ComposeChecks.check(empty, draft),
        )
        val other = EmailCompose.find(mail, listOf(fixture(to = "tania@x.com, eve@evil.com", subject = "Old draft", body = "Something else")))!!
        val checks = ComposeChecks.check(other, draft)
        assertTrue(checks.all { it is FieldCheck.Mismatch })
        val problem = ComposeChecks.problem(checks, "Fixture Mail")!!
        assertTrue(problem, problem.contains("eve@evil.com") && problem.contains("nothing was sent"))
    }

    @Test
    fun `a body duplicated from the uri and the extras is rewritten`() {
        val doubled = EmailCompose.find(mail, listOf(fixture(body = "See you there\nSee you there")))!!
        assertEquals(FieldCheck.Fill(ComposeField.BODY, "See you there"), ComposeChecks.check(doubled, draft).last())
        val reflowed = EmailCompose.find(mail, listOf(fixture(body = "  See you\n there  ")))!!
        assertEquals(FieldCheck.Ok(ComposeField.BODY), ComposeChecks.check(reflowed, draft).last())
    }

    @Test
    fun `cc is checked only when the draft has cc, and must be on screen`() {
        val withCc = draft.copy(cc = listOf(EmailRecipient("bob@y.org")))
        val filled = EmailCompose.find(mail, listOf(fixture(cc = "bob@y.org")))!!
        assertTrue(ComposeChecks.allOk(ComposeChecks.check(filled, withCc)))
        val noCcRow = EmailCompose.find(gmail, listOf(gmailLike(chip = "Tania Cohen")))!!
        assertEquals(FieldCheck.Missing(ComposeField.CC), ComposeChecks.check(noCcRow, withCc)[1])
        assertTrue(ComposeChecks.problem(ComposeChecks.check(noCcRow, withCc), "Gmail")!!.contains("Cc field"))
    }

    @Test
    fun `gmail-like chips match by name, an empty chip row is filled`() {
        val compose = EmailCompose.find(gmail, listOf(gmailLike(chip = "Tania Cohen")))!!
        assertEquals("$gmail:id/peoplekit_autocomplete_input", compose.fields[ComposeField.TO]?.viewId)
        assertEquals("Tania Cohen", compose.shown[ComposeField.TO])
        // The body keeps the app's signature; containing the approved text is enough.
        assertTrue(ComposeChecks.allOk(ComposeChecks.check(compose, draft)))
        val empty = EmailCompose.find(gmail, listOf(gmailLike(chip = null)))!!
        assertEquals("", empty.shown[ComposeField.TO])
        assertEquals(FieldCheck.Fill(ComposeField.TO, "tania@x.com"), ComposeChecks.check(empty, draft).first())
        val typed = EmailCompose.find(gmail, listOf(gmailLike(chip = null, input = "tania@x.com, ")))!!
        assertEquals(FieldCheck.Ok(ComposeField.TO), ComposeChecks.check(typed, draft).first())
    }

    @Test
    fun `fields without ids are recognized by hint or by a label in the same row`() {
        val pkg = "com.example.mail"
        val byHint = EmailCompose.classifyFields(
            window(pkg, edit(pkg, null, "To", top = 300), edit(pkg, null, "Subject", top = 430), edit(pkg, null, "Compose email", top = 560)).root!!,
        )
        assertEquals(listOf(ComposeField.TO, ComposeField.SUBJECT, ComposeField.BODY), byHint.keys.toList())
        val label = { text: String, top: Int -> UiNode(text = text, className = "android.widget.TextView", packageName = pkg, bounds = Bounds(0, top + 40, 120, top + 90)) }
        val byLabel = EmailCompose.classifyFields(
            window(
                pkg,
                label("To:", 300), edit(pkg, null, null, top = 300, bounds = Bounds(130, 300, 1044, 430)),
                label("Subject", 430), edit(pkg, null, null, top = 430, bounds = Bounds(130, 430, 1044, 560)),
                edit(pkg, "editor", null, top = 560),
            ).root!!,
        )
        assertEquals(listOf(ComposeField.TO, ComposeField.SUBJECT, ComposeField.BODY), byLabel.keys.toList())
    }

    @Test
    fun `view id words`() {
        assertEquals(listOf("compose", "send", "button"), EmailCompose.idWords("composeSendButton"))
        assertEquals(listOf("to", "content"), EmailCompose.idWords("to_content"))
        assertEquals(ComposeField.TO, EmailCompose.roleOfId("to"))
        assertEquals(ComposeField.BCC, EmailCompose.roleOfId("bcc"))
        assertEquals(ComposeField.CC, EmailCompose.roleOfId("ccField"))
        assertEquals(ComposeField.BODY, EmailCompose.roleOfId("compose_body"))
        assertNull(EmailCompose.roleOfId("toolbar"))
        assertNull(EmailCompose.roleOfId("photo"))
    }

    @Test
    fun `send button by id, never send-later, falling back to the description`() {
        val pkg = "com.example.mail"
        assertEquals("$pkg:id/action_send", EmailCompose.findSend(listOf(window(pkg, sendButton(pkg, "send_later"), sendButton(pkg, "action_send", desc = null)).root!!))?.viewId)
        assertNull(EmailCompose.findSend(listOf(window(pkg, sendButton(pkg, "schedule_send", desc = null)).root!!)))
        val icon = UiNode(description = "Send", className = "android.widget.ImageView", packageName = pkg, bounds = Bounds(900, 80, 1044, 224))
        val wrapper = UiNode(className = "android.widget.FrameLayout", packageName = pkg, clickable = true, bounds = Bounds(900, 80, 1044, 224), children = listOf(icon))
        assertNotNull(EmailCompose.findSend(listOf(window(pkg, wrapper).root!!)))
        assertNull(EmailCompose.findSend(listOf(window(pkg, sendButton(pkg, enabled = false)).root!!)))
        assertNotNull(EmailCompose.findSend(listOf(window(pkg, sendButton(pkg, id = null, desc = "שליחה")).root!!)))
    }

    @Test
    fun `recipient verdicts`() {
        val bob = EmailRecipient("bob@y.org")
        assertEquals(ComposeChecks.RecipientsVerdict.Empty, ComposeChecks.recipientsVerdict("  ", listOf(tania)))
        assertEquals(ComposeChecks.RecipientsVerdict.Match, ComposeChecks.recipientsVerdict("TANIA@x.com", listOf(tania)))
        assertEquals(ComposeChecks.RecipientsVerdict.Match, ComposeChecks.recipientsVerdict("Tania Cohen bob@y.org", listOf(tania, bob)))
        assertEquals(ComposeChecks.RecipientsVerdict.Foreign(listOf("eve@z.io")), ComposeChecks.recipientsVerdict("tania@x.com eve@z.io", listOf(tania)))
        assertEquals(ComposeChecks.RecipientsVerdict.Incomplete(listOf("bob@y.org")), ComposeChecks.recipientsVerdict("tania@x.com", listOf(tania, bob)))
        assertEquals(ComposeChecks.RecipientsVerdict.Other, ComposeChecks.recipientsVerdict("hello", listOf(tania)))
    }

    @Test
    fun `after send - closed, signals, dialogs`() {
        val composing = listOf(fixture())
        val baseline = SendSignals.baseline(mail, composing)
        assertEquals(AfterSend.Composing, SendSignals.classify(mail, composing, baseline))
        val inbox = window(mail, UiNode(text = "Inbox", className = "android.widget.TextView", packageName = mail, bounds = Bounds(0, 80, 1080, 200)))
        assertEquals(AfterSend.Closed, SendSignals.classify(mail, listOf(inbox), baseline))
        val snackbar = window(mail, UiNode(text = "Sending…", viewId = "$mail:id/snackbar_text", className = "android.widget.TextView", packageName = mail, bounds = Bounds(0, 2200, 1080, 2300)))
        assertEquals(AfterSend.Signal("sending"), SendSignals.classify(mail, listOf(snackbar), baseline))
        // A "Sent" label that was already there before the tap is no signal.
        val sentFolder = window(mail, UiNode(text = "Sent", className = "android.widget.TextView", packageName = mail, bounds = Bounds(0, 80, 1080, 200)))
        assertEquals(AfterSend.Closed, SendSignals.classify(mail, listOf(sentFolder), setOf("sent")))
    }

    @Test
    fun `no-subject question and error dialogs`() {
        fun dialog(title: String?, message: String, positive: String) = WindowSnapshot(
            null, WindowKind.APPLICATION, mail, Bounds(80, 900, 1000, 1500), primary = false,
            UiNode(
                className = "android.widget.FrameLayout", packageName = mail, bounds = Bounds(80, 900, 1000, 1500),
                children = listOfNotNull(
                    title?.let { UiNode(text = it, viewId = "android:id/alertTitle", className = "android.widget.TextView", packageName = mail, bounds = Bounds(100, 920, 980, 990)) },
                    UiNode(text = message, viewId = "android:id/message", className = "android.widget.TextView", packageName = mail, bounds = Bounds(100, 1000, 980, 1200)),
                    UiNode(text = positive, viewId = "android:id/button1", className = "android.widget.Button", packageName = mail, clickable = true, bounds = Bounds(700, 1400, 980, 1480)),
                ),
            ),
        )
        val prompt = SendSignals.classify(mail, listOf(dialog(null, "Send this message without a subject?", "Send"), fixture()), emptySet())
        assertTrue(prompt is AfterSend.SubjectPrompt && prompt.confirm.viewId == "android:id/button1")
        val problem = SendSignals.classify(mail, listOf(dialog("Invalid address", "Check \"x@\" and try again.", "OK"), fixture()), emptySet())
        assertEquals(AfterSend.Problem("Invalid address Check \"x@\" and try again."), problem)
    }
}
