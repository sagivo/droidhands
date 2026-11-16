package com.droidhands.android.tools.screen

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure sizing and content checks for screen_screenshot. */
object ScreenshotMath {
    const val DEFAULT_MAX_DIMENSION = 1280
    const val MIN_MAX_DIMENSION = 64
    const val MAX_MAX_DIMENSION = 4096
    const val JPEG_QUALITY = 80

    /** Output size so that max(width, height) <= [maxDimension], keeping aspect ratio. Never upscales. */
    fun scaledSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val longest = max(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** True when at least [fraction] of the sampled ARGB [pixels] are (near) black. */
    fun isMostlyBlack(pixels: IntArray, fraction: Double = 0.98): Boolean {
        if (pixels.isEmpty()) return false
        val dark = pixels.count { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            r < 16 && g < 16 && b < 16
        }
        return dark >= pixels.size * fraction
    }
}
