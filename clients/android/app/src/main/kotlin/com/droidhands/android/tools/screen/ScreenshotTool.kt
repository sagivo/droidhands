package com.droidhands.android.tools.screen

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.access.NodeQueries
import com.droidhands.android.access.NodeRedaction
import com.droidhands.android.access.ScreenshotException
import com.droidhands.android.access.SelfProtection
import com.droidhands.android.access.SystemOverlays
import com.droidhands.android.tools.support.ApprovalGuard
import com.droidhands.android.tools.support.DeviceState
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.tools.Tool
import com.droidhands.core.tools.ToolException
import com.droidhands.core.tools.ToolResult
import com.droidhands.core.tools.objectSchema
import com.droidhands.core.tools.optInt
import com.droidhands.core.tools.optString
import com.droidhands.core.tools.tool
import com.droidhands.core.util.OneTimeCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/** screen_screenshot: an MCP image of the screen. */
internal fun screenScreenshotTool(context: Context): Tool = tool(
    name = "screen_screenshot",
    title = "Take a screenshot",
    description = "Takes a screenshot of the phone's screen and returns it as an image, scaled so the longest side " +
        "is at most max_dimension pixels (default ${ScreenshotMath.DEFAULT_MAX_DIMENSION}). The caption gives the " +
        "factor to convert image coordinates to screen pixels for ui_tap/ui_swipe. Apps that protect their content " +
        "(banking, DRM video, incognito tabs; Android FLAG_SECURE) appear black: use screen_read for those. " +
        "Prefer screen_read for finding and tapping elements; use screenshots for visual content. If the user blocked " +
        "some apps for this agent, screenshots are refused while any of them is visible, and while the notification " +
        "shade, Quick Settings, the lock screen or the recent-apps screen is showing (use notifications_list instead). " +
        "When the user hides one-time codes, screenshots are refused while a verification code is visible (use screen_read, " +
        "which masks it).",
    scope = Scopes.SCREEN_READ,
    risk = Risk.READ,
    schema = objectSchema {
        integer(
            "max_dimension", "Longest side of the returned image in pixels (default ${ScreenshotMath.DEFAULT_MAX_DIMENSION})",
            minimum = ScreenshotMath.MIN_MAX_DIMENSION.toLong(), maximum = ScreenshotMath.MAX_MAX_DIMENSION.toLong(),
        )
        string("format", "Image format (default jpeg)", enum = listOf("jpeg", "png"))
    },
    timeoutMs = 30_000,
    unavailable = { UiAutomation.unavailableReason() },
) { args, ctx ->
    val maxDimension = (args.optInt("max_dimension") ?: ScreenshotMath.DEFAULT_MAX_DIMENSION)
        .coerceIn(ScreenshotMath.MIN_MAX_DIMENSION, ScreenshotMath.MAX_MAX_DIMENSION)
    val png = when (args.optString("format")?.trim()?.lowercase()) {
        null, "jpeg", "jpg" -> false
        "png" -> true
        else -> throw ToolException("format must be 'jpeg' or 'png'.")
    }
    val service = UiAutomation.service()
    if (!DeviceState.isScreenOn(context)) {
        throw ToolException("The phone's screen is off, so a screenshot would be black. Ask the user to turn it on, or use screen_read.")
    }
    val access = ScreenAccess.of(context, ctx)
    requireShareable(context, service, access)
    val app = UiAutomation.foregroundPackage(service)
    val bitmap = try {
        service.takeScreenshotBitmap()
    } catch (e: ScreenshotException) {
        throw ToolException(e.message ?: "Screenshot failed.")
    }
    // Re-check: a DroidHands screen (e.g. an approval prompt) or a blocked app may have appeared meanwhile.
    try {
        requireShareable(context, service, access)
    } catch (e: ToolException) {
        bitmap.recycle()
        throw e
    }
    val encoded = withContext(Dispatchers.Default) { encode(bitmap, maxDimension, png) }
    ScreenSession.recordScreenshot(access.tokenId, encoded.geometry)
    val caption = buildString {
        append("Screenshot ${encoded.width}x${encoded.height} ${if (png) "png" else "jpeg"} of app=${app ?: "unknown"}")
        append(", screen ${encoded.sourceWidth}x${encoded.sourceHeight} px")
        append(". ").append(coordinateHint(encoded))
        append('.')
        if (DeviceState.isLocked(context)) append(" The phone is locked (lock screen shown).")
        if (encoded.mostlyBlack) {
            append(" The image is almost entirely black: the app probably blocks screenshots (FLAG_SECURE) or the screen is dark. Use screen_read instead.")
        }
    }
    ToolResult.image(Base64.encodeToString(encoded.bytes, Base64.NO_WRAP), if (png) "image/png" else "image/jpeg", caption)
}

/**
 * Throws unless the screen may be shared as pixels: no DroidHands window; no DroidHands notification while an
 * approval is pending ([ApprovalGuard]: it may show another agent's request); when the user hides one-time codes, no
 * visible text that contains one; and, for agents with app restrictions, no window or element of a blocked app and no
 * system screen that shows other apps' content ([SystemOverlays]). Pixels can't be filtered or masked the way
 * screen_read filters nodes.
 */
