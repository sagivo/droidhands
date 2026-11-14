package dev.phonebridge.android.tools.system

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

class ClockRingTimeTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    /** Saturday 2026-11-14 22:10 local time. */
    private val now = ZonedDateTime.of(2026, 11, 14, 22, 10, 30, 0, zone)

    @Test
    fun `one-time alarm later today rings today`() {
        assertEquals(ZonedDateTime.of(2026, 11, 14, 23, 0, 0, 0, zone), ClockLogic.nextRing(now, 23, 0, emptyList()))
    }

    @Test
    fun `one-time alarm at an earlier time rings tomorrow`() {
        assertEquals(ZonedDateTime.of(2026, 11, 15, 7, 30, 0, 0, zone), ClockLogic.nextRing(now, 7, 30, emptyList()))
        // The current minute has already started, so it is tomorrow too.
        assertEquals(ZonedDateTime.of(2026, 11, 15, 22, 10, 0, 0, zone), ClockLogic.nextRing(now, 22, 10, emptyList()))
    }

    @Test
    fun `repeating alarm rings on the next listed day`() {
        val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        assertEquals(ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, zone), ClockLogic.nextRing(now, 7, 0, weekdays))
        assertEquals(ZonedDateTime.of(2026, 11, 14, 23, 0, 0, 0, zone), ClockLogic.nextRing(now, 23, 0, listOf(Calendar.SATURDAY)))
        // Same weekday, but the time has passed: one week later.
        assertEquals(ZonedDateTime.of(2026, 11, 21, 8, 0, 0, 0, zone), ClockLogic.nextRing(now, 8, 0, listOf(Calendar.SATURDAY)))
    }

    @Test
    fun `alarm inside a DST gap moves forward`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:30 does not exist in New York (clocks jump from 02:00 to 03:00).
        val before = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny)
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny), ClockLogic.nextRing(before, 2, 30, emptyList()))
    }

    @Test
    fun `countdowns round up to the minute`() {
        assertEquals("50m", ClockLogic.formatUntil(now, ZonedDateTime.of(2026, 11, 14, 23, 0, 0, 0, zone)))
        assertEquals("9h 20m", ClockLogic.formatUntil(now, ZonedDateTime.of(2026, 11, 15, 7, 30, 0, 0, zone)))
        assertEquals("1m", ClockLogic.formatUntil(now, now.plusSeconds(5)))
        assertEquals("1m", ClockLogic.formatUntil(now, now))
        assertEquals("1d", ClockLogic.formatUntil(now, now.plusDays(1)))
        assertEquals("6d 23h 50m", ClockLogic.formatUntil(now, ZonedDateTime.of(2026, 11, 21, 22, 0, 0, 0, zone)))
    }

    @Test
    fun `calendar day constants convert to day of week`() {
        assertEquals(DayOfWeek.SUNDAY, ClockLogic.dayOfWeek(Calendar.SUNDAY))
        assertEquals(DayOfWeek.MONDAY, ClockLogic.dayOfWeek(Calendar.MONDAY))
        assertEquals(DayOfWeek.SATURDAY, ClockLogic.dayOfWeek(Calendar.SATURDAY))
    }
}
