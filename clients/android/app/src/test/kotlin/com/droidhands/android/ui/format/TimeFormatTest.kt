package com.droidhands.android.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TimeFormatTest {
    private val now = 1_731_500_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun relativePast() {
        assertEquals("just now", TimeFormat.relative(now - 10_000, now))
        assertEquals("just now", TimeFormat.relative(now, now))
        assertEquals("1 min ago", TimeFormat.relative(now - min, now))
        assertEquals("59 min ago", TimeFormat.relative(now - 59 * min - 59_000, now))
        assertEquals("3 h ago", TimeFormat.relative(now - 3 * hour - 5 * min, now))
        assertEquals("1 day ago", TimeFormat.relative(now - day, now))
        assertEquals("29 days ago", TimeFormat.relative(now - 29 * day, now))
        assertEquals("2 months ago", TimeFormat.relative(now - 61 * day, now))
        assertEquals("1 year ago", TimeFormat.relative(now - 400 * day, now))
        assertEquals("3 years ago", TimeFormat.relative(now - 3 * 365 * day, now))
    }

    @Test
    fun relativeFuture() {
        assertEquals("in under a minute", TimeFormat.relative(now + 30_000, now))
        assertEquals("in 5 min", TimeFormat.relative(now + 5 * min, now))
        assertEquals("in 23 h", TimeFormat.relative(now + 23 * hour + 59 * min, now))
        assertEquals("in 1 day", TimeFormat.relative(now + day, now))
        assertEquals("in 6 days", TimeFormat.relative(now + 6 * day + hour, now))
        // Months are 30-day units.
        assertEquals("in 3 months", TimeFormat.relative(now + 90 * day, now))
    }

    @Test
    fun duration() {
        assertEquals("0 ms", TimeFormat.duration(0, Locale.US))
        assertEquals("999 ms", TimeFormat.duration(999, Locale.US))
        assertEquals("1.0 s", TimeFormat.duration(1_000, Locale.US))
        assertEquals("1.2 s", TimeFormat.duration(1_234, Locale.US))
        assertEquals("59.9 s", TimeFormat.duration(59_900, Locale.US))
        assertEquals("1 min", TimeFormat.duration(59_960, Locale.US))
        assertEquals("2 min 5 s", TimeFormat.duration(125_000, Locale.US))
        assertEquals("–", TimeFormat.duration(-1, Locale.US))
        assertEquals("1,5 s", TimeFormat.duration(1_500, Locale.GERMANY))
    }

    @Test
    fun bucket() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val ref = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val earlierToday = ZonedDateTime.of(2026, 11, 13, 0, 5, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 11, 12, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val lastYear = ZonedDateTime.of(2025, 12, 31, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(TimeFormat.Bucket.TODAY, TimeFormat.bucket(earlierToday, ref, zone))
        assertEquals(TimeFormat.Bucket.THIS_YEAR, TimeFormat.bucket(yesterday, ref, zone))
        assertEquals(TimeFormat.Bucket.OLDER, TimeFormat.bucket(lastYear, ref, zone))
    }
}
