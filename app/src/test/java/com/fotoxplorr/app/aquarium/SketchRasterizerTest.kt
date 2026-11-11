package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import android.graphics.Color
import com.fotoxplorr.app.lift.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/** The rasteriser on real bitmaps. Proves sizes, borders and the fill; the look of the ink is a device check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SketchRasterizerTest {

    private val red = 0xFFE53935.toInt()

    private fun ring(cx: Float = 0.5f, cy: Float = 0.5f, rx: Float = 0.2f, ry: Float = 0.25f, n: Int = 48) =
        List(n + 1) {
            val a = (2 * Math.PI * (it % n) / n).toFloat()
            NormPoint(cx + rx * cos(a), cy + ry * sin(a))
        }

    private fun sketchOf(points: List<NormPoint>, width: Float = 0.025f, argb: Int = red, autoFill: Boolean = true) =
        Sketch().add(points, argb, width, autoFill)

    private fun px(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y)

    @Test
    fun `nothing drawn gives no sprite`() {
        assertNull(SketchRasterizer.rasterize(emptyList()))
    }

    @Test
    fun `a closed ring is filled in the middle and clear at the corners`() {
        val bmp = SketchRasterizer.rasterize(sketchOf(ring()).strokes)!!
        assertEquals(red, px(bmp, bmp.width / 2, bmp.height / 2))
        assertEquals(0, Color.alpha(px(bmp, 0, 0)))
        assertEquals(0, Color.alpha(px(bmp, bmp.width - 1, bmp.height - 1)))
    }

    @Test
    fun `with auto fill off the middle of a ring stays clear`() {
        val bmp = SketchRasterizer.rasterize(sketchOf(ring(), autoFill = false).strokes)!!
        assertEquals(0, Color.alpha(px(bmp, bmp.width / 2, bmp.height / 2)))
    }

    @Test
    fun `a nearly closed ring is closed and filled`() {
        val open = ring().dropLast(2)
        val s = sketchOf(open)
        assertTrue(s.strokes.single().filled)
        val bmp = SketchRasterizer.rasterize(s.strokes)!!
        assertEquals(red, px(bmp, bmp.width / 2, bmp.height / 2))
    }

    @Test
    fun `a tall loop the board calls closed is filled in the sprite too`() {
        // 0.10 wide, 0.30 tall, MEDIUM brush, ends 0.03 apart across the top: closed in board
        // units, but a wider gap relative to the sprite window, which used to be judged again there.
        val delta = Math.asin(0.3).toFloat()
        val n = 48
        val loop = List(n + 1) {
            val a = (-Math.PI / 2 + delta + (2 * Math.PI - 2 * delta) * it / n).toFloat()
            NormPoint(0.5f + 0.05f * cos(a), 0.5f + 0.15f * sin(a))
        }
        val s = sketchOf(loop, width = BrushSize.MEDIUM.width)
        assertTrue(s.strokes.single().filled)
        val bmp = SketchRasterizer.rasterize(s.strokes)!!
        assertEquals(red, px(bmp, bmp.width / 2, bmp.height / 2))
    }

    @Test
    fun `an open line is not filled`() {
        val line = List(10) { NormPoint(0.1f + 0.08f * it, 0.5f) }
        val bmp = SketchRasterizer.rasterize(sketchOf(line).strokes)!!
        // Mostly clear: only a thin band of ink.
        var opaque = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (Color.alpha(px(bmp, x, y)) > 8) opaque++
        assertTrue(opaque < bmp.width * bmp.height)
        assertTrue(bmp.height < bmp.width / 4)
    }

    @Test
    fun `every side has a clear border of at least the set width`() {
        val bmp = SketchRasterizer.rasterize(sketchOf(ring()).strokes)!!
        for (i in 0 until SpritePixels.BORDER) {
            for (x in 0 until bmp.width) {
                assertEquals(0, Color.alpha(px(bmp, x, i)))
                assertEquals(0, Color.alpha(px(bmp, x, bmp.height - 1 - i)))
            }
            for (y in 0 until bmp.height) {
                assertEquals(0, Color.alpha(px(bmp, i, y)))
                assertEquals(0, Color.alpha(px(bmp, bmp.width - 1 - i, y)))
            }
        }
    }

    @Test
    fun `a stroke along the very edge of the board still gets its border`() {
        val edge = List(10) { NormPoint(0f, 0.1f + 0.08f * it) }
        val bmp = SketchRasterizer.rasterize(sketchOf(edge).strokes)!!
        for (y in 0 until bmp.height) assertEquals(0, Color.alpha(px(bmp, 0, y)))
    }

    @Test
    fun `the sprite is never bigger than the maximum side`() {
        val big = SketchRasterizer.rasterize(sketchOf(ring(rx = 0.5f, ry = 0.5f), width = 0.05f).strokes)!!
        assertTrue("${big.width}x${big.height}", big.width <= SpritePixels.MAX_SIDE && big.height <= SpritePixels.MAX_SIDE)
    }

    @Test
    fun `a small doodle gets nearly as many pixels as a big one`() {
        val small = SketchRasterizer.rasterize(sketchOf(ring(rx = 0.05f, ry = 0.06f), width = 0.01f).strokes)!!
        val long = maxOf(small.width, small.height)
        // Drawn from vectors at full size, then trimmed: far more than the ~50 px of an enlarged pixel crop.
        assertTrue("$long", long >= 250)
    }

    @Test
    fun `a single dot makes a sprite and keeps its colour`() {
        val dot = Sketch().add(listOf(NormPoint(0.5f, 0.5f)), 0xFF1E88E5.toInt(), 0.05f)
        val bmp = SketchRasterizer.rasterize(dot.strokes)!!
        assertNotNull(bmp)
        assertEquals(0xFF1E88E5.toInt(), px(bmp, bmp.width / 2, bmp.height / 2))
        assertTrue(Math.abs(bmp.width - bmp.height) <= 2)
    }

    @Test
    fun `a later stroke draws over an earlier one`() {
        val blue = 0xFF1E88E5.toInt()
        val s = sketchOf(ring(), width = 0.02f).add(listOf(NormPoint(0.5f, 0.5f)), blue, 0.08f)
        val bmp = SketchRasterizer.rasterize(s.strokes)!!
        assertEquals(blue, px(bmp, bmp.width / 2, bmp.height / 2))
    }

    @Test
    fun `the sprite aspect follows the drawing`() {
        val wide = List(10) { NormPoint(0.1f + 0.08f * it, 0.4f + 0.02f * (it % 2) * 5f) }
        val bmp = SketchRasterizer.rasterize(sketchOf(wide, width = 0.03f).strokes)!!
        assertTrue(bmp.width > bmp.height)
    }

    @Test
    fun `every ready shape rasterises with its body filled`() {
        for (shape in ReadyShape.entries) {
            val sketch = ReadyShapes.addTo(Sketch(), shape, red, BrushSize.MEDIUM.width)
            val bmp = SketchRasterizer.rasterize(sketch.strokes)
            assertNotNull(shape.name, bmp)
            assertTrue(shape.name, bmp!!.width <= SpritePixels.MAX_SIDE && bmp.height <= SpritePixels.MAX_SIDE)
        }
        val fish = SketchRasterizer.rasterize(ReadyShapes.addTo(Sketch(), ReadyShape.ROUND_FISH, red, BrushSize.MEDIUM.width).strokes)!!
        // The middle of the body, left of the eye.
        assertEquals(red, px(fish, fish.width / 2, fish.height / 2))
    }

    @Test
    fun `a sprite survives a png round trip with the size its record would state`() {
        val bmp = SketchRasterizer.rasterize(sketchOf(ring()).strokes)!!
        val png = SpriteIo.encodePng(bmp)!!
        val stored = StoredSprite(StoredCreature(CreatureId.generate(), 1L, bmp.width, bmp.height, false), png)
        val back = SpriteIo.decode(stored)!!
        assertEquals(bmp.width, back.width)
        assertEquals(bmp.height, back.height)
        assertEquals(red, px(back, back.width / 2, back.height / 2))
        // A record that states another size does not belong to this picture.
        assertNull(SpriteIo.decode(StoredSprite(stored.record.copy(widthPx = bmp.width + 1), png)))
        assertNull(SpriteIo.decode(StoredSprite(stored.record, byteArrayOf(1, 2, 3))))
        assertNull(SpriteIo.decode(StoredSprite(stored.record.copy(widthPx = 5000, heightPx = 5000), png)))
    }
}
