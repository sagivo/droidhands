package com.droidhands.android.tools.screen

import com.droidhands.android.access.WindowKind

import android.content.Context
import android.os.SystemClock
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.access.FindCriteria
import com.droidhands.android.access.MatchLevel
import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.SerializeOptions
import com.droidhands.android.access.TextNorm
import com.droidhands.android.access.TreeSerializer
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optBool
import com.droidhands.core.tools.optInt
import com.droidhands.core.tools.optLong
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.tool
import com.droidhands.core.util.OneTimeCodes
import kotlinx.coroutines.delay
import kotlinx.serialization.json.put

private const val DEFAULT_MAX_NODES = 300
private const val MAX_MAX_NODES = 2000
private const val MAX_FIND_RESULTS = 50

/** screen_read: the accessibility tree of the current screen, with refs for the ui_* tools. */
internal fun screenReadTool(context: Context): Tool = tool(
    name = "screen_read",
    title = "Read the screen",
    description = "Reads what is on the phone's screen in any app, as an indented tree. First line: " +
        "app=<package> activity=<class> locked=<bool>. Then one element per line: " +
        "`n12 Button \"Send\" desc=\"...\" id=send [clickable] (x,y,w,h)` (bounds in screen pixels). " +
        "Use the refs (n12) with ui_tap/ui_type/ui_scroll; they are yours alone and stay valid until your next " +
        "screen_read (each read gives new numbers). " +
        "Other windows (dialogs, notification shade) follow under 'window:' headers. By default layout-only " +
        "containers are hidden; include_all shows everything. Use screen_screenshot when the tree is empty or unclear " +
        "(games, maps, some web content). Apps the user blocked for this agent are left out, and reading is refused " +
        "while one of them is in the foreground. DroidHands's own screens and notifications are never shown, and when " +
        "the user hides one-time codes they appear as \"${OneTimeCodes.MASK}\".",
    scope = Scopes.SCREEN_READ,
    risk = Risk.READ,
    schema = objectSchema {
        boolean("include_all", "Show every visible element, including layout containers, keyboard and system bars (default false)")
        integer("max_nodes", "Maximum number of element lines (default $DEFAULT_MAX_NODES)", minimum = 1, maximum = MAX_MAX_NODES.toLong())
    },
    timeoutMs = 30_000,
    unavailable = { UiAutomation.unavailableReason() },
) { args, ctx ->
    val service = UiAutomation.service()
    val includeAll = args.optBool("include_all") ?: false
    val maxNodes = (args.optInt("max_nodes") ?: DEFAULT_MAX_NODES).coerceIn(1, MAX_MAX_NODES)
    val access = ScreenAccess.of(context, ctx)
    // Right after an app switch the new window often has no accessibility content yet (transition in progress):
    // when the screen is on but nothing readable came out, wait briefly and read again instead of reporting an
    // empty screen.
    var state = ScreenCapture.capture(context, service, access, includeAll)
    state.blockedForeground?.let { access.refuse(it) }
    var body = ScreenSession.refs(access).newSnapshot { register ->
        TreeSerializer.serialize(state.windows, SerializeOptions(includeAll = includeAll, maxNodes = maxNodes), register)
    }
    var attempt = 1
    while (state.screenOn && attempt < SETTLE_ATTEMPTS && (body.text.isEmpty() || ScreenSettling.appWindowMissing(state))) {
        kotlinx.coroutines.delay(SETTLE_DELAY_MS)
        attempt++
        state = ScreenCapture.capture(context, service, access, includeAll)
        state.blockedForeground?.let { access.refuse(it) }
        val retryState = state
        body = ScreenSession.refs(access).newSnapshot { register ->
            TreeSerializer.serialize(retryState.windows, SerializeOptions(includeAll = includeAll, maxNodes = maxNodes), register)
        }
    }
    ToolResult.text(
        buildString {
            append(TreeSerializer.header(state.packageName, state.activity, state.locked))
            if (!state.screenOn) append("\n(the screen is off)")
            state.notes.forEach { append('\n').append(it) }
            append('\n')
            append(
                body.text.ifEmpty {
                    "(nothing readable: the screen may be off or the app exposes no accessibility info; try screen_screenshot)"
                },
            )
        },
    )
}

