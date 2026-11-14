package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
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
    description = "Drags a finger from (x1,y1) to (x2,y2) in screen pixels over duration_ms (default 300). Use for " +
        "carousels, sliders, pull-to-refresh or dismissing items; for lists prefer ui_scroll.",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema {
        integer("x1", "Start x", required = true, minimum = 0)
        integer("y1", "Start y", required = true, minimum = 0)
        integer("x2", "End x", required = true, minimum = 0)
        integer("y2", "End y", required = true, minimum = 0)
        integer("duration_ms", "Duration (default 300)", minimum = MIN_SWIPE_MS, maximum = MAX_SWIPE_MS)
    },
    timeoutMs = 30_000,
    unavailable = { UiAutomation.actionUnavailableReason(context) },
    describe = { args -> "Swipe from (${args.optInt("x1")},${args.optInt("y1")}) to (${args.optInt("x2")},${args.optInt("y2")})" },
) { args, _ ->
    val x1 = args.reqInt("x1")
    val y1 = args.reqInt("y1")
    val x2 = args.reqInt("x2")
    val y2 = args.reqInt("y2")
    val duration = (args.optLong("duration_ms") ?: 300L).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
    val service = UiAutomation.service()
    UiAutomation.requireUnlocked(context)
    UiAutomation.exclusive {
        val screen = service.screenBounds()
        if (!screen.contains(x1, y1) || !screen.contains(x2, y2)) {
            throw ToolException("Swipe points must be on the screen (0..${screen.width - 1}, 0..${screen.height - 1}).")
        }
        withContext(Dispatchers.IO) { ScreenGuard.checkPoint(context, service, x1, y1) }
        if (!service.swipe(x1, y1, x2, y2, duration)) throw ToolException("Android cancelled the swipe gesture. Retry.")
        ToolResult.text(UiAutomation.report(service, "Swiped from ($x1,$y1) to ($x2,$y2) in ${duration}ms."))
    }
}

/** ui_global_action: system navigation (back, home, ...). Works on the lock screen too. */
internal fun uiGlobalActionTool(context: Context): Tool = tool(
    name = "ui_global_action",
    title = "System navigation",
    description = "Performs a system navigation action: back, home, recents (app switcher), notifications (open " +
        "the shade), quick_settings, lock_screen, power_dialog.",
    scope = Scopes.SCREEN_CONTROL,
    risk = Risk.ACTION,
    schema = objectSchema { string("action", "The action", required = true, enum = GlobalActions.NAMES) },
    timeoutMs = 15_000,
    unavailable = { Accessibility.unavailableReason() },
    describe = { args -> "Press ${args.optString("action") ?: "a system button"}" },
) { args, _ ->
    val raw = args.reqString("action")
    val (name, action) = GlobalActions.parse(raw)
        ?: throw ToolException("Unknown action '$raw'. Use one of: ${GlobalActions.NAMES.joinToString()}.")
    val service = UiAutomation.service()
    UiAutomation.exclusive {
        if (!service.performGlobal(action)) throw ToolException("Android refused the '$name' action right now. Retry.")
        ToolResult.text(UiAutomation.report(service, "Performed '$name'."))
    }
}
