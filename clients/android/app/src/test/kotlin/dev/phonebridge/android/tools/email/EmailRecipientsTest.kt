package dev.phonebridge.android.tools.email

import dev.phonebridge.android.tools.support.ContactMatch
import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailRecipientsTest {
    private fun contact(name: String, score: Double, vararg emails: String, id: Long = name.hashCode().toLong()) =
        ContactMatch(id, name, phones = emptyList(), emails = emails.toList(), whatsapp = emptyList(), starred = false, score = score)

    @Test
    fun `address validation`() {
        assertTrue(EmailAddresses.isValid("tania@example.com"))
        assertTrue(EmailAddresses.isValid("first.last+tag@sub.example.co.il"))
        assertTrue(EmailAddresses.isValid("דני@דוגמה.קום"))
        assertFalse(EmailAddresses.isValid("tania@example"))
        assertFalse(EmailAddresses.isValid("tania example.com"))
        assertFalse(EmailAddresses.isValid("@example.com"))
        assertFalse(EmailAddresses.isValid("a..b@example.com"))
        assertFalse(EmailAddresses.isValid("a@-example.com"))
        assertFalse(EmailAddresses.isValid("a@b@example.com"))
    }

    @Test
    fun `lists with names, quotes, semicolons and duplicates`() {
        val parsed = EmailAddresses.parseList(
            " a@x.com, Tania Cohen <tania@y.org>; \"Levi, Bob\" <bob@z.io>, mailto:c@x.com, A@X.com ",
            "to",
        )
        assertEquals(
            listOf(
                EmailRecipient("a@x.com"),
                EmailRecipient("tania@y.org", "Tania Cohen"),
                EmailRecipient("bob@z.io", "Levi, Bob"),
                EmailRecipient("c@x.com"),
            ),
            parsed,
        )
        assertEquals("Tania Cohen <tania@y.org>", parsed[1].display)
        assertEquals("a@x.com", parsed[0].display)
        assertEquals(listOf("a@x.com", "\"Levi, Bob\" <b@z.io>"), EmailAddresses.split("a@x.com,,\"Levi, Bob\" <b@z.io>;"))
    }

    @Test
    fun `invalid entries are all named in the error`() {
        val e = assertThrows(ToolException::class.java) { EmailAddresses.parseList("good@x.com, bad, worse@", "to") }
        assertTrue(e.message!!, e.message!!.contains("\"bad\"") && e.message!!.contains("\"worse@\""))
        assertTrue(e.message!!.contains("contact"))
        val cc = assertThrows(ToolException::class.java) { EmailAddresses.parseList("nope", "cc") }
        assertTrue(cc.message!!.startsWith("'cc'"))
        assertThrows(ToolException::class.java) { EmailAddresses.parseList(" , ", "to") }
        assertNull(EmailAddresses.parseEntry("Tania <not an address>"))
    }

    @Test
    fun `a unique exact contact with one email is chosen`() {
        val choice = EmailContactSelection.choose(listOf(contact("Tania Cohen", 1.0, "tania@x.com"), contact("Tanya Levi", 0.6, "t@l.com")))
        assertEquals(EmailContactChoice.Chosen(EmailRecipient("tania@x.com", "Tania Cohen")), choice)
    }

    @Test
    fun `a strong match wins only when the runner-up is weak`() {
        assertEquals(
            EmailContactChoice.Chosen(EmailRecipient("tania@x.com", "Tania Cohen")),
            EmailContactSelection.choose(listOf(contact("Tania Cohen", 0.9, "tania@x.com"), contact("Bob", 0.5, "b@x.com"))),
        )
        val close = EmailContactSelection.choose(listOf(contact("Tania Cohen", 0.9, "tania@x.com"), contact("Tanya Cohen", 0.8, "tanya@x.com")))
        assertTrue(close is EmailContactChoice.AmbiguousContacts && !close.weak)
        val weak = EmailContactSelection.choose(listOf(contact("Tamar", 0.5, "tamar@x.com")))
        assertTrue(weak is EmailContactChoice.AmbiguousContacts && weak.weak)
        assertTrue(EmailContactSelection.message("Tania", weak).contains("Tamar (tamar@x.com)"))
    }

    @Test
    fun `several exact matches are ambiguous unless they are duplicates`() {
        val two = EmailContactSelection.choose(listOf(contact("Dana", 1.0, "dana@a.com"), contact("Dana", 1.0, "dana@b.com")))
        assertTrue(two is EmailContactChoice.AmbiguousContacts)
        val message = EmailContactSelection.message("Dana", two)
        assertTrue(message, message.contains("Dana (dana@a.com); Dana (dana@b.com)") && message.contains("'to'"))
        val duplicates = EmailContactSelection.choose(listOf(contact("Dana", 1.0, "dana@a.com", id = 1), contact("Dana", 1.0, "DANA@a.com", id = 2)))
        assertEquals(EmailContactChoice.Chosen(EmailRecipient("dana@a.com", "Dana")), duplicates)
    }

    @Test
    fun `the contact needs exactly one valid email`() {
        val none = EmailContactSelection.choose(listOf(contact("Bob", 1.0, "not-an-email")))
        assertTrue(none is EmailContactChoice.NoEmail)
        assertTrue(EmailContactSelection.message("Bob", none).contains("no email address"))
        val several = EmailContactSelection.choose(listOf(contact("Bob", 1.0, "bob@a.com", "bob@b.com", "Bob@a.com")))
        assertEquals(listOf("bob@a.com", "bob@b.com"), (several as EmailContactChoice.AmbiguousEmails).emails)
        assertTrue(EmailContactSelection.message("Bob", several).contains("bob@a.com, bob@b.com"))
        assertEquals(EmailContactChoice.NotFound, EmailContactSelection.choose(emptyList()))
        assertTrue(EmailContactSelection.message("Zed", EmailContactChoice.NotFound).contains("No contact matches \"Zed\""))
    }
}