/** screen_find: matching elements as refs (appended to the latest snapshot). */
internal fun screenFindTool(context: Context): Tool = tool(
    name = "screen_find",
    title = "Find elements on screen",
    description = "Finds visible elements on the current screen and returns them with refs (same line format as " +
        "screen_read). text: case-insensitive 'contains' on the element text; description: 'contains' on the " +
        "content description (icons); view_id: exact resource id, full (com.app:id/send) or short (send); " +
        "clickable_only: only elements that react to taps (directly or through a clickable container). " +
        "Criteria combine with AND. Cheaper than screen_read when you know what you're looking for.",
    scope = Scopes.SCREEN_READ,
    risk = Risk.READ,
    schema = objectSchema {
        string("text", "Text the element shows (contains, case-insensitive)")
        string("view_id", "Resource id, full or short (exact)")
        string("description", "Content description (contains, case-insensitive)")
        boolean("clickable_only", "Only elements that respond to taps")
    },
    timeoutMs = 30_000,
    unavailable = { UiAutomation.unavailableReason() },
) { args, ctx ->
    val criteria = FindCriteria(
        text = args.optString("text"),
        viewId = args.optString("view_id"),
        description = args.optString("description"),
        clickableOnly = args.optBool("clickable_only") ?: false,
    )
    if (criteria.isEmpty) throw ToolException("Pass at least one of text, view_id, description, or clickable_only=true.")
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    val state = ScreenCapture.capture(context, service, access)
    state.blockedForeground?.let { access.refuse(it) }
    val matches = NodeQueries.find(state.roots, criteria)
    if (matches.isEmpty()) {
        return@tool ToolResult.text(
            "No matching elements on screen (app=${state.packageName ?: "unknown"}). " +
                "Call screen_read to see what is there, or ui_scroll to reveal more content.",
        )
    }
    val refs = ScreenSession.refs(access)
    val lines = matches.take(MAX_FIND_RESULTS).joinToString("\n") { TreeSerializer.formatNode(it, refs.register(it)) }
    val more = if (matches.size > MAX_FIND_RESULTS) "\n... and ${matches.size - MAX_FIND_RESULTS} more; refine the criteria" else ""
    val codes = if (state.codesHidden) "\n${ScreenState.CODES_NOTE}" else ""
    ToolResult.text("Found ${matches.size} element(s) (app=${state.packageName ?: "unknown"}):\n$lines$more$codes")
}

/** ui_wait: waits until text is visible and/or a package is in the foreground. */
internal fun uiWaitTool(context: Context): Tool = tool(
    name = "ui_wait",
    title = "Wait for the screen",
    description = "Waits until the given text is visible on screen (text or content description, case-insensitive " +
        "contains) and/or the given app package is in the foreground, up to timeout_ms. Returns " +
        "{found, package, waited_ms} and, for text, the matching element's ref. Use it after opening an app or " +
        "tapping something that loads, instead of fixed sleeps. Text in apps the user blocked for this agent and in " +
        "DroidHands's own notifications never matches; when the user hides one-time codes, matching sees them as " +
        "\"${OneTimeCodes.MASK}\" (and the result has codes_hidden=true).",
    scope = Scopes.SCREEN_READ,
    risk = Risk.READ,
    schema = objectSchema {
        string("text", "Text to wait for (contains, case-insensitive)")
        string("package", "App package to wait for in the foreground, e.g. com.whatsapp")
        integer("timeout_ms", "Max wait (default 5000, max 30000)", minimum = 0, maximum = 30_000)
    },
    timeoutMs = 40_000,
    unavailable = { UiAutomation.unavailableReason() },
) { args, ctx ->
    val text = args.optString("text")?.takeIf { it.isNotBlank() }
    val pkg = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }
    if (text == null && pkg == null) throw ToolException("Pass text and/or package to wait for.")
    val timeout = (args.optLong("timeout_ms") ?: 5_000L).coerceIn(0L, 30_000L)
    val access = ScreenAccess.of(context, ctx)
    pkg?.let { access.require(it) }
    waitFor(context, access, text?.let(TextNorm::key), pkg, timeout)
}

