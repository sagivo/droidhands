package dev.phonebridge.android.tools.email

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAppsAndDraftTest {
    private val fixture = "dev.phonebridge.fixture.mail"
    private val outlook = "com.microsoft.office.outlook"
    private val installed = setOf(EmailApps.GMAIL, fixture, outlook, "com.whatsapp")

    private fun choose(requested: String? = null, default: String? = null, handlers: List<String>) =
        EmailApps.choose(requested, default, handlers, installed::contains) { it.substringAfterLast('.') }

    @Test
    fun `the default mailto handler wins, else gmail, else the only app`() {
        assertEquals(fixture, choose(default = fixture, handlers = listOf(EmailApps.GMAIL, fixture)))
        // "android" is the chooser: no default set.
        assertEquals(EmailApps.GMAIL, choose(default = "android", handlers = listOf(fixture, EmailApps.GMAIL)))
        assertEquals(fixture, choose(default = "android", handlers = listOf(fixture)))
        assertEquals(fixture, choose(default = null, handlers = listOf(fixture, fixture)))
    }

    @Test
    fun `several apps without a default must be chosen by the user`() {
        val e = assertThrows(ToolException::class.java) { choose(default = "android", handlers = listOf(fixture, outlook)) }
        assertTrue(e.message!!, e.message!!.contains("mail ($fixture)") && e.message!!.contains("'app'"))
        val none = assertThrows(ToolException::class.java) { choose(handlers = emptyList()) }
        assertEquals(EmailApps.NO_EMAIL_APP, none.message)
    }

    @Test
    fun `a requested app must be installed and handle mailto`() {
        assertEquals(outlook, choose(requested = outlook, default = EmailApps.GMAIL, handlers = listOf(EmailApps.GMAIL, outlook)))
        val missing = assertThrows(ToolException::class.java) { choose(requested = "com.yahoo.mobile", handlers = listOf(EmailApps.GMAIL)) }
        assertTrue(missing.message!!, missing.message!!.contains("not installed") && missing.message!!.contains(EmailApps.GMAIL))
        val notMail = assertThrows(ToolException::class.java) { choose(requested = "com.whatsapp", handlers = listOf(EmailApps.GMAIL)) }
        assertTrue(notMail.message!!, notMail.message!!.contains("can't compose emails"))
    }

    @Test
    fun `mailto uri encodes everything but unreserved characters`() {
        assertEquals(
            "mailto:a%2Btag@x.com,b@y.org?cc=c@z.io&subject=Q3%20%26%20Q4%3F&body=Hi%20Bob%2C%0ALine%202%20%23%201%20%2B%201",
            Mailto.uri(listOf("a+tag@x.com", "b@y.org"), listOf("c@z.io"), "Q3 & Q4?", "Hi Bob,\nLine 2 # 1 + 1"),
        )
        assertEquals("mailto:a@x.com?body=%D7%A9%D7%9C%D7%95%D7%9D", Mailto.uri(listOf("a@x.com"), emptyList(), "", "שלום"))
        assertEquals("mailto:a@x.com", Mailto.uri(listOf("a@x.com"), emptyList(), "", ""))
    }

    @Test
    fun `confirmation summary shows resolved recipients, subject and the start of the body`() {
        val draft = EmailDraft(
            to = listOf(EmailRecipient("tania@x.com", "Tania Cohen"), EmailRecipient("bob@y.org")),
            cc = listOf(EmailRecipient("c@z.io")),
            subject = "Offsite",
            body = "Hi all,\n\n  see you   there. " + "x".repeat(200),
        )
        val summary = draft.confirmationSummary()
        assertTrue(summary, summary.startsWith("Send email to Tania Cohen <tania@x.com>, bob@y.org (cc: c@z.io): \"Offsite\" — Hi all, see you there. x"))
        assertTrue(summary.endsWith("…"))
        assertEquals(EmailDraft.BODY_PREVIEW_CHARS + 1, summary.substringAfter(" — ").length)
        val bare = EmailDraft(listOf(EmailRecipient("a@x.com")), emptyList(), "", "Hello").confirmationSummary()
        assertEquals("Send email to a@x.com: \"(no subject)\" — Hello", bare)
        assertEquals("send an email to Tania Cohen", draft.copy(to = draft.to.take(1)).unlockAction())
        assertEquals("send an email to 2 people", draft.unlockAction())
        assertEquals("A B", EmailDraft.cleanSubject(" A\r\nB "))
    }
}
