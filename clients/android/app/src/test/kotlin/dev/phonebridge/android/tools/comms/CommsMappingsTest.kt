package dev.phonebridge.android.tools.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class CommsMappingsTest {
    @Test
    fun smsBoxes() {
        assertEquals(listOf(1), SmsBoxes.typesFor("inbox"))
        assertEquals(listOf(2), SmsBoxes.typesFor("sent"))
        assertNull(SmsBoxes.typesFor("all"))
        assertEquals("inbox", SmsBoxes.name(1))
        assertEquals("sent", SmsBoxes.name(2))
        assertEquals("draft", SmsBoxes.name(3))
        assertEquals("outbox", SmsBoxes.name(4))
        assertEquals("failed", SmsBoxes.name(5))
        assertEquals("queued", SmsBoxes.name(6))
        assertEquals("other", SmsBoxes.name(42))
    }

    @Test
    fun callTypeFilters() {
        assertNull(CallTypes.typesFor("all"))
        assertEquals(listOf(1, 7), CallTypes.typesFor("incoming"))
        assertEquals(listOf(2), CallTypes.typesFor("outgoing"))
        // missed, rejected, blocked, voicemail
        assertEquals(setOf(3, 5, 6, 4), CallTypes.typesFor("missed")!!.toSet())
    }

    @Test
    fun callTypeNames() {
        assertEquals("incoming", CallTypes.name(1))
        assertEquals("outgoing", CallTypes.name(2))
        assertEquals("missed", CallTypes.name(3))
        assertEquals("voicemail", CallTypes.name(4))
        assertEquals("rejected", CallTypes.name(5))
        assertEquals("blocked", CallTypes.name(6))
        assertEquals("answered_elsewhere", CallTypes.name(7))
        assertEquals("other", CallTypes.name(1000))
    }

    @Test
    fun hiddenNumbers() {
        assertNull(CallTypes.hiddenNumberName(1)) // allowed
        assertEquals("private", CallTypes.hiddenNumberName(2))
        assertEquals("unknown", CallTypes.hiddenNumberName(3))
        assertEquals("payphone", CallTypes.hiddenNumberName(4))
        assertEquals("unknown", CallTypes.hiddenNumberName(5))
    }

    @Test
    fun phoneTypeLabels() {
        assertEquals("mobile", PhoneTypes.label(2, null))
        assertEquals("work mobile", PhoneTypes.label(17, null))
        assertEquals("home", PhoneTypes.label(1, null))
        assertEquals("work", PhoneTypes.label(3, "ignored"))
        assertEquals("other", PhoneTypes.label(7, null))
        assertEquals("Cell", PhoneTypes.label(0, " Cell "))
        assertEquals("other", PhoneTypes.label(0, "  "))
        assertEquals("other", PhoneTypes.label(999, null))
        assertTrue(PhoneTypes.isMobile("mobile"))
        assertTrue(PhoneTypes.isMobile("work mobile"))
        assertFalse(PhoneTypes.isMobile("home"))
    }

    @Test
    fun isoTimes() {
        assertEquals("1970-01-01T00:00:00Z", IsoTime.format(0, ZoneOffset.UTC))
        assertEquals("2024-11-13T14:13:20+02:00", IsoTime.format(1_731_500_000_123, ZoneId.of("Asia/Jerusalem")))
    }

    @Test
    fun likeEscaping() {
        assertEquals("50\\%\\_off\\\\", SqlText.escapeLike("50%_off\\"))
        assertEquals("plain text", SqlText.escapeLike("plain text"))
        assertEquals("%a\\%%", SqlText.containsPattern("a%"))
        assertEquals("?,?,?", SqlText.placeholders(3))
        assertEquals("?", SqlText.placeholders(1))
    }

    @Test
    fun smsResultCodes() {
        assertEquals(-1, SmsResultCodes.OK)
        assertTrue(SmsResultCodes.describe(2).contains("airplane")) // RESULT_ERROR_RADIO_OFF
        assertTrue(SmsResultCodes.describe(4).contains("no cellular service")) // RESULT_ERROR_NO_SERVICE
        assertTrue(SmsResultCodes.describe(5).contains("rate limit")) // RESULT_ERROR_LIMIT_EXCEEDED
        assertTrue(SmsResultCodes.describe(7).contains("short code")) // RESULT_ERROR_SHORT_CODE_NOT_ALLOWED
        assertTrue(SmsResultCodes.describe(120).contains("SIM")) // RESULT_RIL_SIM_ABSENT
        assertTrue(SmsResultCodes.describe(1).contains("generic")) // RESULT_ERROR_GENERIC_FAILURE
        assertTrue(SmsResultCodes.describe(9999).contains("9999"))
    }
}
