package com.droidhands.android.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app update check. DroidHands is installed from an APK, so no app store tells the user
 * about new versions: at most once a day this asks GitHub for the latest release of [repo] and,
 * when it is newer than [currentVersion], [available] holds its version for the Home card. The
 * request is anonymous (no token, account or phone data); nothing is downloaded or installed
 * automatically. Off when the build names no repository; the caller also skips it while the user
 * has "Check for updates" off.
 *
 * State (last attempt, latest version seen, dismissed version) lives in the app-private
 * `droidhands_updates` preferences. Thread-safe.
 */
class UpdateChecker(context: Context, private val repo: String, private val currentVersion: String) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val lock = Any()
    private var checking = false
    private val _available = MutableStateFlow<String?>(null)

    /** True when this build names a GitHub repository to check (builds made by the release workflow). */
    val isSupported: Boolean = UpdateLogic.isValidRepo(repo)

    /** Where the Download button goes: the latest release's APK on GitHub. Null when unsupported. */
    val downloadUrl: String? = if (isSupported) UpdateLogic.downloadUrl(repo) else null

    /** A newer release the user hasn't dismissed, e.g. "1.3.0"; null when there is none (or not known yet). */
    val available: StateFlow<String?> = _available.asStateFlow()

    /**
     * Publishes what earlier checks found, then asks GitHub if the last attempt is a day old. An
     * attempt counts even if it fails, so an offline phone isn't retried before tomorrow. Never
     * throws. Blocking network I/O (short timeouts): call from a background thread.
     */
    @WorkerThread
    fun refresh(now: Long = System.currentTimeMillis()) {
        if (!isSupported) return
        synchronized(lock) {
            if (checking) return
            checking = true
        }
        try {
            publish()
            if (!UpdateLogic.isDue(now, prefs.getLong(KEY_LAST_CHECK, 0L))) return
            prefs.edit { putLong(KEY_LAST_CHECK, now) }
            val latest = fetchLatest() ?: return
            prefs.edit { putString(KEY_LATEST, latest) }
            publish()
        } catch (e: Exception) {
            // Class name only: no URLs or response bodies in the log.
            Log.w(TAG, "Update check failed: ${e.javaClass.name}")
        } finally {
            synchronized(lock) { checking = false }
        }
    }

    /** Hides the card for [version]; a later release shows it again. Disk write: call from a background thread. */
    @WorkerThread
    fun dismiss(version: String) {
        prefs.edit { putString(KEY_DISMISSED, version) }
        publish()
    }

    private fun publish() {
        _available.value = UpdateLogic.availableUpdate(prefs.getString(KEY_LATEST, null), currentVersion, prefs.getString(KEY_DISMISSED, null))
    }

    /** The latest release's version ("1.3.0"), or null when there is none or GitHub didn't answer usefully. */
    private fun fetchLatest(): String? {
        val connection = URL(UpdateLogic.latestReleaseApiUrl(repo)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "DroidHands-Android/$currentVersion")
            // 404: the repository has no published release yet. 403/429: rate limited; try tomorrow.
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use(::readBounded)
            UpdateLogic.parseLatestRelease(body)?.toString()
        } finally {
            connection.disconnect()
        }
    }

    /** The UTF-8 body; throws [IOException] beyond [MAX_BODY_BYTES] (a release object is a few KB). */
    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BODY_BYTES) throw IOException("Release response too large")
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TAG = "DroidHands/Updates"
        const val FILE = "droidhands_updates"
        const val KEY_LAST_CHECK = "last_check_at"
        const val KEY_LATEST = "latest_version"
        const val KEY_DISMISSED = "dismissed_version"
        const val TIMEOUT_MS = 5_000
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
