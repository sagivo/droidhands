package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** A parsed ISO-8601 tool argument: either a calendar date or a point in time. */
sealed interface TimeArg {
    data class Date(val date: LocalDate) : TimeArg

    /** [explicitZone] is true when the text carried an offset/zone (else it was read in the device zone). */
    data class DateTime(val instant: Instant, val explicitZone: Boolean) : TimeArg
}

/** A half-open time range `[start, end)`. */
data class TimeRange(val start: Instant, val end: Instant)

/** What `calendar_event_create` writes: provider column values plus echo strings. */
data class EventPlan(
    val allDay: Boolean,
    /** Events.DTSTART (UTC midnight for all-day events). */
    val dtStartMillis: Long,
    /** Events.DTEND (exclusive; UTC midnight of the day after the last day for all-day events). */
    val dtEndMillis: Long,
    /** Events.EVENT_TIMEZONE ("UTC" for all-day events, as CalendarContract requires). */
    val timeZoneId: String,
    /** ISO-8601 with offset, in the device zone (local midnight for all-day events). */
    val start: String,
    val end: String,
)

/**
 * ISO-8601 parsing and calendar time math (pure java.time). CalendarContract stores all-day
 * events at UTC midnight, so all-day values are converted between UTC-midnight millis and the
 * device's local dates here.
 */
