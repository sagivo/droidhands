package dev.phonebridge.android.ui.components

import android.text.format.DateFormat
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.phonebridge.android.ui.format.TimeFormat
import kotlinx.coroutines.delay
import java.time.Instant
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
 * time with seconds for today, "Nov 12, 14:03" this year, and with the year otherwise.
 */
@Composable
fun rememberTimestampFormatter(): (Long, Long) -> String {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    return remember(locale, is24h) {
        val hour = if (is24h) "Hm" else "hma"
        val today = pattern(locale, if (is24h) "Hms" else "hmsa") ?: DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
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

/** ICU best pattern for [skeleton] as a java.time formatter, or null if java.time can't parse it. */
private fun pattern(locale: Locale, skeleton: String): DateTimeFormatter? = try {
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Sets FLAG_SECURE on the window while in composition: no screenshots, screen recording or
 * Recents thumbnail of secrets (the freshly created token).
 */
@Composable
fun SecureWindowEffect() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
