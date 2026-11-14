package dev.phonebridge.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTextTest {
    @Test
    fun cleanStripsFormatting() {
        assertEquals("+972541234567", PhoneText.clean("+972 (54) 123-4567"))
        assertEquals("0541234567", PhoneText.clean("054‑123‑4567")) // non-breaking hyphens
        assertEquals("+972541234567", PhoneText.clean("‎+972 54 123 4567‏")) // bidi marks, NBSP
        assertEquals("2125551234", PhoneText.clean("212.555.1234"))
        assertEquals("+12125551234", PhoneText.clean("tel:+1-212-555-1234"))
        assertEquals("+12125551234", PhoneText.clean("＋1 212 555 1234")) // full-width plus
    }

    @Test
    fun cleanConvertsNonAsciiDigits() {
        assertEquals("0541234567", PhoneText.clean("٠٥٤١٢٣٤٥٦٧")) // Arabic-Indic
        assertEquals("0541234567", PhoneText.clean("۰۵۴۱۲۳۴۵۶۷")) // Persian
    }

    @Test
    fun cleanDropsDialingSuffixes() {
        assertEquals("+12125551234", PhoneText.clean("+1 212 555 1234 ext. 12"))
        assertEquals("+12125551234", PhoneText.clean("+1 212 555 1234 x12"))
        assertEquals("2125551234", PhoneText.clean("212-555-1234,5"))
        assertEquals("2125551234", PhoneText.clean("212-555-1234;5"))
    }

    @Test
    fun cleanKeepsServiceCodes() {
        assertEquals("*100#", PhoneText.clean("*100#"))
    }

    @Test
    fun cleanRejectsNonNumbers() {
        assertNull(PhoneText.clean("Tania"))
        assertNull(PhoneText.clean("1-800-FLOWERS"))
        assertNull(PhoneText.clean("12+34"))
        assertNull(PhoneText.clean(""))
        assertNull(PhoneText.clean("---"))
        assertNull(PhoneText.clean("x123"))
    }

    @Test
    fun phoneNumberQueries() {
        assertTrue(PhoneText.looksLikePhoneNumber("054-1234567"))
        assertTrue(PhoneText.looksLikePhoneNumber("+972 54 123 4567"))
        assertTrue(PhoneText.looksLikePhoneNumber("4567"))
        assertTrue(PhoneText.looksLikePhoneNumber("(212) 555-1234"))
        assertFalse(PhoneText.looksLikePhoneNumber("Tania"))
        assertFalse(PhoneText.looksLikePhoneNumber("Tania 054"))
        assertFalse(PhoneText.looksLikePhoneNumber("12"))
        assertFalse(PhoneText.looksLikePhoneNumber("tania@example.com"))
    }

    @Test
    fun internationalPrefix() {
        assertEquals("+972541234567", PhoneText.internationalize("00972541234567"))
        assertEquals("0541234567", PhoneText.internationalize("0541234567"))
        assertEquals("+972541234567", PhoneText.internationalize("+972541234567"))
        assertEquals("00", PhoneText.internationalize("00"))
    }

    @Test
    fun plausibleE164() {
        assertEquals("+972541234567", PhoneText.plausibleE164("+972541234567"))
        assertNull(PhoneText.plausibleE164("+12345"))
        assertNull(PhoneText.plausibleE164("+1234567890123456"))
        assertNull(PhoneText.plausibleE164("972541234567"))
        assertNull(PhoneText.plausibleE164("+0541234567"))
        assertNull(PhoneText.plausibleE164("+97254-1234567"))
    }

    @Test
    fun comparableDigits() {
        assertEquals("972541234567", PhoneText.comparableDigits("+972 54-123-4567"))
        assertEquals("972541234567", PhoneText.comparableDigits("00972541234567"))
        assertEquals("541234567", PhoneText.comparableDigits("054-123 4567"))
        assertEquals("2125551234", PhoneText.comparableDigits("(212) 555-1234"))
    }

    @Test
    fun looseNumberMatching() {
        assertTrue(PhoneText.numbersMatch("+972541234567", "054-123-4567"))
        assertTrue(PhoneText.numbersMatch("00972541234567", "+972 54 123 4567"))
        assertTrue(PhoneText.numbersMatch("+12125551234", "(212) 555-1234"))
        assertTrue(PhoneText.numbersMatch("+44 7700 900123", "07700 900123"))
        assertTrue(PhoneText.numbersMatch("+39 06 1234 5678", "06 1234 5678")) // Italy keeps the leading 0
        assertTrue(PhoneText.numbersMatch("1234", "1234"))
        assertFalse(PhoneText.numbersMatch("+12125551234", "+12125551235"))
        assertFalse(PhoneText.numbersMatch("1234", "5551234")) // short codes need an exact match
        assertFalse(PhoneText.numbersMatch("", "1234"))
        assertFalse(PhoneText.numbersMatch("Google", "Google"))
    }

    @Test
    fun countryCandidates() {
        assertEquals(listOf("IL", "US"), PhoneText.countryCandidates("il", " IL ", "us", null, "", "XYZ", "1A"))
        assertEquals(emptyList<String>(), PhoneText.countryCandidates(null, ""))
    }
}
