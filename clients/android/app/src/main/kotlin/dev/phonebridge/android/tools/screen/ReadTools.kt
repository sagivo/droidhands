package dev.phonebridge.android.tools.screen

import android.content.Context
import android.os.SystemClock
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.FindCriteria
import dev.phonebridge.android.access.MatchLevel
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.SerializeOptions
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optLong
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
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
        "Use the refs (n12) with ui_tap/ui_type/ui_scroll; they stay valid until the next screen_read. " +
        "Other windows (dialogs, notification shade) follow under 'window:' headers. By default layout-only " +
        "containers are hidden; include_all shows everything. Use screen_screenshot when the tree is empty or unclear " +
        "(games, maps, some web content). Apps the user blocked for this agent are left out, and reading is refused " +
        "while one of them is in the foreground.",
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
    val state = ScreenCapture.capture(context, service, access.visibility, includeAll)
    state.blockedForeground?.let { access.refuse(it) }
    val body = ScreenSession.refs.newSnapshot { register ->
        TreeSerializer.serialize(state.windows, SerializeOptions(includeAll = includeAll, maxNodes = maxNodes), register)
    }
    ToolResult.text(
        buildString {
            append(TreeSerializer.header(state.packageName, state.activity, state.locked))
            if (!state.screenOn) append("\n(the screen is off)")
            if (state.omittedWindows > 0) append("\n(${state.omittedWindows} window(s) of apps blocked for this agent are not shown)")
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
    val state = ScreenCapture.capture(context, service, access.visibility)
    state.blockedForeground?.let { access.refuse(it) }
    val matches = NodeQueries.find(state.roots, criteria)
    if (matches.isEmpty()) {
        return@tool ToolResult.text(
            "No matching elements on screen (app=${state.packageName ?: "unknown"}). " +
                "Call screen_read to see what is there, or ui_scroll to reveal more content.",
        )
    }
    val lines = matches.take(MAX_FIND_RESULTS).joinToString("\n") { TreeSerializer.formatNode(it, ScreenSession.refs.register(it)) }
    val more = if (matches.size > MAX_FIND_RESULTS) "\n... and ${matches.size - MAX_FIND_RESULTS} more; refine the criteria" else ""
    ToolResult.text("Found ${matches.size} element(s) (app=${state.packageName ?: "unknown"}):\n$lines$more")
}

/** ui_wait: waits until text is visible and/or a package is in the foreground. */
internal fun uiWaitTool(context: Context): Tool = tool(
    name = "ui_wait",
    title = "Wait for the screen",
    description = "Waits until the given text is visible on screen (text or content description, case-insensitive " +
        "contains) and/or the given app package is in the foreground, up to timeout_ms. Returns " +
        "{found, package, waited_ms} and, for text, the matching element's ref. Use it after opening an app or " +
        "tapping something that loads, instead of fixed sleeps. Text in apps the user blocked for this agent never matches.",
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
    }
}

private const val POLL_MS = 250L
private const val MIN_GAP_MS = 100L

private class Probe(val packageName: String?, val ref: String?, val line: String?, val blocked: String? = null)

/** One ui_wait check. Package-only waits skip the (heavier) tree capture. */
private suspend fun probe(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, key: String?): Probe {
    if (key == null) {
        val pkg = UiAutomation.foregroundPackage(service)
        return Probe(pkg, null, null, blocked = pkg?.takeUnless(access::allows))
    }
    val state = ScreenCapture.capture(context, service, access.visibility)
    if (state.blockedForeground != null) return Probe(state.packageName, null, null, blocked = state.blockedForeground)
    val node = NodeQueries.visibleNodes(state.roots).firstOrNull { NodeQueries.textLevel(it, key) != MatchLevel.NONE }
        ?: return Probe(state.packageName, null, null)
    val ref = ScreenSession.refs.register(node)
    return Probe(state.packageName, ref, TreeSerializer.formatNode(node, ref))
}
