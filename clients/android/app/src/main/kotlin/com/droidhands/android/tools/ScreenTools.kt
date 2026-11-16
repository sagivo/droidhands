package com.droidhands.android.tools

import android.content.Context
import com.droidhands.android.tools.screen.screenFindTool
import com.droidhands.android.tools.screen.screenObserveTool
import com.droidhands.android.tools.screen.screenReadTool
import com.droidhands.android.tools.screen.screenScreenshotTool
import com.droidhands.android.tools.screen.uiGlobalActionTool
import com.droidhands.android.tools.screen.uiScrollTool
import com.droidhands.android.tools.screen.uiSwipeTool
import com.droidhands.android.tools.screen.uiTapTool
import com.droidhands.android.tools.screen.uiTypeTool
import com.droidhands.android.tools.screen.uiWaitTool
import com.droidhands.core.tools.Tool

/**
 * Tools: screen_observe, screen_read, screen_find, screen_screenshot, ui_wait, ui_tap, ui_type, ui_scroll, ui_swipe,
 * ui_global_action (docs/TOOLS.md "Screen"). All need the DroidHands accessibility service and apply the
 * agent's app access (`ScreenAccess`); the action tools ask the user to unlock a locked phone and run one at a
 * time (see `UiAutomation`).
 */
fun screenTools(context: Context): List<Tool> = listOf(
    screenObserveTool(context),
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
