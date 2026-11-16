package dev.phonebridge.android.tools.screen

import android.content.Context
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
import dev.phonebridge.core.tools.reqInt
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_SWIPE_MS = 50L
private const val MAX_SWIPE_MS = 10_000L

/** ui_swipe: a straight finger drag between two screen points. */
internal fun uiSwipeTool(context: Context): Tool = tool(
    name = "ui_swipe",
    title = "Swipe",
    description = "Drags a finger from (x1,y1) to (x2,y2) over duration_ms (default 300); points are screen pixels, or " +
        "pixels of your last screenshot with space=screenshot. Returns what changed on screen. Use for " +
        "carousels, sliders, pull-to-refresh or dismissing items; for lists prefer ui_scroll. If the phone is locked, " +
        "the user is asked to unlock it (up to 60 s). Refused in apps the user blocked for this agent. ${UiAutomation.APPROVAL_PAUSE}",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        integer("x1", "Start x", required = true, minimum = 0)
        integer("y1", "Start y", required = true, minimum = 0)
        integer("x2", "End x", required = true, minimum = 0)
        integer("y2", "End y", required = true, minimum = 0)
        integer("duration_ms", "Duration (default 300)", minimum = MIN_SWIPE_MS, maximum = MAX_SWIPE_MS)
        string("space", "What the points are measured in (default screen)", enum = PointSpace.NAMES)
        boolean("observe", UiAutomation.OBSERVE_ARG)
    },
    timeoutMs = 30_000 + UiAutomation.UNLOCK_WAIT_MS,
    unavailable = { UiAutomation.actionUnavailableReason(context) },
    describe = { args -> "Swipe from (${args.optInt("x1")},${args.optInt("y1")}) to (${args.optInt("x2")},${args.optInt("y2")})" },
) { args, ctx ->
    val space = PointSpace.parse(args.optString("space"))
    val duration = (args.optLong("duration_ms") ?: 300L).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    val (x1, y1) = PointSpace.toScreen(space, args.reqInt("x1"), args.reqInt("y1"), service, access)
    val (x2, y2) = PointSpace.toScreen(space, args.reqInt("x2"), args.reqInt("y2"), service, access)
    UiAutomation.awaitUsable(context, ctx, "swipe on the screen")
    val message = UiAutomation.exclusive(access) {
        UiAutomation.requireUnlocked(context)
        UiAutomation.requireOperable(service, access)
        val before = UiAutomation.before(context, service, access)
        val screen = service.screenBounds()
        if (!screen.contains(x1, y1) || !screen.contains(x2, y2)) {
            throw ToolException("Swipe points must be on the screen (0..${screen.width - 1}, 0..${screen.height - 1}).")
        }
        // The whole gesture goes to the window under its starting point.
        withContext(Dispatchers.IO) { ScreenGuard.checkPoint(context, service, x1, y1, access) }
        UiAutomation.beforeAction(access)
        if (!service.swipe(x1, y1, x2, y2, duration)) throw ToolException("Android cancelled the swipe gesture. Retry.")
        UiAutomation.report(service, access, "Swiped from ($x1,$y1) to ($x2,$y2) in ${duration}ms.", before = before)
    }
    UiAutomation.result(context, ctx, args.optBool("observe") ?: false, message)
}

/**
 * ui_global_action: system navigation (back, home, ...). Works on the lock screen too, so it doesn't ask the
 * user to unlock. Refused while a blocked app is in the foreground; if the action reveals one, goes home.
 */
internal fun uiGlobalActionTool(context: Context): Tool = tool(
    name = "ui_global_action",
    title = "System navigation",
    description = "Performs a system navigation action: back, home, recents (app switcher), notifications (open " +
        "the shade), quick_settings, lock_screen, power_dialog. Works on the lock screen. Refused while an app the " +
        "user blocked for this agent is in the foreground. ${UiAutomation.APPROVAL_PAUSE}",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        string("action", "The action", required = true, enum = GlobalActions.NAMES)
        boolean("observe", UiAutomation.OBSERVE_ARG)
    },
    timeoutMs = 15_000,
    unavailable = { UiAutomation.unavailableReason() },
    describe = { args -> "Press ${args.optString("action") ?: "a system button"}" },
) { args, ctx ->
    val raw = args.reqString("action")
    val (name, action) = GlobalActions.parse(raw)
        ?: throw ToolException("Unknown action '$raw'. Use one of: ${GlobalActions.NAMES.joinToString()}.")
    val service = UiAutomation.service()
    val access = ScreenAccess.of(context, ctx)
    val message = UiAutomation.exclusive(access) {
        UiAutomation.requireOperable(service, access)
        val before = UiAutomation.before(context, service, access)
        UiAutomation.beforeAction(access)
        if (!service.performGlobal(action)) throw ToolException("Android refused the '$name' action right now. Retry.")
        UiAutomation.report(service, access, "Performed '$name'.", before = before)
    }
    UiAutomation.result(context, ctx, args.optBool("observe") ?: false, message)
}
