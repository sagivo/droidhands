package dev.phonebridge.android.ui.format

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Android-free time formatting shared by the screens (unit-tested). */
object TimeFormat {
    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    /** Which calendar range a timestamp falls in, relative to "now" (picks the date format). */
    enum class Bucket { TODAY, THIS_YEAR, OLDER }

    /**
     * Coarse relative phrase for [then] as seen from [now]: "just now", "5 min ago", "3 h ago",
     * "2 days ago", "in 6 days", "in 3 months". Values are truncated, never rounded up.
     */
    fun relative(then: Long, now: Long): String {
        val delta = now - then
        val past = delta >= 0
        val d = abs(delta)
        if (d < MINUTE) return if (past) "just now" else "in under a minute"
        val phrase = when {
            d < HOUR -> "${d / MINUTE} min"
            d < DAY -> "${d / HOUR} h"
            d < MONTH -> plural(d / DAY, "day")
            d < YEAR -> plural(d / MONTH, "month")
            else -> plural(d / YEAR, "year")
        }
        return if (past) "$phrase ago" else "in $phrase"
    }

    /** Human duration: "850 ms", "1.2 s", "2 min 5 s". Negative values render as "–". */
    fun duration(ms: Long, locale: Locale = Locale.getDefault()): String {
        if (ms < 0) return "–"
        if (ms < SECOND) return "$ms ms"
        val tenths = (ms + 50) / 100
        if (tenths < 600) return String.format(locale, "%.1f s", tenths / 10.0)
        val totalSeconds = (ms + 500) / SECOND
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (seconds == 0L) "$minutes min" else "$minutes min $seconds s"
    }

    /** Calendar bucket of [timestamp] relative to [now] in [zone]. */
    fun bucket(timestamp: Long, now: Long, zone: ZoneId): Bucket {
        val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> Bucket.TODAY
            day.year == today.year -> Bucket.THIS_YEAR
            else -> Bucket.OLDER
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
