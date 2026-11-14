package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.AppVisibility
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.Bounds
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.NodeRedaction
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowRoot
import dev.phonebridge.android.access.WindowSelection
import dev.phonebridge.android.access.WindowSnapshot
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.core.util.OneTimeCodes
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
    /** PhoneBridge's own notification content (e.g. in the shade) was left out. */
    val ownContentHidden: Boolean = false,
    /** Some text was masked because the user hides one-time codes. */
    val codesHidden: Boolean = false,
) {
    /** Roots of the readable windows (PhoneBridge's own windows excluded). */
    val roots: List<UiNode> get() = WindowSelection.roots(windows)

    /** Root of the primary window, if readable. */
    val primaryRoot: UiNode? get() = windows.firstOrNull { it.primary && !it.hidden }?.root

    /** Notes for the agent about what this capture leaves out or masks, one per line. */
    val notes: List<String>
        get() = buildList {
            if (omittedWindows > 0) add("($omittedWindows window(s) of apps blocked for this agent are not shown)")
            if (ownContentHidden) add(OWN_CONTENT_NOTE)
            if (codesHidden) add(CODES_NOTE)
        }

    companion object {
        const val OWN_CONTENT_NOTE = "(PhoneBridge's own notifications are not shown to agents)"

        const val CODES_NOTE =
            "(the user hides one-time codes: verification codes on screen appear as \"${OneTimeCodes.MASK}\"; ask the user " +
                "to read a code on the phone if it is really needed)"
    }
}

/**
 * Captures window trees into [ScreenState]s, withholding PhoneBridge's own windows and notification content and
 * whatever the agent's [AppVisibility] hides. When the service is stuck (no windows at all on a usable phone), captures
 * throw the agent-facing explanation from [BridgeAccessibilityService.rootNodesOrStuck].
 */
object ScreenCapture {
    /**
     * Captures the windows screen_read shows ([includeAll] adds keyboards and system bars). PhoneBridge's own content
     * (its notifications in System UI, with the rows hosting them) and windows and nodes of apps [visibility] blocks are
     * left out; nothing is captured while a blocked app is in the foreground (see [ScreenState.blockedForeground]).
     * With a [redactor] ([ScreenAccess.redactor]), every node text is passed through it, so whatever is derived from
     * the capture (output, matching, labels) only sees masked one-time codes. Blocking IPC runs on Dispatchers.IO.
     */
    suspend fun capture(
        context: Context,
        service: BridgeAccessibilityService,
        visibility: AppVisibility,
        includeAll: Boolean = false,
        redactor: ((String) -> String)? = null,
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
        val withoutOwn = captured.mapNotNull { w -> hideOwnContent(w, context.packageName) }
        val filtered = visibility.filter(withoutOwn)
        val shown = if (redactor == null) filtered.windows else filtered.windows.map { redact(it, redactor) }
        val activity = if (pkg != null && pkg == tracked) service.currentActivity() else null
        ScreenState(
            windows = shown,
            packageName = pkg,
            activity = activity,
            locked = DeviceState.isLocked(context),
            screenOn = DeviceState.isScreenOn(context),
            omittedWindows = readable.size - allowed.size + filtered.omitted,
            ownContentHidden = withoutOwn.size != captured.size || withoutOwn.indices.any { withoutOwn[it] !== captured[it] },
            codesHidden = shown.indices.any { shown[it] !== filtered.windows[it] },
        )
    }

    /** [capture] for a screen tool: what the calling agent may see, with one-time codes masked if the user hides them. */
    suspend fun capture(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, includeAll: Boolean = false): ScreenState =
        capture(context, service, access.visibility, includeAll, access.redactor)

    /**
     * [w] without PhoneBridge's notification content ([SelfProtection.hideOwnContent]); null when nothing of the window
     * is left. [w] itself when there is nothing to hide (always for our own, already hidden, windows).
     */
    private fun hideOwnContent(w: WindowSnapshot, ownPackage: String): WindowSnapshot? {
        val root = w.root ?: return w
        val kept = SelfProtection.hideOwnContent(root, ownPackage)
        return when {
            kept === root -> w
            kept == null -> null
            else -> w.copy(root = kept)
        }
    }

    /** [w] with [redactor] applied to its title and node texts; [w] itself when nothing changed. */
    private fun redact(w: WindowSnapshot, redactor: (String) -> String): WindowSnapshot {
        val root = w.root?.let { NodeRedaction.tree(it, redactor) }
        val title = w.title?.let(redactor)
        return if (root === w.root && title == w.title) w else w.copy(title = title, root = root)
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
