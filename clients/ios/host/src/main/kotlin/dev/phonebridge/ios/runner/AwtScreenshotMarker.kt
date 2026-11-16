package dev.phonebridge.ios.runner

import dev.phonebridge.core.screen.ScreenshotMarker
import dev.phonebridge.core.screen.Screenshot
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [ScreenshotMarker] with java.awt off screen (headless) and ImageIO: scales the screenshot so its longer side is at
 * most `maxDimension`, draws each mark as a box with its number in a filled tag, and returns a JPEG. A screenshot
 * ImageIO can't decode is returned as it came, so the agent still gets the picture.
 */
class AwtScreenshotMarker(private val quality: Float = 0.8f) : ScreenshotMarker {
    init {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    override fun mark(shot: Screenshot, marks: List<ScreenshotMarker.Mark>, maxDimension: Int): Screenshot {
        val source = try {
            ImageIO.read(ByteArrayInputStream(shot.bytes))
        } catch (e: Exception) {
            null
        } ?: return shot
        val longest = max(source.width, source.height)
        val scale = if (maxDimension in 1 until longest) maxDimension.toDouble() / longest else 1.0
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.drawImage(source, 0, 0, w, h, null)
            val fontSize = (h / 80.0).coerceIn(11.0, 22.0).roundToInt()
            g.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
            val metrics = g.fontMetrics
            g.stroke = BasicStroke(max(2f, (h / 600f)))
            for (mark in marks) {
                val x = (mark.box.x * scale).roundToInt()
                val y = (mark.box.y * scale).roundToInt()
                val bw = max(1, (mark.box.width * scale).roundToInt())
                val bh = max(1, (mark.box.height * scale).roundToInt())
                g.color = BOX
                g.drawRect(x, y, bw, bh)
                val tw = metrics.stringWidth(mark.label) + 6
                val th = metrics.ascent + 2
                val tx = x.coerceIn(0, max(0, w - tw))
                val ty = (y - th).let { if (it < 0) y else it }.coerceIn(0, max(0, h - th))
                g.fillRect(tx, ty, tw, th)
                g.color = Color.WHITE
                g.drawString(mark.label, tx + 3, ty + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
        val bytes = jpeg(out) ?: return shot
        return Screenshot(bytes, "image/jpeg", w, h)
    }

    private fun jpeg(image: BufferedImage): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val buffer = ByteArrayOutputStream()
        return try {
            ImageIO.createImageOutputStream(buffer).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            buffer.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            writer.dispose()
        }
    }

    private companion object {
        /** Magenta stands out on most app colors, light or dark. */
        val BOX = Color(230, 0, 160)
    }
}
