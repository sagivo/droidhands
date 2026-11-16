package com.droidhands.android.tools.system

import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ClockLogicTest {
    @Test
    fun `days map to Calendar constants ordered monday first`() {
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.SUNDAY),
            ClockLogic.parseDays(listOf("sun", "Wed", "mon", "monday")),
        )
        assertEquals(listOf(Calendar.TUESDAY, Calendar.THURSDAY), ClockLogic.parseDays(listOf("tues", "Thursday.")))
        assertEquals(emptyList<Int>(), ClockLogic.parseDays(emptyList()))
    }

    @Test
    fun `day groups expand`() {
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            ClockLogic.parseDays(listOf("weekdays")),
        )
        assertEquals(listOf(Calendar.SATURDAY, Calendar.SUNDAY), ClockLogic.parseDays(listOf("weekends")))
        assertEquals(7, ClockLogic.parseDays(listOf("daily", "mon")).size)
    }

    @Test
    fun `ambiguous or unknown days are rejected`() {
        assertThrows(ToolException::class.java) { ClockLogic.parseDays(listOf("t")) } // tue or thu
        assertThrows(ToolException::class.java) { ClockLogic.parseDays(listOf("someday")) }
    }

    @Test
    fun `day names and formatting`() {
        assertEquals("mon", ClockLogic.dayName(Calendar.MONDAY))
        assertEquals("sun", ClockLogic.dayName(Calendar.SUNDAY))
        assertEquals("07:05", ClockLogic.formatTime(7, 5))
        assertEquals("07:30 every mon, fri \"Gym\"", ClockLogic.describeAlarm(7, 30, listOf(Calendar.MONDAY, Calendar.FRIDAY), "Gym"))
        assertEquals("1h 30m", ClockLogic.formatDuration(5400))
        assertEquals("45s", ClockLogic.formatDuration(45))
        assertEquals("1h 1s", ClockLogic.formatDuration(3601))
    }

    @Test
    fun `ranges are validated`() {
        assertEquals(23, ClockLogic.requireHour(23))
        assertThrows(ToolException::class.java) { ClockLogic.requireHour(24) }
        assertThrows(ToolException::class.java) { ClockLogic.requireMinute(60) }
        assertEquals(86_400, ClockLogic.requireTimerSeconds(86_400))
        assertThrows(ToolException::class.java) { ClockLogic.requireTimerSeconds(0) }
        assertThrows(ToolException::class.java) { ClockLogic.requireTimerSeconds(86_401) }
    }
}

class SpeechLogicTest {
    @Test
    fun `text is trimmed and bounded`() {
        assertEquals("hello", SpeechLogic.requireText("  hello \n"))
        assertThrows(ToolException::class.java) { SpeechLogic.requireText("   ") }
        val e = assertThrows(ToolException::class.java) { SpeechLogic.requireText("a".repeat(SpeechLogic.MAX_CHARS + 1)) }
        assertTrue(e.message!!.contains("Split"))
    }

    @Test
    fun `timeout grows with text and is capped at 60 s`() {
        assertEquals(10_000L, SpeechLogic.timeoutMs(5))
        assertEquals(14_000L, SpeechLogic.timeoutMs(100))
        assertEquals(60_000L, SpeechLogic.timeoutMs(10_000))
        assertTrue(SpeechLogic.timeoutMs(SpeechLogic.MAX_CHARS) <= SpeechLogic.MAX_TIMEOUT_MS)
    }

    @Test
    fun `language tags parse`() {
        assertNull(SpeechLogic.parseLanguage(null))
        assertNull(SpeechLogic.parseLanguage("  "))
        assertEquals("en-US", SpeechLogic.parseLanguage("en-US")!!.toLanguageTag())
        assertEquals("pt-BR", SpeechLogic.parseLanguage("pt_BR")!!.toLanguageTag())
        assertEquals("fr", SpeechLogic.parseLanguage("fr")!!.language)
        assertThrows(ToolException::class.java) { SpeechLogic.parseLanguage("!!") }
    }
}
