package dev.phonebridge.android.tools.comms

import dev.phonebridge.android.tools.support.ContactMatch
import dev.phonebridge.android.tools.support.PhoneEntry
import dev.phonebridge.android.tools.support.WhatsAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientSelectionTest {
    private fun mobile(e164: String) = PhoneEntry(number = e164, normalized = e164, type = "mobile")

    private fun contact(
        id: Long,
        name: String,
        score: Double,
        phones: List<PhoneEntry> = listOf(mobile("+97254000000$id")),
        whatsapp: List<WhatsAppEntry> = emptyList(),
    ) = ContactMatch(id, name, phones, emptyList(), whatsapp, starred = false, score = score)

    private fun chosen(choice: ContactChoice): ContactMatch = (choice as ContactChoice.Chosen).match

    // --- chooseContact ---

    @Test
    fun noMatchesIsNotFound() {
        assertSame(ContactChoice.NotFound, RecipientSelection.chooseContact(emptyList()))
    }

    @Test
    fun uniqueExactMatchWinsEvenWithCloseRunnersUp() {
        val tania = contact(1, "Tania", 1.0)
        val choice = RecipientSelection.chooseContact(listOf(contact(2, "Tania Cohen", 0.9), tania, contact(3, "Tanya", 0.95)))
        assertEquals(tania, chosen(choice))
    }

    @Test
    fun severalExactMatchesAreAmbiguous() {
        val choice = RecipientSelection.chooseContact(listOf(contact(1, "Dan", 1.0), contact(2, "Dan", 1.0), contact(3, "Dana", 0.9)))
        choice as ContactChoice.Ambiguous
        assertFalse(choice.weak)
        assertEquals(listOf(1L, 2L, 3L), choice.candidates.map { it.id })
    }

    @Test
    fun exactDuplicatesWithTheSameNumbersAreOnePerson() {
        val phones = listOf(mobile("+972541234567"))
        val first = contact(1, "Tania Cohen", 1.0, phones)
        val choice = RecipientSelection.chooseContact(listOf(first, contact(2, "Tania Cohen", 1.0, phones)))
        assertEquals(first, chosen(choice))
    }

    @Test
    fun strongMatchWinsOnlyWhenRunnerUpIsWeak() {
        val top = contact(1, "Tania Cohen", 0.9)
        assertEquals(top, chosen(RecipientSelection.chooseContact(listOf(top))))
        assertEquals(top, chosen(RecipientSelection.chooseContact(listOf(top, contact(2, "Tamara", 0.74)))))
        val ambiguous = RecipientSelection.chooseContact(listOf(top, contact(2, "Tanya Levi", 0.75)))
        assertTrue(ambiguous is ContactChoice.Ambiguous)
        assertFalse((ambiguous as ContactChoice.Ambiguous).weak)
    }

    @Test
    fun autoPickThresholdIsInclusive() {
        val top = contact(1, "Tania Cohen", 0.85)
        assertEquals(top, chosen(RecipientSelection.chooseContact(listOf(top))))
    }

    @Test
    fun weakBestMatchIsNeverPicked() {
        val choice = RecipientSelection.chooseContact(listOf(contact(1, "Daniel", 0.83)))
        choice as ContactChoice.Ambiguous
        assertTrue(choice.weak)
        assertEquals(1, choice.candidates.size)
    }

    @Test
    fun candidatesAreCappedAndSortedByScore() {
        val matches = (1L..12L).map { contact(it, "Dan $it", 0.8 + it / 100.0) }
        val choice = RecipientSelection.chooseContact(matches) as ContactChoice.Ambiguous
        assertEquals(RecipientSelection.MAX_CANDIDATES, choice.candidates.size)
        assertEquals(12L, choice.candidates.first().id)
    }

    // --- chooseNumber ---

    @Test
    fun whatsappNumberPreferredWhenAsked() {
        val match = contact(1, "Tania", 1.0, whatsapp = listOf(WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp")))
        assertEquals(NumberChoice.Chosen("+972541234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = true))
        assertEquals(NumberChoice.Chosen("+972540000001"), RecipientSelection.chooseNumber(match, preferWhatsApp = false))
    }

    @Test
    fun sameWhatsappNumberInBothAppsIsOneNumber() {
        val match = contact(
            1, "Tania", 1.0,
            whatsapp = listOf(
                WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp"),
                WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp.w4b"),
            ),
        )
        assertEquals(NumberChoice.Chosen("+972541234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = true))
    }

    @Test
    fun severalWhatsappNumbersAreAmbiguous() {
        val match = contact(
            1, "Tania", 1.0,
            whatsapp = listOf(
                WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp"),
                WhatsAppEntry("972501112233", "972501112233@s.whatsapp.net", "com.whatsapp"),
            ),
        )
        val choice = RecipientSelection.chooseNumber(match, preferWhatsApp = true) as NumberChoice.Ambiguous
        assertEquals("WhatsApp numbers", choice.kind)
        assertEquals(listOf("+972541234567 (WhatsApp)", "+972501112233 (WhatsApp)"), choice.options)
    }

    @Test
    fun withoutWhatsappFallsBackToMobile() {
        val match = contact(1, "Tania", 1.0, phones = listOf(PhoneEntry("03-1234567", "+97231234567", "home"), mobile("+972541234567")))
        assertEquals(NumberChoice.Chosen("+972541234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = true))
        assertEquals(NumberChoice.Chosen("+972541234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = false))
    }

    @Test
    fun severalMobilesAreAmbiguous() {
        val match = contact(1, "Tania", 1.0, phones = listOf(mobile("+972541234567"), PhoneEntry("050-111 2233", "+972501112233", "work mobile")))
        val choice = RecipientSelection.chooseNumber(match, preferWhatsApp = false) as NumberChoice.Ambiguous
        assertEquals("mobile numbers", choice.kind)
        assertEquals(listOf("+972541234567 (mobile)", "+972501112233 (work mobile)"), choice.options)
    }

    @Test
    fun theSameNumberStoredTwiceIsNotAmbiguous() {
        val match = contact(1, "Tania", 1.0, phones = listOf(mobile("+972541234567"), PhoneEntry("054-123-4567", "+972541234567", "mobile")))
        assertEquals(NumberChoice.Chosen("+972541234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = false))
    }

    @Test
    fun withoutMobileASingleNumberOfAnyTypeIsUsed() {
        val match = contact(1, "Office", 1.0, phones = listOf(PhoneEntry("03-1234567", "+97231234567", "work")))
        assertEquals(NumberChoice.Chosen("+97231234567"), RecipientSelection.chooseNumber(match, preferWhatsApp = false))
    }

    @Test
    fun withoutMobileSeveralNumbersAreAmbiguous() {
        val match = contact(
            1, "Office", 1.0,
            phones = listOf(PhoneEntry("03-1234567", "+97231234567", "work"), PhoneEntry("03-7654321", "+97237654321", "home")),
        )
        assertEquals("phone numbers", (RecipientSelection.chooseNumber(match, preferWhatsApp = false) as NumberChoice.Ambiguous).kind)
    }

    @Test
    fun noPhoneIsNoNumber() {
        assertSame(NumberChoice.NoNumber, RecipientSelection.chooseNumber(contact(1, "Tania", 1.0, phones = emptyList()), preferWhatsApp = true))
    }

    @Test
    fun dialableFallsBackToTheCleanedStoredNumber() {
        assertEquals("0541234567", RecipientSelection.dialable(PhoneEntry("054-123 4567", null, "mobile")))
        assertEquals("+972541234567", RecipientSelection.dialable(PhoneEntry("054-123 4567", "+972541234567", "mobile")))
    }

    // --- messages ---

    @Test
    fun candidateDescriptions() {
        assertEquals("Tania Cohen (+972541234567)", RecipientSelection.describeCandidate(contact(1, "Tania Cohen", 1.0, listOf(mobile("+972541234567")))))
        assertEquals("Tania Cohen (no phone number)", RecipientSelection.describeCandidate(contact(1, "Tania Cohen", 1.0, emptyList())))
        val many = contact(
            1, "Tania", 1.0,
            phones = listOf(PhoneEntry("03-1", "+97231111111", "home"), mobile("+972541234567"), mobile("+972501112233")),
        )
        assertEquals("Tania (+972541234567, +972501112233, …)", RecipientSelection.describeCandidate(many))
        val whatsappOnly = contact(1, "Tania", 1.0, emptyList(), listOf(WhatsAppEntry("972541234567", "972541234567@s.whatsapp.net", "com.whatsapp")))
        assertEquals("Tania (+972541234567)", RecipientSelection.describeCandidate(whatsappOnly))
    }

    @Test
    fun ambiguityMessagesListCandidatesAndTheNumberArgument() {
        val choice = ContactChoice.Ambiguous(listOf(contact(1, "Tania Cohen", 0.9), contact(2, "Tanya Levi", 0.87)), weak = false)
        val message = RecipientSelection.ambiguousMessage("Tania", choice, "to")
        assertTrue(message, message.startsWith("Several contacts match \"Tania\": Tania Cohen (+972540000001); Tanya Levi (+972540000002)."))
        assertTrue(message, message.contains("'to'"))

        val weak = RecipientSelection.ambiguousMessage("Danielle", ContactChoice.Ambiguous(listOf(contact(1, "Daniel", 0.8)), weak = true), "address")
        assertTrue(weak, weak.startsWith("No contact closely matches \"Danielle\". The closest is Daniel (+972540000001)."))
        assertTrue(weak, weak.contains("'address'"))

        assertTrue(RecipientSelection.notFoundMessage("Zed", "to").contains("contacts_search"))
        val numbers = NumberChoice.Ambiguous(listOf("+1 (mobile)", "+2 (mobile)"), "mobile numbers")
        assertEquals(
            "Contact \"Tania\" has several mobile numbers: +1 (mobile), +2 (mobile). Ask the user which one to use and pass it as 'to'.",
            RecipientSelection.ambiguousNumberMessage(contact(1, "Tania", 1.0), numbers, "to"),
        )
    }
}
