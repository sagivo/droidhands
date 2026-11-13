package dev.phonebridge.android.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CalendarSelectionTest {
    private val zone = ZoneId.of("Asia/Jerusalem") // UTC+2 in November

    private fun cal(id: Long, writable: Boolean, primary: Boolean = false, visible: Boolean = true) =
        CalendarInfo(id, "Cal $id", "me@example.com", "com.google", writable, primary, visible)

    private fun timed(id: Long, title: String, start: String, end: String, location: String? = null) = InstanceRow(
        eventId = id, title = title, beginMillis = Instant.parse(start).toEpochMilli(), endMillis = Instant.parse(end).toEpochMilli(),
        allDay = false, location = location, description = null, calendarName = "Cal", calendarId = 1,
    )

    private fun allDay(id: Long, title: String, first: LocalDate, endExclusive: LocalDate) = InstanceRow(
        eventId = id, title = title, beginMillis = CalendarTime.allDayUtcMillis(first), endMillis = CalendarTime.allDayUtcMillis(endExclusive),
        allDay = true, location = null, description = "Bring cake", calendarName = "Cal", calendarId = 1,
    )

    @Test
    fun `default calendar prefers primary writable visible`() {
        assertEquals(3L, CalendarSelection.defaultCalendar(listOf(cal(1, false, primary = true), cal(2, true), cal(3, true, primary = true)))?.id)
        assertEquals(2L, CalendarSelection.defaultCalendar(listOf(cal(1, false), cal(2, true), cal(3, true)))?.id)
        assertEquals(3L, CalendarSelection.defaultCalendar(listOf(cal(2, true, primary = true, visible = false), cal(3, true)))?.id)
        assertEquals(2L, CalendarSelection.defaultCalendar(listOf(cal(1, false), cal(2, true, visible = false)))?.id)
        assertNull(CalendarSelection.defaultCalendar(listOf(cal(1, false))))
    }

    @Test
    fun `all-day events land on their local day only`() {
        // Local day Nov 15 in Jerusalem = [Nov 14 22:00Z, Nov 15 22:00Z). The Nov 14 all-day event
        // (stored Nov 14 00:00Z - Nov 15 00:00Z) overlaps that window in raw UTC but is not on Nov 15.
        val day15 = CalendarTime.listRange(TimeArg.Date(LocalDate.of(2026, 11, 15)), TimeArg.Date(LocalDate.of(2026, 11, 15)), null, Instant.EPOCH, zone)
        val rows = listOf(
            allDay(1, "Birthday 14", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)),
            allDay(2, "Holiday 15", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 16)),
            allDay(3, "Holiday 16", LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17)),
        )
        val (events, more) = CalendarSelection.select(rows, day15, null, 50, zone)
        assertEquals(listOf("Holiday 15"), events.map { it.row.title })
        assertFalse(more)
        assertEquals("2026-11-15T00:00:00+02:00", CalendarTime.format(events[0].start))
    }

    @Test
    fun `results are filtered, sorted, deduplicated and limited`() {
        val range = TimeRange(Instant.parse("2026-11-14T00:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"))
        val rows = listOf(
            timed(1, "Lunch", "2026-11-14T10:00:00Z", "2026-11-14T11:00:00Z", location = "Cafe Noir"),
            timed(2, "Standup", "2026-11-14T07:00:00Z", "2026-11-14T07:15:00Z"),
            timed(2, "Standup", "2026-11-14T07:00:00Z", "2026-11-14T07:15:00Z"),
            timed(3, "Outside", "2026-11-15T07:00:00Z", "2026-11-15T08:00:00Z"),
            timed(4, "Ongoing", "2026-11-13T20:00:00Z", "2026-11-14T01:00:00Z"),
        )
        val (all, _) = CalendarSelection.select(rows, range, null, 50, zone)
        assertEquals(listOf("Ongoing", "Standup", "Lunch"), all.map { it.row.title })

        val (byLocation, _) = CalendarSelection.select(rows, range, "  CAFE ", 50, zone)
        assertEquals(listOf("Lunch"), byLocation.map { it.row.title })

        val (limited, more) = CalendarSelection.select(rows, range, null, 2, zone)
        assertEquals(2, limited.size)
        assertTrue(more)
    }

    @Test
    fun `query matches description`() {
        val range = TimeRange(Instant.parse("2026-11-13T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z"))
        val rows = listOf(allDay(1, "Party", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)))
        assertEquals(1, CalendarSelection.select(rows, range, "cake", 10, zone).first.size)
        assertEquals(0, CalendarSelection.select(rows, range, "pie", 10, zone).first.size)
    }
}
