package dev.phonebridge.ios.runner

import dev.phonebridge.core.screen.Rect
import dev.phonebridge.core.screen.ScreenshotMarker
import dev.phonebridge.core.screen.Screenshot
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AwtScreenshotMarkerTest {
    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test
    fun `scales to the max dimension, draws boxes and returns a JPEG`() {
        val shot = Screenshot(png(1179, 2556), "image/png", 1179, 2556)
        val marks = listOf(ScreenshotMarker.Mark("1", Rect(100, 200, 300, 120)), ScreenshotMarker.Mark("12", Rect(0, 0, 50, 50)))
        val out = AwtScreenshotMarker().mark(shot, marks, 1000)
        assertEquals("image/jpeg", out.mimeType)
        assertEquals(1000, out.height)
        assertEquals(Math.round(1179 * 1000.0 / 2556).toInt(), out.width)
        val decoded = ImageIO.read(ByteArrayInputStream(out.bytes))
        assertEquals(out.width, decoded.width)
        // The first box's left edge (x = 100 px → 39 px after scaling) is drawn, not white.
        val edge = Color(decoded.getRGB(Math.round(100 * 1000.0 / 2556).toInt(), Math.round(260 * 1000.0 / 2556).toInt()))
        assertTrue(edge.red > 150 && edge.green < 120, "box color at the edge, got $edge")
    }

    @Test
    fun `small screenshots keep their size, undecodable ones come back as taken`() {
        val small = AwtScreenshotMarker().mark(Screenshot(png(200, 400), "image/png", 200, 400), emptyList(), 1000)
        assertEquals(400, small.height)
        val junk = Screenshot(byteArrayOf(1, 2, 3), "image/png", 1, 1)
        assertSame(junk, AwtScreenshotMarker().mark(junk, emptyList(), 1000))
    }
}
