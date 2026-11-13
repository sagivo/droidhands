package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarTimeTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem") // UTC+2 in November
    private val newYork = ZoneId.of("America/New_York") // UTC-5 in November

    private fun instant(text: String) = Instant.parse(text)

    private fun dateTime(raw: String, zone: ZoneId = jerusalem) = CalendarTime.parse(raw, "start", zone) as TimeArg.DateTime

    @Test
    fun `local date-times are read in the device zone`() {
        assertEquals(instant("2026-11-14T13:00:00Z"), dateTime("2026-11-14T15:00").instant)
        assertEquals(instant("2026-11-14T13:00:30Z"), dateTime("2026-11-14T15:00:30").instant)
        assertEquals(instant("2026-11-14T13:00:30.250Z"), dateTime("2026-11-14T15:00:30.25").instant)
        assertEquals(instant("2026-11-14T13:00:00Z"), dateTime("2026-11-14 15:00").instant)
        assertEquals(instant("2026-11-14T20:00:00Z"), dateTime("2026-11-14T15:00", newYork).instant)
        assertFalse(dateTime("2026-11-14T15:00").explicitZone)
    }

    @Test
    fun `offsets and zones are honored`() {
        assertEquals(instant("2026-11-14T15:00:00Z"), dateTime("2026-11-14T15:00Z").instant)
        assertEquals(instant("2026-11-14T15:00:00Z"), dateTime("2026-11-14t15:00:00z").instant)
        assertEquals(instant("2026-11-14T13:00:00Z"), dateTime("2026-11-14T15:00:00+02:00").instant)
        assertEquals(instant("2026-11-14T13:00:00Z"), dateTime("2026-11-14T15:00+0200").instant)
        assertEquals(instant("2026-11-14T20:00:00Z"), dateTime("2026-11-14T15:00-05").instant)
        assertEquals(instant("2026-11-14T14:00:00Z"), dateTime("2026-11-14T15:00+01:00[Europe/Paris]").instant)
        assertTrue(dateTime("2026-11-14T15:00Z").explicitZone)
    }

    @Test
    fun `date-only values parse as dates`() {
        assertEquals(TimeArg.Date(LocalDate.of(2026, 11, 14)), CalendarTime.parse(" 2026-11-14 ", "start", jerusalem))
    }

    @Test
    fun `invalid values are rejected with an example`() {
        for (bad in listOf("", "tomorrow", "2026-13-01", "2026-02-30", "14/11/2026", "2026-11-14T25:00")) {
            val e = assertThrows(bad, ToolException::class.java) { CalendarTime.parse(bad, "start", jerusalem) }
            assertTrue(e.message!!.contains("start"))
        }
    }

    @Test
    fun `list range defaults to now plus 7 days`() {
        val now = instant("2026-11-13T10:00:00Z")
        val r = CalendarTime.listRange(null, null, null, now, jerusalem)
        assertEquals(now, r.start)
        assertEquals(instant("2026-11-20T10:00:00Z"), r.end)
    }

    @Test
    fun `date-only bounds cover whole local days`() {
        val day = TimeArg.Date(LocalDate.of(2026, 11, 14))
        val r = CalendarTime.listRange(day, day, null, Instant.EPOCH, jerusalem)
        assertEquals(instant("2026-11-13T22:00:00Z"), r.start)
        assertEquals(instant("2026-11-14T22:00:00Z"), r.end)
        val days = CalendarTime.listRange(day, null, 2, Instant.EPOCH, jerusalem)
        assertEquals(instant("2026-11-15T22:00:00Z"), days.end)
    }

    @Test
    fun `list range validation`() {
        val now = instant("2026-11-13T10:00:00Z")
        val earlier = TimeArg.DateTime(instant("2026-11-12T10:00:00Z"), true)
        assertThrows(ToolException::class.java) { CalendarTime.listRange(null, earlier, null, now, jerusalem) }
        assertThrows(ToolException::class.java) { CalendarTime.listRange(null, null, 0, now, jerusalem) }
        assertThrows(ToolException::class.java) { CalendarTime.listRange(null, null, 400, now, jerusalem) }
        val far = TimeArg.Date(LocalDate.of(2028, 1, 1))
        assertThrows(ToolException::class.java) { CalendarTime.listRange(null, far, null, now, jerusalem) }
    }

    @Test
    fun `all-day boundaries convert between UTC midnight and local dates`() {
        val millis = CalendarTime.allDayUtcMillis(LocalDate.of(2026, 11, 14))
        assertEquals(instant("2026-11-14T00:00:00Z").toEpochMilli(), millis)
        assertEquals(LocalDate.of(2026, 11, 14), CalendarTime.allDayDate(millis))

        // Stored at UTC midnight; shown as local midnight of the same date, not shifted a day.
        val end = CalendarTime.allDayUtcMillis(LocalDate.of(2026, 11, 15))
        for (zone in listOf(jerusalem, newYork, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Pago_Pago"))) {
            val (s, e) = CalendarTime.instanceWindow(millis, end, allDay = true, zone = zone)
            assertEquals(LocalDate.of(2026, 11, 14), s.toLocalDate())
            assertEquals(0, s.hour)
            assertEquals(LocalDate.of(2026, 11, 15), e.toLocalDate())
        }
        assertEquals("2026-11-14T00:00:00-05:00", CalendarTime.format(CalendarTime.instanceWindow(millis, end, true, newYork).first))
    }

    @Test
    fun `timed instances keep their instants`() {
        val begin = instant("2026-11-14T13:00:00Z").toEpochMilli()
        val (s, e) = CalendarTime.instanceWindow(begin, begin + 3_600_000, allDay = false, zone = jerusalem)
        assertEquals("2026-11-14T15:00:00+02:00", CalendarTime.format(s))
        assertEquals("2026-11-14T16:00:00+02:00", CalendarTime.format(e))
    }

    @Test
    fun `overlap is half-open and includes zero-length events inside`() {
        val r = TimeRange(instant("2026-11-14T00:00:00Z"), instant("2026-11-15T00:00:00Z"))
        assertTrue(CalendarTime.overlaps(instant("2026-11-13T23:00:00Z"), instant("2026-11-14T01:00:00Z"), r))
        assertFalse(CalendarTime.overlaps(instant("2026-11-13T23:00:00Z"), instant("2026-11-14T00:00:00Z"), r))
        assertFalse(CalendarTime.overlaps(instant("2026-11-15T00:00:00Z"), instant("2026-11-15T01:00:00Z"), r))
        assertTrue(CalendarTime.overlaps(instant("2026-11-14T00:00:00Z"), instant("2026-11-14T00:00:00Z"), r))
        assertFalse(CalendarTime.overlaps(instant("2026-11-15T00:00:00Z"), instant("2026-11-15T00:00:00Z"), r))
    }

    @Test
    fun `timed event defaults to 60 minutes in the device zone`() {
        val plan = CalendarTime.planEvent(dateTime("2026-11-14T15:00"), null, null, null, jerusalem)
        assertFalse(plan.allDay)
        assertEquals(instant("2026-11-14T13:00:00Z").toEpochMilli(), plan.dtStartMillis)
        assertEquals(instant("2026-11-14T14:00:00Z").toEpochMilli(), plan.dtEndMillis)
        assertEquals("Asia/Jerusalem", plan.timeZoneId)
        assertEquals("2026-11-14T15:00:00+02:00", plan.start)
        assertEquals("2026-11-14T16:00:00+02:00", plan.end)
    }

    @Test
    fun `timed event with end or duration`() {
        val start = dateTime("2026-11-14T15:00")
        val withEnd = CalendarTime.planEvent(start, dateTime("2026-11-14T15:45"), 999, null, jerusalem)
        assertEquals(45 * 60_000L, withEnd.dtEndMillis - withEnd.dtStartMillis)
        val withDuration = CalendarTime.planEvent(start, null, 90, null, jerusalem)
        assertEquals(90 * 60_000L, withDuration.dtEndMillis - withDuration.dtStartMillis)
        assertThrows(ToolException::class.java) { CalendarTime.planEvent(start, dateTime("2026-11-14T15:00"), null, null, jerusalem) }
        assertThrows(ToolException::class.java) { CalendarTime.planEvent(start, null, 0, null, jerusalem) }
    }

    @Test
    fun `date start makes an all-day event at UTC midnight`() {
        val plan = CalendarTime.planEvent(TimeArg.Date(LocalDate.of(2026, 11, 14)), null, null, null, jerusalem)
        assertTrue(plan.allDay)
        assertEquals("UTC", plan.timeZoneId)
        assertEquals(instant("2026-11-14T00:00:00Z").toEpochMilli(), plan.dtStartMillis)
        assertEquals(instant("2026-11-15T00:00:00Z").toEpochMilli(), plan.dtEndMillis)
        assertEquals("2026-11-14T00:00:00+02:00", plan.start)
        assertEquals("2026-11-15T00:00:00+02:00", plan.end)
    }

    @Test
    fun `all-day end dates are inclusive, local-midnight ends exclusive`() {
        val start = TimeArg.Date(LocalDate.of(2026, 11, 14))
        val inclusive = CalendarTime.planEvent(start, TimeArg.Date(LocalDate.of(2026, 11, 16)), null, null, jerusalem)
        assertEquals(instant("2026-11-17T00:00:00Z").toEpochMilli(), inclusive.dtEndMillis)
        val sameDay = CalendarTime.planEvent(start, start, null, null, jerusalem)
        assertEquals(instant("2026-11-15T00:00:00Z").toEpochMilli(), sameDay.dtEndMillis)
        val exclusive = CalendarTime.planEvent(start, dateTime("2026-11-16T00:00"), null, null, jerusalem)
        assertEquals(instant("2026-11-16T00:00:00Z").toEpochMilli(), exclusive.dtEndMillis)
        val days = CalendarTime.planEvent(start, null, 3 * 24 * 60 + 1, null, jerusalem)
        assertEquals(instant("2026-11-18T00:00:00Z").toEpochMilli(), days.dtEndMillis)
        assertThrows(ToolException::class.java) {
            CalendarTime.planEvent(start, TimeArg.Date(LocalDate.of(2026, 11, 13)), null, null, jerusalem)
        }
    }

    @Test
    fun `all_day flag overrides the start format`() {
        val timedFromDate = CalendarTime.planEvent(TimeArg.Date(LocalDate.of(2026, 11, 14)), null, null, false, jerusalem)
        assertFalse(timedFromDate.allDay)
        assertEquals("2026-11-14T00:00:00+02:00", timedFromDate.start)
        assertEquals("2026-11-14T01:00:00+02:00", timedFromDate.end)

        // 23:30 in New York is already the 15th in UTC; the local date (14th) must be used.
        val allDayFromTime = CalendarTime.planEvent(dateTime("2026-11-14T23:30", newYork), null, null, true, newYork)
        assertTrue(allDayFromTime.allDay)
        assertEquals(instant("2026-11-14T00:00:00Z").toEpochMilli(), allDayFromTime.dtStartMillis)
    }
}
