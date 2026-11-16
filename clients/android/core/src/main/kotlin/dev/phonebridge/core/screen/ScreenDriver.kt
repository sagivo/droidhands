package dev.phonebridge.core.screen

import dev.phonebridge.core.plugins.DeviceOs

// Phone use, platform-neutral (docs/TOOLS.md "Screen"): what a device must provide so the generic screen tools
// ([phoneUseTools]) and screen recipes ([ScreenStepRunner]) work on it. A platform implements [ScreenDriver] with its
// own automation layer (the iOS host with the on-device runner, docs/IOS.md); everything an agent sees (refs, marked
// screenshots, change reports, app-access checks) is built here, once, from what the driver reports.

/** A rectangle in screen points (the unit [ScreenDriver.tap] takes). */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < x + width && py < y + height

    /** The part of this rectangle inside [other], or an empty rectangle. */
    fun intersect(other: Rect): Rect {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + width, other.x + other.width)
        val bottom = minOf(y + height, other.y + other.height)
        return Rect(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }
}

/** A size in screen points. */
data class Size(val width: Int, val height: Int)

/**
 * One element on screen as the platform's accessibility layer exposes it. Only what the platform reports is filled
 * in: nothing is guessed from an app's name or a known layout.
 */
data class UiElement(
    /** The platform's role without prefixes, e.g. `Button`, `TextField`, `Cell` (iOS) or `EditText` (Android). */
    val type: String,
    /** Accessible name: label, text or content description. */
    val label: String? = null,
    /** Current value, e.g. a text field's contents or a switch's "1". Never set for [secure] fields. */
    val value: String? = null,
    /** Developer id: iOS accessibilityIdentifier, Android view id. */
    val identifier: String? = null,
    val placeholder: String? = null,
    val bounds: Rect,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val focused: Boolean = false,
    /** The platform says it responds to taps (Android `clickable`; on iOS derived from its type and traits). */
    val actionable: Boolean = false,
    /** Accepts text input. */
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    /** A password field: its value is never reported. */
    val secure: Boolean = false,
    val children: List<UiElement> = emptyList(),
)

/** What is on screen at one moment. */
class ScreenSnapshot(
    /** The id ([dev.phonebridge.core.plugins.InstalledApp.packageName]) of the app in front, or null if unknown. */
    val foregroundApp: String?,
    /** Screen size in points. */
    val screen: Size,
    /** The foreground app's element tree, or null when it exposes nothing. */
    val root: UiElement?,
    /**
     * System UI drawn over the app (alerts, permission prompts, the keyboard's own buttons excluded), each with the id of
     * the process that owns it (e.g. `com.apple.springboard`). Treated like the app's own elements.
     */
    val overlays: List<Overlay> = emptyList(),
    val locked: Boolean = false,
    val keyboardShown: Boolean = false,
    /** Platform notes for the agent, e.g. why the tree is empty. */
    val notes: List<String> = emptyList(),
) {
    class Overlay(val owner: String, val root: UiElement)
}

/** A screenshot as the device took it. [width] and [height] are pixels; points × scale = pixels. */
class Screenshot(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)

/** Thrown by a [ScreenDriver] when the device refuses or can't do something; [message] is shown to the agent. */
class ScreenException(message: String) : Exception(message)

/**
 * The device side of phone use. Coordinates are screen points, origin top-left. Implementations must be thread-safe;
 * the tools call one method at a time per device. Every method throws [ScreenException] with a plain explanation when
 * the device can't do it (runner not running, phone locked, OS refused).
 */
interface ScreenDriver {
    val os: DeviceOs

    /**
     * The `ui_global_action` names this device supports, e.g. `home` and `lock_screen` on iOS, plus `back`, `recents`,
     * `notifications`, `quick_settings`, `power_dialog` on Android. Others are refused with the list of these.
     */
    val globalActions: Set<String>

    suspend fun snapshot(): ScreenSnapshot

    suspend fun screenshot(): Screenshot

    /** A tap, or a long press when [durationMs] > 0. */
    suspend fun tap(x: Double, y: Double, durationMs: Long = 0)

    suspend fun swipe(fromX: Double, fromY: Double, toX: Double, toY: Double, durationMs: Long = 300)

    /** Types into the focused element (the keyboard must be up: tap a field first). */
    suspend fun typeText(text: String)

    /** Clears the focused text field. */
    suspend fun clearText()

    /** Presses a keyboard key: `return` (submit) or `delete`. */
    suspend fun pressKey(key: String)

    /** One of [globalActions]. */
    suspend fun globalAction(action: String)

    /** Brings the app to the front (launching it if needed). */
    suspend fun launchApp(appId: String)

    /** Asks the OS to open [url]; the OS picks the app that handles it. */
    suspend fun openUrl(url: String)
}

/** Draws numbered boxes on a screenshot and scales it to fit [maxDimension]; the platform provides the imaging. */
fun interface ScreenshotMarker {
    /** [marks] are in the pixels of [shot]. Returns the image to send (any of PNG or JPEG). */
    fun mark(shot: Screenshot, marks: List<Mark>, maxDimension: Int): Screenshot

    /** One box: [label] is the element's ref number. */
    data class Mark(val label: String, val box: Rect)

    companion object {
        /** No imaging available: the screenshot is sent as taken, without boxes. */
        val NONE = ScreenshotMarker { shot, _, _ -> shot }
    }
}
