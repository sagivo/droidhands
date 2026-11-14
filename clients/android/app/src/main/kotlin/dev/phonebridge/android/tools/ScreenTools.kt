package dev.phonebridge.android.tools

import android.content.Context
import dev.phonebridge.android.tools.screen.screenFindTool
import dev.phonebridge.android.tools.screen.screenReadTool
import dev.phonebridge.android.tools.screen.screenScreenshotTool
import dev.phonebridge.android.tools.screen.uiGlobalActionTool
import dev.phonebridge.android.tools.screen.uiScrollTool
import dev.phonebridge.android.tools.screen.uiSwipeTool
import dev.phonebridge.android.tools.screen.uiTapTool
import dev.phonebridge.android.tools.screen.uiTypeTool
import dev.phonebridge.android.tools.screen.uiWaitTool
import dev.phonebridge.core.tools.Tool

/**
 * Tools: screen_read, screen_find, screen_screenshot, ui_wait, ui_tap, ui_type, ui_scroll, ui_swipe,
 * ui_global_action (docs/TOOLS.md "Screen"). All need the PhoneBridge accessibility service and apply the
 * agent's app access (`ScreenAccess`); the action tools ask the user to unlock a locked phone and run one at a
 * time (see `UiAutomation`).
 */
fun screenTools(context: Context): List<Tool> = listOf(
    screenReadTool(context),
    screenFindTool(context),
    screenScreenshotTool(context),
    uiWaitTool(context),
    uiTapTool(context),
    uiTypeTool(context),
    uiScrollTool(context),
    uiSwipeTool(context),
    uiGlobalActionTool(context),
)
