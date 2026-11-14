package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.Bounds
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowSelection
import dev.phonebridge.android.access.WindowSnapshot
import dev.phonebridge.android.tools.support.DeviceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One capture of everything an agent may see on screen. Windows are primary first. */
class ScreenState(
    val windows: List<WindowSnapshot>,
    val packageName: String?,
    val activity: String?,
    val locked: Boolean,
    val screenOn: Boolean,
) {
    /** Roots of the readable windows (PhoneBridge's own windows excluded). */
    val roots: List<UiNode> get() = WindowSelection.roots(windows)

    /** Root of the primary window, if readable. */
    val primaryRoot: UiNode? get() = windows.firstOrNull { it.primary && !it.hidden }?.root
}

/** Captures window trees into [ScreenState]s, withholding PhoneBridge's own windows. */
object ScreenCapture {
    /**
     * Captures the windows screen_read shows ([includeAll] adds keyboards and system bars).
     * Blocking IPC runs on Dispatchers.IO.
     */
    suspend fun capture(context: Context, service: BridgeAccessibilityService, includeAll: Boolean = false): ScreenState =
        withContext(Dispatchers.IO) {
            val own = context.packageName
            val roots = service.rootNodes()
            val screen = roots.fold(Bounds.EMPTY) { acc, w -> acc.union(w.bounds) }
            val budget = NodeCapture.Budget()
            val windows = roots
                .filter { WindowSelection.keep(it.kind, it.primary, it.bounds, screen, includeAll) }
                .map { w ->
                    val hidden = w.packageName == own
                    WindowSnapshot(
                        title = w.title,
                        kind = w.kind,
                        packageName = w.packageName,
                        bounds = w.bounds,
                        primary = w.primary,
                        root = if (hidden) null else NodeCapture.capture(w.root, budget),
                        hidden = hidden,
                    )
                }
            val pkg = roots.firstOrNull()?.packageName ?: BridgeAccessibilityService.foregroundPackage
            val activity = if (pkg != null && pkg == BridgeAccessibilityService.foregroundPackage) service.currentActivity() else null
            ScreenState(windows, pkg, activity, DeviceState.isLocked(context), DeviceState.isScreenOn(context))
        }
}
