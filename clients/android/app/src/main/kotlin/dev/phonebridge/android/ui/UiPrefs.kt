package dev.phonebridge.android.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small UI-only state persisted across launches (not settings, no secrets): which runtime
 * permissions were requested at least once (to detect "don't ask again"), whether the
 * first-launch notification prompt was shown, and whether the onboarding wizard was finished or
 * skipped. All disk access happens on [Dispatchers.IO].
 */
internal class UiPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val lock = Any()

    suspend fun askedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        prefs.getStringSet(KEY_ASKED, null)?.toSet() ?: emptySet()
    }

    suspend fun markAsked(permissions: Collection<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_ASKED, null)?.toSet() ?: emptySet()
            if (!current.containsAll(permissions)) prefs.edit { putStringSet(KEY_ASKED, current + permissions) }
        }
    }

    /** True exactly once per install: the caller should show the first-launch prompt now. */
    suspend fun consumeFirstLaunchNotificationPrompt(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)) {
                false
            } else {
                prefs.edit { putBoolean(KEY_NOTIFICATION_PROMPTED, true) }
                true
            }
        }
    }

    suspend fun onboardingCompleted(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean(KEY_ONBOARDING_DONE, false) }

    /** Finished or skipped (true); false re-arms it (Settings > About > Run setup again). */
    suspend fun setOnboardingCompleted(done: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, done) }
    }

    private companion object {
        const val FILE = "phonebridge_ui"
        const val KEY_ASKED = "asked_permissions"
        const val KEY_NOTIFICATION_PROMPTED = "notification_prompted"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