internal suspend fun requireShareable(context: Context, service: BridgeAccessibilityService, access: ScreenAccess) {
    withContext(Dispatchers.IO) {
        val own = context.packageName
        if (!access.restricted && access.redactor == null && !ApprovalGuard.isActive) {
            if (service.rootNodesOrStuck().any { it.packageName == own }) throw ToolException(OWN_WINDOW_VISIBLE)
            return@withContext
        }
        val state = ScreenCapture.captureEverything(context, service)
        if (state.windows.any { it.hidden }) throw ToolException(OWN_WINDOW_VISIBLE)
        val visible = NodeQueries.visibleNodes(state.roots)
        if (ApprovalGuard.isActive && visible.any { SelfProtection.isOwnPackage(it.packageName, own) }) {
            throw ToolException(OWN_NOTIFICATION_VISIBLE)
        }
        if (access.redactor != null && NodeRedaction.anyVisibleText(state.roots, OneTimeCodes::containsCode)) {
            throw ToolException(CODE_VISIBLE)
        }
        if (!access.restricted) return@withContext
        val windowPackages = state.windows.map { it.packageName }
        val primary = state.windows.firstOrNull { it.primary }?.packageName
        access.visibility.blockedForeground(primary, BridgeAccessibilityService.foregroundPackage, windowPackages)
            ?.let { access.refuse(it, "Can't take a screenshot.") }
        access.visibility.firstBlocked(state.windows)
            ?.let { access.refuse(it, "Can't take a screenshot: a window or notification of a blocked app is visible.") }
        SystemOverlays.detect(state.windows, state.locked, state.activity)
            ?.let { throw ToolException(ScreenAccess.overlayRefusal(it)) }
    }
}

private const val OWN_WINDOW_VISIBLE = SelfProtection.OWN_SCREEN + " A DroidHands screen is visible right now; try again after it closes."

private const val OWN_NOTIFICATION_VISIBLE =
    "Can't take a screenshot while a DroidHands notification is visible and an approval is waiting for the user: it may " +
        "show another agent's request, and DroidHands does not show its own screens and notifications to agents. Retry " +
        "after the user has answered, or use screen_read (it leaves them out)."

private const val CODE_VISIBLE =
    "Can't take a screenshot: the screen shows what looks like a verification (one-time) code, and the user hides those " +
        "from agents (a screenshot can't be masked). Use screen_read instead (codes appear as \"${OneTimeCodes.MASK}\"), or " +
        "ask the user to read the code on the phone if it is really needed."

internal class Encoded(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val mostlyBlack: Boolean,
) {
    val geometry: ScreenshotGeometry get() = ScreenshotGeometry(width, height, sourceWidth, sourceHeight)
}

/** How to tap a point seen in the image [encoded]. */
internal fun coordinateHint(encoded: Encoded): String = if (encoded.width == encoded.sourceWidth) {
    "Image pixels are screen pixels for ui_tap/ui_swipe"
} else {
    "To tap a point of this image, pass its image x,y with space=screenshot to ui_tap/ui_swipe (or multiply by " +
        String.format(Locale.ROOT, "%.3f", encoded.sourceWidth.toDouble() / encoded.width) + " for screen pixels)"
}

/**
 * Scales, checks for an all-black frame (before [paint] draws on it), lets [paint] draw on the scaled image, compresses.
 * Recycles [source].
 */
internal fun encode(source: Bitmap, maxDimension: Int, png: Boolean, paint: ((Bitmap) -> Unit)? = null): Encoded {
    val (w, h) = ScreenshotMath.scaledSize(source.width, source.height, maxDimension)
    var scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
    try {
        val probe = Bitmap.createScaledBitmap(scaled, BLACK_PROBE_W, BLACK_PROBE_H, false)
        val pixels = IntArray(BLACK_PROBE_W * BLACK_PROBE_H)
        probe.getPixels(pixels, 0, BLACK_PROBE_W, 0, 0, BLACK_PROBE_W, BLACK_PROBE_H)
        if (probe !== scaled) probe.recycle()
        if (paint != null) {
            if (!scaled.isMutable) {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw ToolException("Not enough memory to process the screenshot. Retry with a smaller max_dimension.")
                if (scaled !== source) scaled.recycle()
                scaled = copy
            }
            paint(scaled)
        }
        val out = ByteArrayOutputStream(if (png) w * h else w * h / 4)
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!scaled.compress(format, if (png) 100 else ScreenshotMath.JPEG_QUALITY, out)) {
            throw ToolException("Could not encode the screenshot. Retry with format=jpeg or a smaller max_dimension.")
        }
        return Encoded(out.toByteArray(), w, h, source.width, source.height, ScreenshotMath.isMostlyBlack(pixels))
    } finally {
        if (scaled !== source) scaled.recycle()
        source.recycle()
    }
}

private const val BLACK_PROBE_W = 32
private const val BLACK_PROBE_H = 64