object CalendarTime {
    const val MAX_RANGE_DAYS = 366L
    const val DEFAULT_DAYS = 7
    const val DEFAULT_DURATION_MINUTES = 60
    const val MAX_DURATION_MINUTES = 366 * 24 * 60
    private const val MINUTES_PER_DAY = 24 * 60

    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val DATE_SPACE_TIME = Regex("^(\\d{4}-\\d{2}-\\d{2})[ ]+(\\d)")
    private val TRAILING_OFFSET = Regex("([+-])(\\d{2}):?(\\d{2})?$")
    private val OUTPUT = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * Accepts `2026-11-14`, `2026-11-14T15:00`, `2026-11-14T15:00:30(.123)`, `2026-11-14 15:00`,
     * and any of those with `Z`, `+02:00`, `+0200`, `+02` or `+01:00[Europe/Paris]`. Times without
     * an offset are read in [zone].
     */
    fun parse(raw: String, argName: String, zone: ZoneId): TimeArg {
        var s = raw.trim()
        if (s.isEmpty()) throw ToolException("Argument '$argName' is empty; use ISO-8601 such as 2026-11-14T15:00.")
        if (DATE_ONLY.matches(s)) {
            return try {
                TimeArg.Date(LocalDate.parse(s))
            } catch (e: DateTimeParseException) {
                throw invalid(argName, raw)
            }
        }
        s = s.replace(DATE_SPACE_TIME, "$1T$2")
        if (s.length > 10 && (s[10] == 't')) s = s.substring(0, 10) + "T" + s.substring(11)
        if (s.endsWith("z")) s = s.dropLast(1) + "Z"
        return try {
            when {
                s.endsWith("]") -> TimeArg.DateTime(ZonedDateTime.parse(s, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant(), true)
                hasOffset(s) -> TimeArg.DateTime(OffsetDateTime.parse(normalizeOffset(s)).toInstant(), true)
                else -> TimeArg.DateTime(LocalDateTime.parse(s).atZone(zone).toInstant(), false)
            }
        } catch (e: DateTimeParseException) {
            throw invalid(argName, raw)
        }
    }

    private fun hasOffset(s: String): Boolean {
        val time = s.substringAfter('T', "")
        return time.endsWith("Z") || time.contains('+') || time.contains('-')
    }

    /** "+0200" and "+02" -> "+02:00" (java.time's ISO parser wants HH:MM). */
    private fun normalizeOffset(s: String): String {
        if (s.endsWith("Z")) return s
        val m = TRAILING_OFFSET.find(s) ?: return s
        val (sign, hours, minutes) = m.destructured
        return s.substring(0, m.range.first) + sign + hours + ":" + minutes.ifEmpty { "00" }
    }

    private fun invalid(argName: String, raw: String) = ToolException(
        "Argument '$argName' must be an ISO-8601 date or date-time such as 2026-11-14T15:00, " +
            "2026-11-14T15:00:00+02:00 or 2026-11-14; got '${truncate(raw, 60)}'.",
    )

    /** Start instant: midnight in [zone] for dates. */
    fun startInstant(arg: TimeArg, zone: ZoneId): Instant = when (arg) {
        is TimeArg.Date -> arg.date.atStartOfDay(zone).toInstant()
        is TimeArg.DateTime -> arg.instant
    }

    /** The calendar date of [arg] as seen in [zone]. */
    fun localDate(arg: TimeArg, zone: ZoneId): LocalDate = when (arg) {
        is TimeArg.Date -> arg.date
        is TimeArg.DateTime -> arg.instant.atZone(zone).toLocalDate()
    }

    /**
     * Query window for `calendar_events_list`. `from` defaults to [now]. A date-only `to` includes
     * that whole day. Without `to`, the window is [days] (default 7) calendar days from `from`.
     */
    fun listRange(from: TimeArg?, to: TimeArg?, days: Int?, now: Instant, zone: ZoneId): TimeRange {
        val start = from?.let { startInstant(it, zone) } ?: now
        val end = when {
            to is TimeArg.Date -> to.date.plusDays(1).atStartOfDay(zone).toInstant()
            to is TimeArg.DateTime -> to.instant
            else -> {
                val d = days ?: DEFAULT_DAYS
                if (d !in 1..MAX_RANGE_DAYS) throw ToolException("Argument 'days' must be between 1 and $MAX_RANGE_DAYS, got $d.")
                start.atZone(zone).plusDays(d.toLong()).toInstant()
            }
        }
        if (!end.isAfter(start)) throw ToolException("'to' (${format(end, zone)}) must be after 'from' (${format(start, zone)}).")
        // One extra day of slack for DST shifts and an inclusive date-only 'to'.
        if (Duration.between(start, end) > Duration.ofDays(MAX_RANGE_DAYS + 1)) {
            throw ToolException("The range is too long (at most $MAX_RANGE_DAYS days). Narrow 'from'/'to'.")
        }
        return TimeRange(start, end)
    }

    /** UTC-midnight millis CalendarContract uses for an all-day event boundary on [date]. */
    fun allDayUtcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The date an all-day boundary (UTC-midnight millis) stands for. */
    fun allDayDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate()

    /**
     * Real start/end of an event instance in [zone]. All-day instances are stored as UTC-midnight
     * boundaries; they span local midnight to local midnight of the same dates.
     */
    fun instanceWindow(beginMillis: Long, endMillis: Long, allDay: Boolean, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        if (!allDay) {
            return Instant.ofEpochMilli(beginMillis).atZone(zone) to Instant.ofEpochMilli(endMillis).atZone(zone)
        }
        val startDate = allDayDate(beginMillis)
        val endDate = allDayDate(endMillis).let { if (it.isAfter(startDate)) it else startDate.plusDays(1) }
        return startDate.atStartOfDay(zone) to endDate.atStartOfDay(zone)
    }

    /** Whether `[start, end)` overlaps [range]; zero-length events count when they start inside it. */
    fun overlaps(start: Instant, end: Instant, range: TimeRange): Boolean =
        if (!end.isAfter(start)) !start.isBefore(range.start) && start.isBefore(range.end)
        else start.isBefore(range.end) && end.isAfter(range.start)

    /** ISO-8601 with offset, whole seconds: 2026-11-14T15:00:00+02:00. */
    fun format(instant: Instant, zone: ZoneId): String = OUTPUT.format(instant.atZone(zone).truncatedTo(ChronoUnit.SECONDS))

    fun format(time: ZonedDateTime): String = OUTPUT.format(time.truncatedTo(ChronoUnit.SECONDS))

    /**
     * Plans `calendar_event_create`. [allDay] null means "all-day if [start] is a date". For
     * all-day events a date-only [end] is the last day (inclusive); a local-midnight date-time end
     * is exclusive (what `calendar_events_list` returns). Without [end], [durationMinutes]
     * (default 60; rounded up to whole days for all-day events) applies.
     */
    fun planEvent(start: TimeArg, end: TimeArg?, durationMinutes: Int?, allDay: Boolean?, zone: ZoneId): EventPlan {
        if (durationMinutes != null && durationMinutes !in 1..MAX_DURATION_MINUTES) {
            throw ToolException("Argument 'duration_minutes' must be between 1 and $MAX_DURATION_MINUTES, got $durationMinutes.")
        }
        val isAllDay = allDay ?: (start is TimeArg.Date)
        return if (isAllDay) planAllDay(start, end, durationMinutes, zone) else planTimed(start, end, durationMinutes, zone)
    }

    private fun planAllDay(start: TimeArg, end: TimeArg?, durationMinutes: Int?, zone: ZoneId): EventPlan {
        val first = localDate(start, zone)
        val endExclusive = when (end) {
            is TimeArg.Date -> end.date.plusDays(1)
            is TimeArg.DateTime -> {
                val local = end.instant.atZone(zone)
                if (local.toLocalTime() == LocalTime.MIDNIGHT && local.toLocalDate().isAfter(first)) local.toLocalDate()
                else local.toLocalDate().plusDays(1)
            }
            // Whole days, rounded up.
            null -> first.plusDays(((durationMinutes ?: MINUTES_PER_DAY) + MINUTES_PER_DAY - 1) / MINUTES_PER_DAY.toLong())
        }
        if (!endExclusive.isAfter(first)) {
            throw ToolException("'end' (${end?.let { localDate(it, zone) }}) must not be before 'start' ($first).")
        }
        return EventPlan(
            allDay = true,
            dtStartMillis = allDayUtcMillis(first),
            dtEndMillis = allDayUtcMillis(endExclusive),
            timeZoneId = "UTC",
            start = format(first.atStartOfDay(zone)),
            end = format(endExclusive.atStartOfDay(zone)),
        )
    }

    private fun planTimed(start: TimeArg, end: TimeArg?, durationMinutes: Int?, zone: ZoneId): EventPlan {
        val startInstant = startInstant(start, zone)
        val endInstant = when (end) {
            null -> startInstant.plus((durationMinutes ?: DEFAULT_DURATION_MINUTES).toLong(), ChronoUnit.MINUTES)
            else -> startInstant(end, zone)
        }
        if (!endInstant.isAfter(startInstant)) {
            throw ToolException("'end' (${format(endInstant, zone)}) must be after 'start' (${format(startInstant, zone)}).")
        }
        return EventPlan(
            allDay = false,
            dtStartMillis = startInstant.toEpochMilli(),
            dtEndMillis = endInstant.toEpochMilli(),
            timeZoneId = zone.id,
            start = format(startInstant, zone),
            end = format(endInstant, zone),
        )
    }
}
