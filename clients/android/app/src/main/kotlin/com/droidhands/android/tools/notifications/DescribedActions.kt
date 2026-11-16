package com.droidhands.android.tools.notifications

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which action title `describeCall` showed in the approval prompt for recent
 * `notification_action` calls, so the call can refuse to run if the same arguments now resolve to
 * a different button (the app re-posted the notification with other actions while the user was
 * deciding). Bounded and time-limited; thread-safe. Pure (injectable clock).
 */
class DescribedActions(
    private val ttlMs: Long = 5 * 60_000L,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val actionTitle: String, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Records that a call with these arguments was described as tapping [actionTitle]. */
    fun remember(key: String, action: String?, index: Int?, actionTitle: String) {
        val now = clock()
        entries.entries.removeIf { now - it.value.at > ttlMs }
        if (entries.size >= maxEntries) entries.keys.firstOrNull()?.let(entries::remove)
        entries[argsKey(key, action, index)] = Entry(actionTitle, now)
    }

    /** The title shown for these arguments (consumed), or null if none was recorded recently. */
    fun take(key: String, action: String?, index: Int?): String? {
        val entry = entries.remove(argsKey(key, action, index)) ?: return null
        return entry.actionTitle.takeIf { clock() - entry.at <= ttlMs }
    }

    private fun argsKey(key: String, action: String?, index: Int?): String =
        "$key\u0000${action?.trim()?.lowercase(Locale.ROOT).orEmpty()}\u0000${index ?: ""}"
}
