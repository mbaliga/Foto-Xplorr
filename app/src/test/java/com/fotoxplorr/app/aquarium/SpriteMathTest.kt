package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteMathTest {

    private fun stroke(vararg pts: Pair<Float, Float>, width: Float = 0.02f) =
        DrawStroke(pts.map { NormPoint(it.first, it.second) }, 0xFF000000.toInt(), width, filled = false)

    @Test
    fun `the window hugs the ink, ink thickness included`() {
        val w = SketchWindow.of(listOf(stroke(0.25f to 0.3f, 0.75f to 0.6f)))!!
        // u runs over the board width; v is y over the 4:3 aspect.
        assertEquals(0.25f - 0.01f - 0.004f, w.u0, 1e-4f)
        assertEquals(0.75f + 0.01f + 0.004f, w.u1, 1e-4f)
        assertEquals(0.3f / Sketch.BOARD_ASPECT - 0.014f, w.v0, 1e-4f)
        assertEquals(0.6f / Sketch.BOARD_ASPECT + 0.014f, w.v1, 1e-4f)
    }

    @Test
    fun `no usable point gives no window`() {
        assertNull(SketchWindow.of(emptyList()))
        assertNull(SketchWindow.of(listOf(stroke(Float.NaN to 0.5f))))
    }

    @Test
    fun `a dot on a corner still has a window with room in it`() {
        val w = SketchWindow.of(listOf(stroke(0f to 0f, width = 0.004f)))!!
        assertTrue(w.width > 0f && w.height > 0f)
        val (pw, ph) = w.pixelSize(284)
        assertTrue(pw >= 1 && ph >= 1)
        val corner = SketchWindow.of(listOf(stroke(1f to 1f, width = 0.004f)))!!
        assertTrue(corner.width > 0f && corner.height > 0f)
    }

    @Test
    fun `the long edge gets exactly the requested pixels and the other keeps the shape`() {
        val w = SketchWindow.of(listOf(stroke(0.1f to 0.4f, 0.9f to 0.6f)))!!
        val (pw, ph) = w.pixelSize(284)
        assertEquals(284, pw)
        assertEquals(w.height / w.width, ph.toFloat() / pw, 0.01f)
    }

    @Test
    fun `window coordinates map the corners to 0 and 1`() {
        val w = SketchWindow.of(listOf(stroke(0.2f to 0.2f, 0.8f to 0.7f)))!!
        val a = w.toWindow(NormPoint(0.2f - 0.01f - 0.004f, (0.2f / Sketch.BOARD_ASPECT - 0.014f) * Sketch.BOARD_ASPECT))
        assertEquals(0f, a.x, 1e-3f)
        assertEquals(0f, a.y, 1e-3f)
        // A brush width is a share of the window's width.
        assertEquals(0.02f / w.width, w.widthInWindow(0.02f), 1e-6f)
    }

    @Test
    fun `a thin horizontal line makes a thin window and a thin sprite`() {
        val w = SketchWindow.of(listOf(stroke(0.1f to 0.5f, 0.9f to 0.5f, width = 0.01f)))!!
        val (pw, ph) = w.pixelSize(284)
        assertEquals(284, pw)
        assertTrue(ph < 20)
    }

    @Test
    fun `paintMask sets only the masked pixels and ignores a wrong size`() {
        val px = IntArray(6)
        SpritePixels.paintMask(px, booleanArrayOf(true, false, true, false, false, true), 0xFF112233.toInt())
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), 0, 0xFF112233.toInt(), 0, 0, 0xFF112233.toInt()), px)
        val untouched = IntArray(4)
        SpritePixels.paintMask(untouched, BooleanArray(3) { true }, 5)
        assertArrayEquals(IntArray(4), untouched)
    }

    @Test
    fun `cropAndPad cuts the bounds and leaves a clear border all round`() {
        val w = 6
        val h = 5
        val px = IntArray(w * h) { 0 }
        px[2 * w + 3] = 0xFFFFFFFF.toInt()
        px[3 * w + 4] = 0xFFFFFFFF.toInt()
        val bounds = OutlineFill.opaqueBounds(px, w, h)!!
        val c = SpritePixels.cropAndPad(px, w, h, bounds, border = 2)!!
        assertEquals(2 + 4, c.width)
        assertEquals(2 + 4, c.height)
        // The border is clear on all four sides.
        for (x in 0 until c.width) for (y in 0 until c.height) {
            val inside = x in 2 until c.width - 2 && y in 2 until c.height - 2
            if (!inside) assertEquals("($x,$y)", 0, c.pixels[y * c.width + x])
        }
        assertEquals(0xFFFFFFFF.toInt(), c.pixels[2 * c.width + 2])
        assertEquals(0xFFFFFFFF.toInt(), c.pixels[3 * c.width + 3])
    }

    @Test
    fun `cropAndPad refuses bad input`() {
        val px = IntArray(12)
        assertNull(SpritePixels.cropAndPad(px, 4, 3, IntBounds(0, 0, 5, 3)))
        assertNull(SpritePixels.cropAndPad(px, 4, 3, IntBounds(2, 2, 2, 3)))
        assertNull(SpritePixels.cropAndPad(px, 5, 3, IntBounds(0, 0, 1, 1)))
        assertNull(SpritePixels.cropAndPad(px, 4, 3, IntBounds(-1, 0, 2, 2)))
        assertNotNull(SpritePixels.cropAndPad(px, 4, 3, IntBounds(0, 0, 4, 3)))
    }

    @Test
    fun `the whole sprite stays within the maximum side`() {
        assertEquals(288, SpritePixels.MAX_SIDE)
    }
}
