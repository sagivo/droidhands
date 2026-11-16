package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.RenderedSteps
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException

/**
 * The screen tools of docs/TOOLS.md ("Screen": `screen_observe`, `screen_read`, `screen_find`, `screen_screenshot`,
 * `ui_wait`, `ui_tap`, `ui_type`, `ui_scroll`, `ui_swipe`, `ui_global_action`) on any [ScreenDriver].
 * [appLabel] names an app id for headers and refusals.
 */
fun phoneUseTools(
    driver: ScreenDriver,
    appLabel: suspend (String) -> String?,
    marker: ScreenshotMarker = ScreenshotMarker.NONE,
): List<Tool> = TODO("phone use engine")

/** Runs screen recipes (docs/PLUGINS.md §6.4) on any [ScreenDriver], confined to the action's app. */
class ScreenStepRunner(private val driver: ScreenDriver) {
    /**
     * Runs [steps] for [app]. Throws [dev.phonebridge.core.plugins.IntegrationException] naming the failed step when the
     * app doesn't do what the recipe expects, [ToolException] for refusals.
     */
    suspend fun run(steps: RenderedSteps, app: InstalledApp, ctx: CallContext): ActionResult = throw ToolException("Screen recipes are not supported yet.")
}
