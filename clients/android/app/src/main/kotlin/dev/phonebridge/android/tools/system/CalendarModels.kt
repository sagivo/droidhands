package dev.phonebridge.android.tools.system

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** A row of CalendarContract.Calendars, reduced to what the tools expose. */
data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String?,
    val accountType: String?,
    /** Access level >= CAL_ACCESS_CONTRIBUTOR: events can be created in it. */
    val writable: Boolean,
    val primary: Boolean,
    val visible: Boolean,
)

/** A row of CalendarContract.Instances (one occurrence of a possibly recurring event). */
data class InstanceRow(
    val eventId: Long,
    val title: String?,
    /** Instances.BEGIN/END: epoch millis; UTC-midnight boundaries for all-day events. */
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val calendarName: String?,
    val calendarId: Long,
)

/** An instance placed in the device time zone, ready to be rendered. */
data class EventOccurrence(val row: InstanceRow, val start: ZonedDateTime, val end: ZonedDateTime)

/** Pure selection logic for the calendar tools. */
object CalendarSelection {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    const val MAX_DESCRIPTION_CHARS = 500

    /** Default target for new events: primary writable visible calendar, else first writable visible, else first writable. */
    fun defaultCalendar(calendars: List<CalendarInfo>): CalendarInfo? =
        calendars.firstOrNull { it.writable && it.visible && it.primary }
            ?: calendars.firstOrNull { it.writable && it.visible }
            ?: calendars.firstOrNull { it.writable }

    /**
     * Places [rows] in [zone], keeps those overlapping [range] (the provider query is widened by a
     * day on each side because all-day instances are stored in UTC) and matching [query]
     * (case-insensitive, in title/location/description), sorted by start then title.
     * Returns at most [limit] items and whether more matched.
     */
    fun select(rows: List<InstanceRow>, range: TimeRange, query: String?, limit: Int, zone: ZoneId): Pair<List<EventOccurrence>, Boolean> {
        val needle = query?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val matching = rows.asSequence()
            .map { row ->
                val (start, end) = CalendarTime.instanceWindow(row.beginMillis, row.endMillis, row.allDay, zone)
                EventOccurrence(row, start, end)
            }
            .filter { CalendarTime.overlaps(it.start.toInstant(), it.end.toInstant(), range) }
            .filter { needle == null || it.row.matches(needle) }
            .distinctBy { Triple(it.row.eventId, it.row.beginMillis, it.row.endMillis) }
            .sortedWith(compareBy<EventOccurrence> { it.start.toInstant() }.thenBy { it.row.title.orEmpty().lowercase(Locale.ROOT) })
            .toList()
        return matching.take(limit) to (matching.size > limit)
    }

    private fun InstanceRow.matches(needle: String): Boolean =
        listOf(title, location, description).any { it?.lowercase(Locale.ROOT)?.contains(needle) == true }
}
