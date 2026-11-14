package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.AppVisibility
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.Bounds
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowRoot
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
    /** A blocked app the user is in (see [AppVisibility.blockedForeground]); its content was not captured. */
    val blockedForeground: String? = null,
    /** Windows left out because their app is blocked for this agent. */
    val omittedWindows: Int = 0,
) {
    /** Roots of the readable windows (PhoneBridge's own windows excluded). */
    val roots: List<UiNode> get() = WindowSelection.roots(windows)

    /** Root of the primary window, if readable. */
    val primaryRoot: UiNode? get() = windows.firstOrNull { it.primary && !it.hidden }?.root
}

/**
 * Captures window trees into [ScreenState]s, withholding PhoneBridge's own windows and whatever the agent's
 * [AppVisibility] hides. When the service is stuck (no windows at all on a usable phone), captures throw the
 * agent-facing explanation from [BridgeAccessibilityService.rootNodesOrStuck].
 */
object ScreenCapture {
    /**
     * Captures the windows screen_read shows ([includeAll] adds keyboards and system bars). Windows and nodes of
     * apps [visibility] blocks are left out; nothing is captured while a blocked app is in the foreground (see
     * [ScreenState.blockedForeground]). Blocking IPC runs on Dispatchers.IO.
     */
    suspend fun capture(
        context: Context,
        service: BridgeAccessibilityService,
        visibility: AppVisibility,
        includeAll: Boolean = false,
    ): ScreenState = withContext(Dispatchers.IO) {
        val roots = service.rootNodesOrStuck()
        val tracked = BridgeAccessibilityService.foregroundPackage
        val pkg = roots.firstOrNull()?.packageName ?: tracked
        val blocked = visibility.blockedForeground(roots.firstOrNull()?.packageName, tracked, roots.map { it.packageName })
        if (blocked != null) {
            return@withContext ScreenState(emptyList(), pkg, null, DeviceState.isLocked(context), DeviceState.isScreenOn(context), blocked)
        }
        val screen = roots.fold(Bounds.EMPTY) { acc, w -> acc.union(w.bounds) }
        val readable = roots.filter { WindowSelection.keep(it.kind, it.primary, it.bounds, screen, includeAll) }
        // Blocked windows are dropped before capturing, so their apps get no IPC at all.
        val allowed = readable.filter { it.packageName == context.packageName || visibility.allows(it.packageName) }
        val captured = snapshots(context, allowed)
        val filtered = visibility.filter(captured)
        val activity = if (pkg != null && pkg == tracked) service.currentActivity() else null
        ScreenState(
            windows = filtered.windows,
            packageName = pkg,
            activity = activity,
            locked = DeviceState.isLocked(context),
            screenOn = DeviceState.isScreenOn(context),
            omittedWindows = readable.size - allowed.size + filtered.omitted,
        )
    }

    /**
     * Every window on screen (all kinds, unfiltered; PhoneBridge's own still hidden), for checks that must see
     * everything a screenshot would show. Blocking IPC runs on Dispatchers.IO.
     */
    suspend fun captureEverything(context: Context, service: BridgeAccessibilityService): ScreenState = withContext(Dispatchers.IO) {
        val roots = service.rootNodesOrStuck()
        val tracked = BridgeAccessibilityService.foregroundPackage
        val pkg = roots.firstOrNull()?.packageName ?: tracked
        val activity = if (pkg != null && pkg == tracked) service.currentActivity() else null
        ScreenState(snapshots(context, roots), pkg, activity, DeviceState.isLocked(context), DeviceState.isScreenOn(context))
    }

    private fun snapshots(context: Context, roots: List<WindowRoot>): List<WindowSnapshot> {
        val own = context.packageName
        val budget = NodeCapture.Budget()
        return roots.map { w ->
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
    }
}
