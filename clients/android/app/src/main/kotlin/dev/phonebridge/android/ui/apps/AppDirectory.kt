package dev.phonebridge.android.ui.apps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The installed apps and their actions as the UI shows them ([AppListings]), shared by the Apps tab, the
 * per-action rules and the names in the activity log and usage. Loading scans every app, so it runs in
 * [scope] (a background dispatcher), one load at a time, and [state] keeps the previous list while a
 * reload runs or when one fails. Thread-safe.
 */
class AppDirectory(
    private val load: suspend () -> List<AppListing>,
    private val scope: CoroutineScope,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _apps = MutableStateFlow<List<AppListing>?>(null)
    private val _loading = MutableStateFlow(false)

    @Volatile private var loadedAt = 0L

    /** The apps, sorted as the platform lists them (by label); null until the first load finished. */
    val apps: StateFlow<List<AppListing>?> = _apps.asStateFlow()

    /** True while a load runs. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Loads in the background, unless the last load finished less than [MAX_AGE_MS] ago. [force] reloads
     * anyway (after the user changed something the list shows, e.g. imported a plugin).
     */
    fun refresh(force: Boolean = false) {
        scope.launch { reload(force) }
    }

    /** [refresh], waiting for the result. */
    suspend fun reload(force: Boolean = false) {
        mutex.withLock {
            if (!force && _apps.value != null && clock() - loadedAt < MAX_AGE_MS) return
            _loading.value = true
            try {
                _apps.value = load()
                loadedAt = clock()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Loading the app list failed", e)
                if (_apps.value == null) _apps.value = emptyList()
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        /** Package changes are rare while the app is open; a reload this soon would only rescan the same apps. */
        const val MAX_AGE_MS = 30_000L
    }
}
