package dev.phonebridge.android.ui.agents

import dev.phonebridge.core.auth.ActiveHours
import java.util.Locale

/** Pure formatting and checks for an agent's daily active-hours window (unit-tested). */
object Schedule {
    const val MINUTES_PER_DAY = 24 * 60

    /** Suggested window when the user turns active hours on: 08:00–23:00. */
    val DEFAULT = ActiveHours(startMinute = 8 * 60, endMinute = 23 * 60)

    /** "08:00" (24-hour) or "8:00 AM" (12-hour) for minutes after midnight. */
    fun time(minuteOfDay: Int, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        val hour = m / 60
        val minute = m % 60
        if (is24Hour) return String.format(locale, "%02d:%02d", hour, minute)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(locale, "%d:%02d %s", h12, minute, if (hour < 12) "AM" else "PM")
    }

    /** True for windows that cross midnight (22:00–06:00). */
    fun isOvernight(hours: ActiveHours): Boolean = hours.startMinute > hours.endMinute

    /** "08:00–23:00", or "22:00–06:00 (overnight)". */
    fun range(hours: ActiveHours, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val text = "${time(hours.startMinute, is24Hour, locale)}–${time(hours.endMinute, is24Hour, locale)}"
        return if (isOvernight(hours)) "$text (overnight)" else text
    }

    /** "Any time" when there is no window. */
    fun describe(hours: ActiveHours?, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String =
        if (hours == null) "Any time" else range(hours, is24Hour, locale)

    /** Whether [minuteOfDay] falls inside [hours] (start inclusive, end exclusive). Null = always. */
    fun isWithin(hours: ActiveHours?, minuteOfDay: Int): Boolean {
        if (hours == null) return true
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return if (hours.startMinute < hours.endMinute) {
            m >= hours.startMinute && m < hours.endMinute
        } else {
            m >= hours.startMinute || m < hours.endMinute
        }
    }

    /** User-facing problem with a window, or null if it is valid. */
    fun error(hours: ActiveHours?): String? {
        if (hours == null) return null
        val range = 0 until MINUTES_PER_DAY
        return when {
            hours.startMinute !in range || hours.endMinute !in range -> "Pick times between 00:00 and 23:59."
            hours.startMinute == hours.endMinute -> "Start and end can't be the same time. Turn active hours off to allow any time."
            else -> null
        }
    }

    /** Length of the window, e.g. "15 h" or "8 h 30 min". */
    fun length(hours: ActiveHours): String {
        val minutes = Math.floorMod(hours.endMinute - hours.startMinute, MINUTES_PER_DAY)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }
}
