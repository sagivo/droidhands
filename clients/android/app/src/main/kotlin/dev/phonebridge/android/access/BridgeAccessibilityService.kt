package dev.phonebridge.android.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A lightweight copy of an accessibility event (events themselves are recycled by the system). */
data class UiEvent(val type: Int, val packageName: String?, val className: String?, val uptimeMs: Long)

/** An on-screen window and its root node. Lists are ordered primary window first, then by z-order. */
class WindowRoot(
    val root: AccessibilityNodeInfo,
    val kind: WindowKind,
    val title: String?,
    val packageName: String?,
    val bounds: Bounds,
    val primary: Boolean,
    /** Position in the system's z-order, 0 = top-most (for hit-testing). */
    val zIndex: Int,
)

/**
 * An on-screen window as a touch sees it. [root] is null when Android withholds the window's content from this
 * service (e.g. accessibility-data-sensitive windows on Android 14+, which include PhoneBridge's approval screens).
 */
class TouchWindow(val kind: WindowKind, val bounds: Bounds, val root: AccessibilityNodeInfo?)

/**
 * Generic UI automation for any app (docs/TOOLS.md "Screen"): reads window trees, performs
 * gestures, global actions and screenshots. Being bound by the system, it also exempts PhoneBridge
 * from background-activity-launch limits (see `Launcher`). It also watches its own health
 * ([health], [AccessibilityHealth]): after an app update Android may keep it bound but deliver nothing.
 *
 * Node reads and node actions are blocking IPC: call them from Dispatchers.IO. Gestures,
 * global actions and screenshots are dispatched on the main thread by the helpers below.
 */
class BridgeAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitor = HealthMonitor(this, healthState)

    override fun onServiceConnected() {
        super.onServiceConnected()
        tracker.configure(packageName, emptyList())
        healthState.reset()
        instance = this
        monitor.start(scope)
        scope.launch(Dispatchers.IO) {
            val imes = try {
                getSystemService(InputMethodManager::class.java)?.inputMethodList?.map { it.packageName }.orEmpty()
            } catch (e: RuntimeException) {
                emptyList()
            }
            tracker.configure(packageName, imes)
            keyboardPackages = imes.toSet()
            tracker.seed(runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.uptimeMillis()
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) tracker.onWindowStateChanged(pkg, cls, now)
        healthState.onEvent()
        lastEvent.value = now
        eventFlow.tryEmit(UiEvent(event.eventType, pkg, cls, now))
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        monitor.stop()
        if (instance === this) {
            instance = null
            tracker.reset()
        }
    }

    /**
     * Interactive windows on the default display with their roots, primary (active app) window
     * first, then the rest top-most first. Falls back to [getRootInActiveWindow]. Blocking IPC.
     */
    fun rootNodes(): List<WindowRoot> {
        val candidates = windowsWithRoots().mapNotNull { (w, root) ->
            root ?: return@mapNotNull null
            Triple(w, root, WindowFacts(kindOf(w.type), w.isActive, w.isFocused, boundsOf(w)))
        }
        if (candidates.isEmpty()) {
            val (root, bounds) = activeRoot() ?: return emptyList()
            return listOf(WindowRoot(root, WindowKind.APPLICATION, null, root.packageName?.toString(), bounds, primary = true, zIndex = 0))
        }
        val primary = WindowSelection.primaryIndex(candidates.map { it.third })
        val all = candidates.mapIndexed { z, (w, root, facts) ->
            WindowRoot(root, facts.kind, w.title?.toString(), root.packageName?.toString(), facts.bounds, primary = z == primary, zIndex = z)
        }
        return listOf(all[primary]) + all.filterIndexed { z, _ -> z != primary }
    }

    /**
     * Every window on the default display, top-most first, including those whose content Android withholds from this
     * service (null root), for fail-closed hit-testing. Falls back to [getRootInActiveWindow]. Blocking IPC.
     */
    fun touchWindows(): List<TouchWindow> {
        val all = windowsWithRoots().map { (w, root) -> TouchWindow(kindOf(w.type), boundsOf(w), root) }
        if (all.isNotEmpty()) return all
        val (root, bounds) = activeRoot() ?: return emptyList()
        return listOf(TouchWindow(WindowKind.APPLICATION, bounds, root))
    }

    /** The windows (top-most first) with their roots; a root is null when unavailable. Blocking IPC. */
    private fun windowsWithRoots(): List<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo?>> {
        val infos: List<AccessibilityWindowInfo> = try {
            windows.orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
        return infos.map { w -> w to try { w.root } catch (e: RuntimeException) { null } }
    }

    private fun activeRoot(): Pair<AccessibilityNodeInfo, Bounds>? {
        val root = try { rootInActiveWindow } catch (e: RuntimeException) { null } ?: return null
        val rect = Rect().also(root::getBoundsInScreen)
        return root to Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun boundsOf(w: AccessibilityWindowInfo): Bounds {
        val rect = Rect().also(w::getBoundsInScreen)
        return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    /**
     * [rootNodes], but when the phone is usable (screen on, unlocked) and no window shows up for about a
     * second, throws a [ToolException] saying the service is stuck (see [AccessibilityHealth]). Blocking IPC
     * between short delays: call from Dispatchers.IO.
     */
    suspend fun rootNodesOrStuck(): List<WindowRoot> {
        val first = rootNodes()
        monitor.record(empty = first.isEmpty())
        if (first.isNotEmpty() || !monitor.usable()) return first
        val deadline = SystemClock.uptimeMillis() + HealthMonitor.CONFIRM_MS + STUCK_PROBE_MS
        while (SystemClock.uptimeMillis() < deadline) {
            delay(STUCK_PROBE_MS)
            val again = rootNodes()
            monitor.record(empty = again.isEmpty())
            if (again.isNotEmpty()) return again
        }
        monitor.cached()?.let { throw ToolException(it) }
        return emptyList()
    }

    /** Full display size in pixels (current rotation). */
    fun screenBounds(): Bounds {
        val r = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Bounds(r.left, r.top, r.right, r.bottom)
    }

    /**
     * Taps at screen pixel ([x], [y]). Returns false if the system cancelled the gesture. Like every gesture, refused
     * (ToolException) while a PhoneBridge approval is pending or shown.
     */
    suspend fun tap(x: Int, y: Int): Boolean = dispatchStroke(pointPath(x, y), TAP_MS)

    /** Touches and holds at ([x], [y]) for longer than the user's long-press timeout. */
    suspend fun longPress(x: Int, y: Int): Boolean =
        dispatchStroke(pointPath(x, y), maxOf(LONG_PRESS_MS, ViewConfiguration.getLongPressTimeout() + 300L))

    /** Drags from ([x1], [y1]) to ([x2], [y2]) over [durationMs]. */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val screen = screenBounds()
        val path = Path().apply {
            moveTo(clampX(x1, screen), clampY(y1, screen))
            lineTo(clampX(x2, screen), clampY(y2, screen))
        }
        return dispatchStroke(path, durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration()))
    }

    /** Performs a `GLOBAL_ACTION_*` (back, home, ...). Returns the system's result. */
    suspend fun performGlobal(action: Int): Boolean = withContext(Dispatchers.Main) { performGlobalAction(action) }

    /**
     * Screenshot of the default display as a software ARGB_8888 bitmap (caller recycles it).
     * Retries when Android's screenshot rate limit is hit. Throws [ScreenshotException].
     */
    suspend fun takeScreenshotBitmap(): Bitmap = Screenshots.capture(this)

    /** Most recent activity class of the foreground app, if known. Blocking (package manager). */
    fun currentActivity(): String? = tracker.activity { pkg, cls -> isActivity(packageManager, pkg, cls) }

    private fun pointPath(x: Int, y: Int): Path {
        val screen = screenBounds()
        return Path().apply { moveTo(clampX(x, screen), clampY(y, screen)) }
    }

    /** Throws [ToolException] instead of dispatching while a PhoneBridge approval is pending or shown ([SelfProtection]). */
    private suspend fun dispatchStroke(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return withContext(Dispatchers.Main) {
            // Checked on the main thread right before dispatching, so no approval can appear in between unnoticed.
            SelfProtection.requireNoPendingApproval()
            withTimeoutOrNull(durationMs + GESTURE_GRACE_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    if (!dispatchGesture(gesture, callback, null)) cont.resume(false)
                }
            } ?: false
        }
    }

    companion object {
        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 600L
        private const val GESTURE_GRACE_MS = 3_000L
        private const val STUCK_PROBE_MS = 200L

        private val healthState = AccessibilityHealth()

        /** Connected service instance, or null when accessibility is off / not yet bound. */
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        private val tracker = ForegroundTracker()
        private val lastEvent = MutableStateFlow(0L)
        private val eventFlow = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Every accessibility event received (copied), for event-driven waiting. Drops oldest on overflow. */
        val events: SharedFlow<UiEvent> = eventFlow.asSharedFlow()

        /** `SystemClock.uptimeMillis()` of the latest accessibility event (0 if none). */
        val lastEventAt: StateFlow<Long> = lastEvent.asStateFlow()

        /** Whether the user enabled this service in system settings (may be enabled but not yet bound). */
        fun isEnabled(context: Context): Boolean {
            val setting = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (e: RuntimeException) {
                null
            }
            return EnabledServices.contains(setting, context.packageName, BridgeAccessibilityService::class.java.name)
        }

        /** Packages of the installed keyboards (known once the service connected). */
        @Volatile
        var keyboardPackages: Set<String> = emptySet()
            private set

        /**
         * Status for `bridge_capabilities`: null when the service is connected and working, otherwise an
         * agent-facing explanation (not running, or connected but stuck, e.g. right after an app update). Off the
         * main thread it probes the window list and may block about a second to confirm a problem.
         */
        fun health(): String? {
            val service = instance ?: return AccessibilityHealth.NOT_RUNNING_MESSAGE
            return service.monitor.check()
        }

        /** Like [health] but only from what is already known (cheap; for `unavailableReason`). Null when not connected. */
        fun healthCached(): String? = instance?.monitor?.cached()

        /**
         * Tells the health check that the UI should change now (PhoneBridge is launching an app): if no
         * accessibility event follows within a few seconds, the service is reported as stuck.
         */
        fun expectUiChange() {
            val service = instance ?: return
            healthState.onExpectedChange(SystemClock.uptimeMillis(), service.monitor.usable())
        }

        /** Package currently in the foreground (tracked from window events), or null if unknown. */
        val foregroundPackage: String? get() = if (instance != null) tracker.packageName else null

        /** Uptime of the latest window-state change from [pkg] (e.g. an activity of it appeared), or null. */
        fun lastWindowStateChangeAt(pkg: String): Long? = tracker.lastStateChangeAt(pkg)

        /**
         * Waits until the UI looks settled: at least [minDelayMs], then until no event arrived for
         * [quietMs], giving up after [maxMs] in total (animations, video).
         */
        suspend fun awaitIdle(minDelayMs: Long = 350, quietMs: Long = 250, maxMs: Long = 1_500) {
            val start = SystemClock.uptimeMillis()
            delay(minDelayMs)
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietFor = now - lastEvent.value
                val left = maxMs - (now - start)
                if (quietFor >= quietMs || left <= 0) return
                delay(minOf(quietMs - quietFor, left).coerceAtLeast(16))
            }
        }

        /** Suspends until any accessibility event newer than [sinceUptime] arrives or [timeoutMs] passes. */
        suspend fun awaitEvent(sinceUptime: Long, timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { lastEvent.first { it > sinceUptime } } != null

        private fun kindOf(type: Int): WindowKind = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.OVERLAY
            else -> WindowKind.OTHER
        }

        private fun isActivity(pm: PackageManager, pkg: String, cls: String): Boolean = try {
            val component = ComponentName(pkg, cls)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getActivityInfo(component, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: RuntimeException) {
            false
        }

        private fun clampX(x: Int, screen: Bounds): Float = x.coerceIn(0, (screen.width - 1).coerceAtLeast(0)).toFloat()

        private fun clampY(y: Int, screen: Bounds): Float = y.coerceIn(0, (screen.height - 1).coerceAtLeast(0)).toFloat()
    }
}
