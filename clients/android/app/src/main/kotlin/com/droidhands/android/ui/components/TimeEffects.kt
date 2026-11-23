package com.droidhands.android.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.droidhands.android.ui.format.TimeFormat
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Wall-clock millis that ticks every [intervalMs] while the screen is at least STARTED. */
@Composable
fun rememberNow(intervalMs: Long = 30_000): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle, intervalMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(intervalMs)
            }
        }
    }
    return now
}

/**
 * Formatter `(timestamp, now) -> text` honoring the locale and the user's 12/24-hour setting:
 * time for today ("14:03"), "Nov 12, 14:03" this year, and with the year otherwise.
 */
@Composable
fun rememberTimestampFormatter(): (Long, Long) -> String {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    return remember(locale, is24h) {
        val hour = if (is24h) "Hm" else "hma"
        val today = pattern(locale, hour) ?: DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val thisYear = pattern(locale, "MMMd$hour") ?: DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        val older = pattern(locale, "yMMMd$hour") ?: DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        val formatter: (Long, Long) -> String = { ts, now ->
            val zone = ZoneId.systemDefault()
            val time = Instant.ofEpochMilli(ts).atZone(zone)
            when (TimeFormat.bucket(ts, now, zone)) {
                TimeFormat.Bucket.TODAY -> today
                TimeFormat.Bucket.THIS_YEAR -> thisYear
                TimeFormat.Bucket.OLDER -> older
            }.format(time)
        }
        formatter
    }
}

/**
 * Formatter for a time of day ("14:03:27" / "2:03:27 PM", or "14:03" / "2:03 PM" without [seconds]),
 * honoring the 12/24-hour setting.
 */
@Composable
fun rememberTimeOfDayFormatter(seconds: Boolean = true): (Long) -> String {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    return remember(locale, is24h, seconds) {
        val skeleton = (if (is24h) "Hm" else "hm") + (if (seconds) "s" else "") + (if (is24h) "" else "a")
        val formatter = pattern(locale, skeleton)
            ?: DateTimeFormatter.ofLocalizedTime(if (seconds) FormatStyle.MEDIUM else FormatStyle.SHORT)
        val format: (Long) -> String = { ts -> formatter.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())) }
        format
    }
}

/**
 * Formatter for a calendar day, e.g. "Mon, Nov 10" (with the year when it isn't the current
 * one) in the user's locale.
 */
@Composable
fun rememberDayFormatter(): (LocalDate) -> String {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(locale) {
        val thisYear = pattern(locale, "EEEMMMd") ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val older = pattern(locale, "yMMMd") ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val format: (LocalDate) -> String = { date ->
            (if (date.year == LocalDate.now().year) thisYear else older).format(date)
        }
        format
    }
}

/** Short day label for chart axes and summaries, e.g. "Nov 10". */
@Composable
fun rememberShortDayFormatter(): (LocalDate) -> String {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(locale) {
        val formatter = pattern(locale, "MMMd") ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        val format: (LocalDate) -> String = { date -> formatter.format(date) }
        format
    }
}

/** ICU best pattern for [skeleton] as a java.time formatter, or null if java.time can't parse it. */
private fun pattern(locale: Locale, skeleton: String): DateTimeFormatter? = try {
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
} catch (_: IllegalArgumentException) {
    null
}
