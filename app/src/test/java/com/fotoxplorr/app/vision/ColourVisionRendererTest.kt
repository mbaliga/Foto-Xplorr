package com.fotoxplorr.app.vision

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filter applied to a real (Robolectric) bitmap: it must equal the pure per-pixel path, leave the
 * source alone, keep alpha, and stop when asked. What it cannot show is how a photograph looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ColourVisionRendererTest {

    private val palette = intArrayOf(
        VisionTestKit.argb(220, 40, 40),
        VisionTestKit.argb(40, 160, 40),
        VisionTestKit.argb(30, 60, 220),
        VisionTestKit.argb(250, 220, 30),
        VisionTestKit.argb(128, 128, 128),
        VisionTestKit.argb(255, 255, 255),
    )

    private fun source(w: Int = 12, h: Int = 5) = ColourVisionKit.bitmap(w, h) { x, y -> palette[(x + y * 3) % palette.size] }

    @Test
    fun `a simulated bitmap equals the pure simulation of every pixel`() = runBlocking {
        val src = source()
        val key = FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)
        val out = ColourVisionRenderer.filter(src, key)
        val want = Simulation.simulateAll(ColourVisionKit.pixels(src), Deficiency.DEUTAN, 1.0)
        assertArrayEquals(want, ColourVisionKit.pixels(out))
    }

    @Test
    fun `a daltonized bitmap equals the pure daltonizer at the chosen strength`() = runBlocking {
        val src = source()
        val out = ColourVisionRenderer.filter(src, FilterKey(Deficiency.PROTAN, VisionKind.HELP, 6))
        val want = Daltonizer.daltonizeAll(ColourVisionKit.pixels(src), Deficiency.PROTAN, 0.6)
        assertArrayEquals(want, ColourVisionKit.pixels(out))
    }

    @Test
    fun `the source bitmap is not touched and the result is a new bitmap of the same size`() = runBlocking {
        val src = source()
        val before = ColourVisionKit.pixels(src)
        val out = ColourVisionRenderer.filter(src, FilterKey(Deficiency.TRITAN, VisionKind.SIMULATE, 10))
        assertNotSame(src, out)
        assertEquals(src.width, out.width)
        assertEquals(src.height, out.height)
        assertEquals(Bitmap.Config.ARGB_8888, out.config)
        assertFalse(src.isRecycled)
        assertArrayEquals(before, ColourVisionKit.pixels(src))
    }

    @Test
    fun `strength zero point one barely moves a colour and full strength moves it a lot`() = runBlocking {
        val src = ColourVisionKit.bitmap(1, 1) { _, _ -> VisionTestKit.argb(220, 40, 40) }
        val weak = ColourVisionKit.pixels(ColourVisionRenderer.filter(src, FilterKey(Deficiency.PROTAN, VisionKind.SIMULATE, 1)))[0]
        val full = ColourVisionKit.pixels(ColourVisionRenderer.filter(src, FilterKey(Deficiency.PROTAN, VisionKind.SIMULATE, 10)))[0]
        val original = VisionTestKit.argb(220, 40, 40)
        assertTrue(ColourSpace.deltaE(original, weak) < ColourSpace.deltaE(original, full))
        assertTrue(ColourSpace.deltaE(original, full) > 20)
    }

    @Test
    fun `a bigger bitmap than one band is converted in full`() = runBlocking {
        // 600 x 600 = 360000 pixels, more than one band of 262144, so the loop runs at least twice.
        val src = ColourVisionKit.bitmap(600, 600) { x, y -> palette[(x / 7 + y / 11) % palette.size] }
        val bands = ArrayList<Float>()
        val out = ColourVisionRenderer.filter(src, FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)) { bands += it }
        assertTrue(bands.size >= 2)
        assertEquals(1f, bands.last(), 0f)
        assertEquals(bands.sorted(), bands)
        val want = Simulation.simulateAll(ColourVisionKit.pixels(src), Deficiency.DEUTAN, 1.0)
        assertArrayEquals(want, ColourVisionKit.pixels(out))
    }

    @Test
    fun `a filter cancelled after its first band stops there`() = runBlocking {
        val src = ColourVisionKit.bitmap(600, 600) { _, _ -> VisionTestKit.argb(200, 30, 30) }
        var bands = 0
        lateinit var self: Job
        self = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            ColourVisionRenderer.filter(src, FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10)) {
                bands++
                self.cancel()
            }
        }
        self.start()
        self.join()
        assertTrue(self.isCancelled)
        assertEquals("it did not carry on to the second band", 1, bands)
        assertFalse(src.isRecycled)
    }

    @Test
    fun `alpha passes through a filtered see-through bitmap`() = runBlocking {
        val src = ColourVisionKit.bitmap(4, 1) { x, _ -> VisionTestKit.argb(220, 40, 40, a = 255 - x * 60) }
        val out = ColourVisionRenderer.filter(src, FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10))
        // setPixels/getPixels premultiply and un-premultiply, so compare the alpha channel only.
        assertEquals(ColourVisionKit.pixels(src).map { ColourSpace.alpha(it) }, ColourVisionKit.pixels(out).map { ColourSpace.alpha(it) })
    }

    // ---- the index and the patterns ----

    @Test
    fun `a red and green picture is cut to its two colours and a deutan sees them alike`() = runBlocking {
        val src = ColourVisionKit.bitmap(40, 20) { x, _ -> if (x < 20) VisionTestKit.argb(200, 70, 20) else VisionTestKit.argb(120, 130, 20) }
        val image = ColourVisionRenderer.index(src)
        assertEquals(2, image.centres.size)
        val layer = ColourVisionRenderer.hatch(image, FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10))
        assertTrue("a deutan would take these for one colour", layer.width > 0)
        assertEquals(HatchOverlay.LONG_EDGE, maxOf(layer.width, layer.height))
        assertTrue(layer.argb.any { it ushr 24 != 0 })
    }

    @Test
    fun `colours that differ in blue and yellow get no patterns for a deutan`() = runBlocking {
        val src = ColourVisionKit.bitmap(40, 20) { x, _ -> if (x < 20) VisionTestKit.argb(20, 40, 220) else VisionTestKit.argb(240, 220, 30) }
        val layer = ColourVisionRenderer.hatch(ColourVisionRenderer.index(src), FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 10))
        assertEquals(0, layer.width)
    }

    @Test
    fun `a big picture is quantised on a small copy`() = runBlocking {
        val src = ColourVisionKit.bitmap(1000, 500) { x, _ -> if (x < 500) VisionTestKit.argb(200, 70, 20) else VisionTestKit.argb(20, 40, 200) }
        val image = ColourVisionRenderer.index(src)
        assertEquals(ColourVisionRenderer.INDEX_EDGE, maxOf(image.width, image.height))
        assertEquals(2.0, image.width.toDouble() / image.height, 0.05)
        assertFalse("the caller's bitmap is not recycled", src.isRecycled)
    }

    @Test
    fun `a small picture is used as it is`() = runBlocking {
        val src = ColourVisionKit.bitmap(10, 6) { _, _ -> VisionTestKit.argb(90, 90, 90) }
        val image = ColourVisionRenderer.index(src)
        assertEquals(10, image.width)
        assertEquals(6, image.height)
        assertFalse(src.isRecycled)
    }

    @Test
    fun `the same picture always gets the same patterns`() = runBlocking {
        val src = ColourVisionKit.bitmap(60, 30) { x, y -> palette[(x / 10 + y / 15) % 3] }
        val key = FilterKey(Deficiency.PROTAN, VisionKind.SIMULATE, 10)
        val a = ColourVisionRenderer.hatch(ColourVisionRenderer.index(src), key)
        val b = ColourVisionRenderer.hatch(ColourVisionRenderer.index(src), key)
        assertArrayEquals(a.argb, b.argb)
    }
}
