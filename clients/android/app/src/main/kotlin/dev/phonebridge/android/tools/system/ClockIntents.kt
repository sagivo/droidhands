package dev.phonebridge.android.tools.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import dev.phonebridge.android.tools.support.Launcher
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alarms and timers through the clock app's public AlarmClock intents (no UI shown). */
internal object ClockIntents {
    fun alarmIntent(hour: Int, minute: Int, days: List<Int>, label: String?): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

    fun timerIntent(seconds: Int, label: String?): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }

    /** For unavailableReason: null if a clock app accepts [action] (AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER). */
    fun unavailableReason(context: Context, action: String): String? = runCatching {
        if (context.packageManager.resolveActivityCompat(Intent(action), PackageManager.MATCH_DEFAULT_ONLY) == null) noClockApp(action) else null
    }.getOrNull()

    /**
     * Hands [intent] to the clock app and returns its package, or null when several clock apps are
     * installed without a default, so Android shows a chooser and nothing is set until the user
     * picks one. Throws [ToolException] if no clock app handles it.
     */
    suspend fun send(context: Context, intent: Intent): String? {
        val handler = withContext(Dispatchers.IO) {
            context.packageManager.resolveActivityCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }?.activityInfo ?: throw ToolException(noClockApp(intent.action))
        Launcher.startActivity(context, intent)
        return handler.packageName.takeUnless { LaunchTargetPolicy.isChooser(LaunchHandler(it, handler.name), emptyList()) }
    }

    private fun noClockApp(action: String?) =
        "No clock app on the phone accepts ${if (action == AlarmClock.ACTION_SET_TIMER) "timers" else "alarms"}. " +
            "Ask the user to install or enable a clock app."
}