/**
 * The ui_wait loop: checks, then sleeps until the next UI event (or [POLL_MS]), until found or [timeout].
 * A blocked app in the foreground never matches; if one is still there at the timeout, the wait is refused.
 */
private suspend fun waitFor(context: Context, access: ScreenAccess, key: String?, pkg: String?, timeout: Long): ToolResult {
    val start = SystemClock.uptimeMillis()
    while (true) {
        val service = UiAutomation.service()
        val seen = BridgeAccessibilityService.lastEventAt.value
        val probe = probe(context, service, access, key)
        val pkgOk = pkg == null || pkg.equals(probe.packageName, ignoreCase = true)
        val elapsed = SystemClock.uptimeMillis() - start
        if (probe.blocked == null && pkgOk && (key == null || probe.ref != null)) {
            return ToolResult.json {
                put("found", true)
                put("package", probe.packageName)
                put("waited_ms", elapsed)
                probe.ref?.let { put("ref", it) }
                probe.line?.let { put("element", it) }
                if (probe.codesHidden) put("codes_hidden", true)
            }
        }
        if (elapsed >= timeout) {
            probe.blocked?.let { access.refuse(it) }
            return ToolResult.json {
                put("found", false)
                put("package", probe.packageName)
                put("waited_ms", elapsed)
            }
        }
        // Event-driven, but at most every POLL_MS and at least MIN_GAP_MS apart (busy screens fire constantly).
        BridgeAccessibilityService.awaitEvent(seen, minOf(POLL_MS, timeout - elapsed))
        delay(MIN_GAP_MS)
        access.ensureStillAllowed()
    }
}

private const val POLL_MS = 250L
private const val MIN_GAP_MS = 100L

private class Probe(
    val packageName: String?,
    val ref: String?,
    val line: String?,
    val blocked: String? = null,
    /** The matching element shows a masked one-time code. */
    val codesHidden: Boolean = false,
)

/** One ui_wait check. Package-only waits skip the (heavier) tree capture. */
private suspend fun probe(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, key: String?): Probe {
    if (key == null) {
        val pkg = UiAutomation.foregroundPackage(service)
        return Probe(pkg, null, null, blocked = pkg?.takeUnless(access::allows))
    }
    val state = ScreenCapture.capture(context, service, access)
    if (state.blockedForeground != null) return Probe(state.packageName, null, null, blocked = state.blockedForeground)
    val node = NodeQueries.visibleNodes(state.roots).firstOrNull { NodeQueries.textLevel(it, key) != MatchLevel.NONE }
        ?: return Probe(state.packageName, null, null)
    val ref = ScreenSession.refs(access).register(node)
    val line = TreeSerializer.formatNode(node, ref)
    return Probe(state.packageName, ref, line, codesHidden = state.codesHidden && line.contains(OneTimeCodes.MASK))
}


private const val SETTLE_ATTEMPTS = 4
private const val SETTLE_DELAY_MS = 350L

/** Pure checks for screen_read's settle-and-retry. */
internal object ScreenSettling {
    private const val SYSTEM_UI = "com.android.systemui"

    /**
     * True when an ordinary app is in the foreground but no application window was captured — typically mid
     * activity transition, when only System UI (status bar) windows are listed. Reading then would show status-bar
     * noise instead of the app.
     */
    fun appWindowMissing(state: ScreenState): Boolean {
        val tracked = BridgeAccessibilityService.foregroundPackage ?: return false
        return appWindowMissing(tracked, state.windows.map { it.kind })
    }

    fun appWindowMissing(trackedForeground: String, capturedKinds: List<WindowKind>): Boolean =
        trackedForeground != SYSTEM_UI && capturedKinds.none { it == WindowKind.APPLICATION }
}
