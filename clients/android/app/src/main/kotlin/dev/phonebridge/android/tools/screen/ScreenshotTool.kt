package dev.phonebridge.android.tools.screen

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.ScreenshotException
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optInt
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.tool
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
        "Prefer screen_read for finding and tapping elements; use screenshots for visual content.",
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
    unavailable = { Accessibility.unavailableReason() },
) { args, _ ->
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
    requireNoOwnWindow(context, service)
    val app = UiAutomation.foregroundPackage(service)
    val bitmap = try {
        service.takeScreenshotBitmap()
    } catch (e: ScreenshotException) {
        throw ToolException(e.message ?: "Screenshot failed.")
    }
    // Re-check: a PhoneBridge screen (e.g. an approval prompt) may have appeared meanwhile.
    try {
        requireNoOwnWindow(context, service)
    } catch (e: ToolException) {
        bitmap.recycle()
        throw e
    }
    val encoded = withContext(Dispatchers.Default) { encode(bitmap, maxDimension, png) }
    val caption = buildString {
        append("Screenshot ${encoded.width}x${encoded.height} ${if (png) "png" else "jpeg"} of app=${app ?: "unknown"}")
        append(", screen ${encoded.sourceWidth}x${encoded.sourceHeight} px")
        if (encoded.width != encoded.sourceWidth) {
            append("; multiply image coordinates by ")
            append(String.format(Locale.ROOT, "%.3f", encoded.sourceWidth.toDouble() / encoded.width))
            append(" for ui_tap/ui_swipe")
        }
        append('.')
        if (DeviceState.isLocked(context)) append(" The phone is locked (lock screen shown).")
        if (encoded.mostlyBlack) {
            append(" The image is almost entirely black: the app probably blocks screenshots (FLAG_SECURE) or the screen is dark. Use screen_read instead.")
        }
    }
    ToolResult.image(Base64.encodeToString(encoded.bytes, Base64.NO_WRAP), if (png) "image/png" else "image/jpeg", caption)
}

private suspend fun requireNoOwnWindow(context: Context, service: BridgeAccessibilityService) {
    val own = withContext(Dispatchers.IO) { service.rootNodes().any { it.packageName == context.packageName } }
    if (own) throw ToolException(SelfProtection.OWN_SCREEN + " A PhoneBridge screen is visible right now; try again after it closes.")
}

private class Encoded(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val mostlyBlack: Boolean,
)

/** Scales, checks for an all-black frame, compresses. Recycles [source]. */
private fun encode(source: Bitmap, maxDimension: Int, png: Boolean): Encoded {
    val (w, h) = ScreenshotMath.scaledSize(source.width, source.height, maxDimension)
    val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
    try {
        val probe = Bitmap.createScaledBitmap(scaled, BLACK_PROBE_W, BLACK_PROBE_H, false)
        val pixels = IntArray(BLACK_PROBE_W * BLACK_PROBE_H)
        probe.getPixels(pixels, 0, BLACK_PROBE_W, 0, 0, BLACK_PROBE_W, BLACK_PROBE_H)
        if (probe !== scaled) probe.recycle()
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
