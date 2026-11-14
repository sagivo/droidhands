package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.TextNorm
import dev.phonebridge.android.access.TextTarget
import dev.phonebridge.android.access.TreeSerializer
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optBool
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CANDIDATES = 10

/** ui_tap: taps an element (ref or text) or a point. */
internal fun uiTapTool(context: Context): Tool = tool(
    name = "ui_tap",
    title = "Tap on screen",
    description = "Taps on the phone's screen, in any app. Target, in order of preference: ref (n12 from " +
        "screen_read/screen_find; clicks it or its clickable container), text (visible text or content description, " +
        "case-insensitive: exact match beats starts-with beats contains; if several elements match equally you get " +
        "the candidates with refs), or x+y screen pixels. long_press=true touches and holds. Returns a short " +
        "confirmation and the foreground app; call screen_read to see the result. If the phone is locked, the user is " +
        "asked to unlock it (up to 60 s). Refused in apps the user blocked for this agent. ${UiAutomation.APPROVAL_PAUSE}",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("ref", "Element ref from screen_read/screen_find, e.g. n12")
        string("text", "Visible text or content description of the element")
        integer("x", "Screen x in pixels (with y)", minimum = 0)
        integer("y", "Screen y in pixels (with x)", minimum = 0)
        boolean("long_press", "Touch and hold instead of a tap")
    },
    timeoutMs = 30_000 + UiAutomation.UNLOCK_WAIT_MS,
    unavailable = { UiAutomation.actionUnavailableReason(context) },
    describe = { args ->
        val verb = if (args.optBool("long_press") == true) "Long-press" else "Tap"
        when {
            args.optString("ref") != null -> "$verb element ${args.optString("ref")} on screen"
            args.optString("text") != null -> "$verb \"${args.optString("text")}\" on screen"
            else -> "$verb the screen at (${args.optInt("x")},${args.optInt("y")})"
        }
    },
) { args, ctx ->
    val ref = args.optString("ref")?.trim()
    val text = args.optString("text")?.takeIf { it.isNotBlank() }
    val x = args.optInt("x")
    val y = args.optInt("y")
    val longPress = args.optBool("long_press") ?: false
    if (ref == null && text == null && (x == null || y == null)) {
        throw ToolException("Pass ref (from screen_read), text, or both x and y.")
    }
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    val action = if (text != null) "tap \"${TextNorm.truncate(text, 40)}\" on the screen" else "tap on the screen"
    UiAutomation.awaitUsable(context, ctx, action)
    UiAutomation.exclusive(access) {
        UiAutomation.requireUnlocked(context)
        UiAutomation.requireOperable(service, access)
        val message = when {
            ref != null -> {
                val live = withContext(Dispatchers.IO) { ScreenSession.resolve(ref, access) }
                tapNode(context, service, access, live, longPress)
            }
            text != null -> tapText(context, service, access, text, longPress)
            else -> tapPoint(context, service, access, x!!, y!!, longPress)
        }
        ToolResult.text(UiAutomation.report(service, access, message))
    }
}

private suspend fun tapText(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, text: String, longPress: Boolean): String {
    val state = ScreenCapture.capture(context, service, access)
    state.blockedForeground?.let { access.refuse(it) }
    return when (val target = NodeQueries.resolveTapText(state.roots, text)) {
        TextTarget.NotFound -> throw ToolException(
            "No visible element shows text or description matching \"$text\" (app=${state.packageName ?: "unknown"}). " +
                "Call screen_read to see what is on screen, or ui_scroll to reveal more.",
        )
        is TextTarget.Ambiguous -> throw ToolException(ambiguity(access, "\"$text\" matches", target.candidates))
        is TextTarget.Found -> {
            val ref = ScreenSession.refs(access).register(target.node)
            val fresh = withContext(Dispatchers.IO) { ScreenSession.refresh(target.node, access) }
                ?: throw ToolException("The element \"$text\" disappeared before it could be tapped; call screen_read again.")
            tapNode(context, service, access, ScreenSession.Live(ref, target.node, fresh), longPress)
        }
    }
}

/**
 * Clicks [live] (or its nearest clickable container) with an accessibility action; falls back to
 * a real touch gesture at the element's visible center. Both are checked right before they happen
 * ([UiAutomation.beforeAction], [ScreenGuard]).
 */
internal suspend fun tapNode(
    context: Context,
    service: BridgeAccessibilityService,
    access: ScreenAccess,
    live: ScreenSession.Live,
    longPress: Boolean,
): String {
    withContext(Dispatchers.IO) { ScreenGuard.checkNode(context, live.fresh, access) }
    val label = "${live.ref} ${TreeSerializer.label(live.fresh)}"
    if (!live.fresh.enabled) {
        throw ToolException("Element $label is disabled right now (it may need other input first). Call screen_read to check.")
    }
    val verb = if (longPress) "Long-pressed" else "Tapped"
    val target = if (longPress) NodeQueries.longClickTarget(live.node) else NodeQueries.clickTarget(live.node)
    if (target != null) {
        UiAutomation.beforeAction(access)
        val clicked = withContext(Dispatchers.IO) {
            val liveTarget = if (target === live.node) live.fresh else NodeActions.refresh(target)
            if (liveTarget == null) {
                false
            } else {
                ScreenGuard.checkClick(context, liveTarget, access)
                if (longPress) NodeActions.longClick(liveTarget) else NodeActions.click(liveTarget)
            }
        }
        if (clicked) {
            val via = if (target === live.node) "" else " (via its container ${TreeSerializer.label(target)})"
            return "$verb $label$via."
        }
    }
    val area = live.fresh.bounds.intersect(service.screenBounds())
    if (!live.fresh.visible || area.isEmpty) {
        throw ToolException("Element $label is not visible on screen, so it can't be tapped. Scroll to it (ui_scroll), then screen_read again.")
    }
    gestureAt(context, service, access, area.centerX, area.centerY, longPress)
    return "$verb $label at (${area.centerX},${area.centerY})."
}

private suspend fun tapPoint(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, x: Int, y: Int, longPress: Boolean): String {
    val screen = service.screenBounds()
    if (!screen.contains(x, y)) throw ToolException("Point ($x,$y) is outside the screen (${screen.width}x${screen.height} px).")
    gestureAt(context, service, access, x, y, longPress)
    return "${if (longPress) "Long-pressed" else "Tapped"} at ($x,$y)."
}

private suspend fun gestureAt(context: Context, service: BridgeAccessibilityService, access: ScreenAccess, x: Int, y: Int, longPress: Boolean) {
    withContext(Dispatchers.IO) { ScreenGuard.checkPoint(context, service, x, y, access) }
    UiAutomation.beforeAction(access)
    val ok = if (longPress) service.longPress(x, y) else service.tap(x, y)
    if (!ok) throw ToolException("Android cancelled the touch gesture (another gesture or the user interrupted it). Retry.")
}

/**
 * Error text listing up to [MAX_CANDIDATES] candidates with refs (registered among the calling agent's refs so it can
 * use them).
 */
internal fun ambiguity(access: ScreenAccess, prefix: String, candidates: List<UiNode>, noun: String = "different elements"): String {
    val refs = ScreenSession.refs(access)
    val lines = candidates.take(MAX_CANDIDATES).joinToString("\n") { TreeSerializer.formatNode(it, refs.register(it)) }
    val more = if (candidates.size > MAX_CANDIDATES) "\n... and ${candidates.size - MAX_CANDIDATES} more" else ""
    return "$prefix ${candidates.size} $noun. Retry with one of these refs:\n$lines$more"
}
