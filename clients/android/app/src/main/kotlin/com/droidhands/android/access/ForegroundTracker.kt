package com.droidhands.android.access

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the foreground app from `TYPE_WINDOW_STATE_CHANGED` events (pure, thread-safe).
 *
 * Events from DroidHands itself, System UI (shade, keyguard, volume dialog) and keyboards are
 * ignored, so the tracked package is the app the user (or agent) is working in. Window classes
 * are not always activities (dialogs, popups), so the activity is resolved lazily, off the main
 * thread, by checking recent classes against the package manager ([activity]).
 */
class ForegroundTracker {
    private val lock = Any()
    private var currentPackage: String? = null
    private val recentClasses = ArrayDeque<String>()
    private val stateChangeTimes = HashMap<String, Long>()
    private val activityCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var ignored: Set<String> = setOf(SYSTEM_UI)

    /** Package currently in the foreground, or null if unknown. */
    val packageName: String? get() = synchronized(lock) { currentPackage }

    /** Sets the packages to ignore besides System UI: our own package and the installed keyboards. */
    fun configure(ownPackage: String, imePackages: Collection<String>) {
        ignored = buildSet {
            add(SYSTEM_UI)
            add(ownPackage)
            addAll(imePackages)
        }
    }

    /** Whether a window event from [pkg]/[cls] should change the tracked foreground app. */
    fun shouldTrack(pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg in ignored) return false
        if (cls != null && IME_CLASS_PREFIXES.any { cls.startsWith(it) }) return false
        return true
    }

    /** Feeds a window-state change. Returns true if it became (or stayed) the tracked foreground. */
    fun onWindowStateChanged(pkg: String?, cls: String?, uptimeMs: Long): Boolean = synchronized(lock) {
        if (!pkg.isNullOrBlank()) {
            if (stateChangeTimes.size > MAX_PACKAGES) stateChangeTimes.clear()
            stateChangeTimes[pkg] = uptimeMs
        }
        if (!shouldTrack(pkg, cls)) return false
        if (pkg != currentPackage) {
            currentPackage = pkg
            recentClasses.clear()
        }
        if (!cls.isNullOrBlank()) {
            recentClasses.remove(cls)
            recentClasses.addLast(cls)
            while (recentClasses.size > MAX_CLASSES) recentClasses.removeFirst()
        }
        true
    }

    /** Seeds the package when no event was seen yet (e.g. right after the service connects). */
    fun seed(pkg: String?) = synchronized(lock) {
        if (currentPackage == null && shouldTrack(pkg, null)) currentPackage = pkg
    }

    /** Uptime of the last window-state change from [pkg] (tracked or not), or null. */
    fun lastStateChangeAt(pkg: String): Long? = synchronized(lock) { stateChangeTimes[pkg] }

    /**
     * Most recent window class of the foreground package that [isActivity] confirms is an activity
     * (results are cached). May do blocking IPC through [isActivity]: call off the main thread.
     */
    fun activity(isActivity: (pkg: String, cls: String) -> Boolean): String? {
        val (pkg, classes) = synchronized(lock) { (currentPackage ?: return null) to recentClasses.toList() }
        return classes.asReversed().firstOrNull { cls ->
            activityCache.getOrPut("$pkg/$cls") { isActivity(pkg, cls) }
        }
    }

    /** Forgets the foreground app (service disconnected). */
    fun reset() = synchronized(lock) {
        currentPackage = null
        recentClasses.clear()
        stateChangeTimes.clear()
    }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_CLASSES = 8
        private const val MAX_PACKAGES = 64
        private val IME_CLASS_PREFIXES = listOf("android.inputmethodservice.")
    }
}
