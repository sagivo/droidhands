package dev.phonebridge.core.policy

import dev.phonebridge.core.auth.ActiveHours
import java.time.Instant
import java.time.ZoneId

/** Pure helpers for [ActiveHours] daily windows (phone-local time). */
object ActiveWindow {
    private const val MINUTES_PER_DAY = 24 * 60

    /** True if [hours] is usable: both ends in 0..1439 and start != end. */
    fun isValid(hours: ActiveHours): Boolean =
        hours.startMinute in 0 until MINUTES_PER_DAY && hours.endMinute in 0 until MINUTES_PER_DAY && hours.startMinute != hours.endMinute

    /**
     * True if [minuteOfDay] (0..1439) falls inside [hours]: start inclusive, end exclusive. A window with
     * start > end runs overnight (22:00-06:00 contains 23:30 and 05:59, not 06:00). Invalid windows
     * contain nothing (fail closed).
     */
    fun contains(hours: ActiveHours, minuteOfDay: Int): Boolean {
        if (!isValid(hours)) return false
        return if (hours.startMinute < hours.endMinute) {
            minuteOfDay >= hours.startMinute && minuteOfDay < hours.endMinute
        } else {
            minuteOfDay >= hours.startMinute || minuteOfDay < hours.endMinute
        }
    }

    /** Minutes after local midnight at [epochMillis] in [zone]. */
    fun minuteOfDay(epochMillis: Long, zone: ZoneId): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** "08:05" for 485. Out-of-range values are wrapped into a day. */
    fun format(minuteOfDay: Int): String {
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60)
    }

    /** "08:00-23:00". */
    fun format(hours: ActiveHours): String = "${format(hours.startMinute)}-${format(hours.endMinute)}"
}
