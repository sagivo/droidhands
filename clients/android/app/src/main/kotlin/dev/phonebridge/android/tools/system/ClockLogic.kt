package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import java.util.Calendar

/** Argument validation and formatting for `alarm_set` / `timer_set` (pure). */
object ClockLogic {
    const val MAX_TIMER_SECONDS = 86_400

    /** java.util.Calendar day constants in the order agents (and most of the world's calendars) list them. */
    private val WEEK = listOf(
        Calendar.MONDAY to "monday",
        Calendar.TUESDAY to "tuesday",
        Calendar.WEDNESDAY to "wednesday",
        Calendar.THURSDAY to "thursday",
        Calendar.FRIDAY to "friday",
        Calendar.SATURDAY to "saturday",
        Calendar.SUNDAY to "sunday",
    )

    private val GROUPS: Map<String, List<Int>> = mapOf(
        "weekdays" to listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
        "weekends" to listOf(Calendar.SATURDAY, Calendar.SUNDAY),
        "weekend" to listOf(Calendar.SATURDAY, Calendar.SUNDAY),
        "daily" to WEEK.map { it.first },
        "everyday" to WEEK.map { it.first },
        "every_day" to WEEK.map { it.first },
    )

    fun requireHour(hour: Int): Int {
        if (hour !in 0..23) throw ToolException("Argument 'hour' must be 0-23 (24-hour clock), got $hour.")
        return hour
    }

    fun requireMinute(minute: Int): Int {
        if (minute !in 0..59) throw ToolException("Argument 'minute' must be 0-59, got $minute.")
        return minute
    }

    fun requireTimerSeconds(seconds: Int): Int {
        if (seconds !in 1..MAX_TIMER_SECONDS) {
            throw ToolException("Argument 'seconds' must be between 1 and $MAX_TIMER_SECONDS (24 h), got $seconds.")
        }
        return seconds
    }

    /**
     * Parses `alarm_set.days` (`mon`..`sun`; any unambiguous prefix of an English day name of at
     * least two letters, plus `weekdays`/`weekends`/`daily`) into java.util.Calendar day constants
     * (the values AlarmClock.EXTRA_DAYS expects), distinct and ordered Monday..Sunday.
     */
    fun parseDays(raw: List<String>): List<Int> {
        val days = mutableSetOf<Int>()
        for (item in raw) {
            val key = normalizeEnumArg(item).trimEnd('.')
            if (key.isEmpty()) continue
            val group = GROUPS[key]
            if (group != null) {
                days += group
                continue
            }
            val match = WEEK.filter { key.length >= 2 && it.second.startsWith(key) }
            if (match.size != 1) {
                throw ToolException("Argument 'days' must contain day names mon, tue, wed, thu, fri, sat, sun; got '$item'.")
            }
            days += match[0].first
        }
        return WEEK.map { it.first }.filter { it in days }
    }

    /** Calendar day constant -> "mon".."sun". */
    fun dayName(calendarDay: Int): String =
        WEEK.firstOrNull { it.first == calendarDay }?.second?.take(3) ?: calendarDay.toString()

    fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    /** "07:30 on mon, wed 'Gym'" style summary. */
    fun describeAlarm(hour: Int, minute: Int, days: List<Int>, label: String?): String = buildString {
        append(formatTime(hour, minute))
        if (days.isNotEmpty()) append(" every ").append(days.joinToString(", ") { dayName(it) })
        if (!label.isNullOrBlank()) append(" ").append(quoteForSummary(label, 60))
    }

    /** 5400 -> "1h 30m", 45 -> "45s". */
    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "${it}h" },
            m.takeIf { it > 0 }?.let { "${it}m" },
            s.takeIf { it > 0 || seconds == 0 }?.let { "${it}s" },
        ).joinToString(" ")
    }
}
