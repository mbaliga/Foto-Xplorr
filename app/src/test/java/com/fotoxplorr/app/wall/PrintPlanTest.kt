package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The pure side of the print export: what size the file is, which part of the photo it keeps, what it is called. */
class PrintPlanTest {

    private fun plan(srcW: Int, srcH: Int, wCm: Double, hCm: Double, dpi: Int = 300, maxEdge: Int = 8192, maxPixels: Long = 64_000_000L) =
        PrintPlanner.plan(srcW, srcH, PrintSize(wCm, hCm), dpi, maxEdge, maxPixels)!!

    // --- the crop ---

    @Test
    fun `a wider photo than the frame loses its sides, centred`() {
        val crop = coverRect(4000, 3000, aspect = 0.5)
        assertEquals(PixelRect(left = 1250, top = 0, width = 1500, height = 3000), crop)
    }

    @Test
    fun `a taller photo than the frame loses its top and bottom, centred`() {
        val crop = coverRect(3000, 4000, aspect = 2.0)
        assertEquals(PixelRect(left = 0, top = 1250, width = 3000, height = 1500), crop)
    }

    @Test
    fun `a photo of the frame's own shape is kept whole`() {
        assertEquals(PixelRect(0, 0, 4000, 3000), coverRect(4000, 3000, aspect = 4.0 / 3))
    }

    @Test
    fun `the crop is never bigger than the photo, whatever rounding does`() {
        for (aspect in listOf(0.3, 0.7071, 1.0, 1.4142, 1.5, 3.3)) for ((w, h) in listOf(1 to 1, 7 to 5, 4032 to 3024, 3023 to 4031)) {
            val c = coverRect(w, h, aspect)
            assertTrue("$w x $h at $aspect: $c", c.left >= 0 && c.top >= 0 && c.width in 1..w && c.height in 1..h)
            assertTrue(c.left + c.width <= w && c.top + c.height <= h)
        }
    }

    @Test
    fun `the crop has the frame's shape to within a pixel`() {
        val c = coverRect(4032, 3024, aspect = 30.0 / 40)
        assertTrue(abs(c.width.toDouble() / c.height - 0.75) < 1.0 / c.height)
    }

    // --- the size of the file ---

    @Test
    fun `a photo with enough pixels gives the full size at 300 dpi`() {
        // 30 x 40 cm at 300 dpi is 3543 x 4724; a 48 MP photo has plenty.
        val p = plan(6000, 8000, 30.0, 40.0)
        assertEquals(3543, p.outW)
        assertEquals(4724, p.outH)
        assertEquals(300, p.dpi)
        assertEquals(PrintLimit.NONE, p.limitedBy)
    }

    @Test
    fun `every standard size comes out at exactly 300 dpi unless it is over the edge limit`() {
        for (size in FramePresets.all) {
            val p = plan(9000, 9000, size.wCm.toDouble(), size.hCm.toDouble())
            val longEdgeAt300 = PrintMath.pixelsForCm(maxOf(size.wCm, size.hCm).toDouble(), 300)
            if (longEdgeAt300 <= 8192) {
                assertEquals(size.label, 300, p.dpi)
                assertEquals(size.label, PrintLimit.NONE, p.limitedBy)
            } else {
                assertEquals(size.label, PrintLimit.SIZE_LIMIT, p.limitedBy)
                assertTrue(size.label, p.dpi < 300 && maxOf(p.outW, p.outH) <= 8192)
            }
        }
    }

    @Test
    fun `a photo with too few pixels is not made up to size, and the density says so`() {
        // A 12 MP photo (3000 x 4000) to 30 x 40 cm: 3543 x 4724 would be needed, so the file is the photo's own 3000 x 4000.
        val p = plan(3000, 4000, 30.0, 40.0)
        assertEquals(PrintLimit.PHOTO, p.limitedBy)
        assertEquals(3000, p.outW)
        assertEquals(4000, p.outH)
        assertEquals(254, p.dpi) // 3000 px over 11.81 in
        // The pixels and the density together still describe the real size: 3000 px at 254 dpi is 30 cm.
        assertEquals(30.0, p.outW.toDouble() / p.dpi * 2.54, 0.2)
    }

    @Test
    fun `the density is what the pixels give at the printed size, so it matches PrintMath`() {
        val p = plan(3000, 4000, 40.0, 50.0)
        assertEquals(PrintMath.effectiveDpi(3000, 4000, 40.0, 50.0), p.dpi.toDouble(), 1.0)
        assertEquals(PrintLimit.PHOTO, p.limitedBy)
    }

    @Test
    fun `the largest print is held to the edge and pixel limits`() {
        // 61 x 91 cm at 300 dpi would be 7205 x 10748: over the 8192 edge.
        val p = plan(9000, 9000, 61.0, 91.0)
        assertEquals(PrintLimit.SIZE_LIMIT, p.limitedBy)
        assertTrue(p.outH <= 8192 && p.outW <= 8192)
        assertEquals(8192, p.outH)
        assertTrue(p.dpi in 220..230) // 8192 px over 35.8 in
    }

    @Test
    fun `the pixel budget holds even when each edge is under the limit`() {
        val p = plan(20000, 20000, 90.0, 90.0, maxEdge = 20000, maxPixels = 50_000_000L)
        assertEquals(PrintLimit.SIZE_LIMIT, p.limitedBy)
        assertTrue(p.outW.toLong() * p.outH <= 50_000_000L)
    }

    @Test
    fun `a stepped-down edge keeps the shape and lowers the density`() {
        val full = plan(8000, 6000, 30.0, 40.0)
        val smaller = plan(8000, 6000, 30.0, 40.0, maxEdge = 2362)
        assertEquals(2362, smaller.outH)
        assertTrue(smaller.dpi < full.dpi)
        assertEquals(full.outW.toDouble() / full.outH, smaller.outW.toDouble() / smaller.outH, 0.002)
    }

    @Test
    fun `the file has the frame's shape and the crop has the file's`() {
        val p = plan(4032, 3024, 21.0, 29.7)
        assertEquals(21.0 / 29.7, p.outW.toDouble() / p.outH, 0.002)
        assertEquals(p.outW.toDouble() / p.outH, p.crop.width.toDouble() / p.crop.height, 0.002)
    }

    @Test
    fun `the file is never bigger than the part of the photo it comes from`() {
        for (size in FramePresets.all) for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 1200 to 1200, 640 to 480)) {
            val p = plan(w, h, size.wCm.toDouble(), size.hCm.toDouble())
            assertTrue("${size.label} from $w x $h: $p", p.outW <= p.crop.width + 1 && p.outH <= p.crop.height + 1)
        }
    }

    @Test
    fun `a tiny photo still gives a one-pixel-or-more file and a density of at least 1`() {
        val p = plan(2, 2, 100.0, 100.0)
        assertTrue(p.outW >= 1 && p.outH >= 1 && p.dpi >= 1)
    }

    @Test
    fun `a size or a photo of nothing has no plan`() {
        assertNull(PrintPlanner.plan(0, 100, PrintSize(30.0, 40.0)))
        assertNull(PrintPlanner.plan(100, 0, PrintSize(30.0, 40.0)))
        assertNull(PrintPlanner.plan(100, 100, PrintSize(0.0, 40.0)))
        assertNull(PrintPlanner.plan(100, 100, PrintSize(30.0, -1.0)))
        assertNull(PrintPlanner.plan(100, 100, PrintSize(30.0, 40.0), wantDpi = 0))
    }

    // --- decoding no more than is needed ---

    @Test
    fun `a photo much bigger than the print is decoded smaller`() {
        val p = plan(8000, 6000, 21.0, 29.7, dpi = 150) // A4 at 150 dpi: 1240 x 1754
        val edge = decodeEdgeFor(8000, 6000, p, maxEdge = 8192)
        // The 4:3 photo is cut to A4's shape, so the long edge is scaled by the shorter side's ratio.
        assertEquals(8000 * (p.outH.toDouble() / p.crop.height), edge.toDouble(), 2.0)
        assertTrue(edge < 8000)
    }

    @Test
    fun `a photo no bigger than the print is decoded in full`() {
        val p = plan(3000, 4000, 30.0, 40.0)
        assertEquals(4000, decodeEdgeFor(3000, 4000, p, maxEdge = 8192))
    }

    @Test
    fun `the decode is held to the export edge`() {
        val p = plan(12000, 9000, 61.0, 91.0, maxEdge = 20000, maxPixels = 200_000_000L)
        assertEquals(8192, decodeEdgeFor(12000, 9000, p, maxEdge = 8192))
    }

    // --- resampling in steps ---

    @Test
    fun `no intermediate steps when the photo is less than twice the file`() {
        assertEquals(emptyList<Pair<Int, Int>>(), resampleSteps(1900, 1900, 1000, 1000))
        assertEquals(emptyList<Pair<Int, Int>>(), resampleSteps(500, 500, 1000, 1000))
    }

    @Test
    fun `a big reduction goes down by halves and ends within a factor of two of the file`() {
        val steps = resampleSteps(8000, 6000, 700, 525)
        assertEquals(listOf(4000 to 3000, 2000 to 1500, 1000 to 750), steps)
        for ((w, h) in steps) assertTrue(w >= 700 && h >= 525)
    }

    @Test
    fun `halving never goes below the file on either side`() {
        val steps = resampleSteps(8000, 1000, 1000, 800)
        for ((w, h) in steps) assertTrue(w >= 1000 && h >= 800)
    }

    // --- what is written into the file ---

    @Test
    fun `the resolution tags say dots per inch, the same on both axes`() {
        val tags = ResolutionTags.of(300)
        assertEquals("300/1", tags.xResolution)
        assertEquals("300/1", tags.yResolution)
        assertEquals("2", tags.unit) // inches
        assertEquals("190/1", ResolutionTags.of(190).xResolution)
    }

    // --- naming ---

    @Test
    fun `the name is the photo's stem, a marker, the size and jpg`() {
        assertEquals("IMG_1-print-30x40cm.jpg", printFileName("IMG_1.jpg", PrintSize(30.0, 40.0), emptySet()))
        assertEquals("a-print-29.7x42cm.jpg", printFileName("a.png", PrintSize(29.7, 42.0), emptySet()))
    }

    @Test
    fun `odd names are handled like the editor's`() {
        assertEquals("photo-print-20x30cm.jpg", printFileName("", PrintSize(20.0, 30.0), emptySet()))
        assertEquals("noext-print-20x30cm.jpg", printFileName("noext", PrintSize(20.0, 30.0), emptySet()))
        assertEquals("a.b.c-print-20x30cm.jpg", printFileName("a.b.c.heic", PrintSize(20.0, 30.0), emptySet()))
        assertEquals(".hidden-print-20x30cm.jpg", printFileName(".hidden", PrintSize(20.0, 30.0), emptySet()))
    }

    @Test
    fun `a name already taken gets a number, the next free one`() {
        val size = PrintSize(30.0, 40.0)
        val taken = setOf("a-print-30x40cm.jpg")
        assertEquals("a-print-30x40cm (2).jpg", printFileName("a.jpg", size, taken))
        assertEquals("a-print-30x40cm (3).jpg", printFileName("a.jpg", size, taken + "a-print-30x40cm (2).jpg"))
        // Not fooled by a hole: the first free number wins.
        assertEquals("a-print-30x40cm (2).jpg", printFileName("a.jpg", size, taken + "a-print-30x40cm (3).jpg"))
    }

    @Test
    fun `names are compared without regard to case`() {
        assertEquals("a-print-30x40cm (2).jpg", printFileName("a.jpg", PrintSize(30.0, 40.0), setOf("A-PRINT-30X40CM.JPG")))
    }

    @Test
    fun `printing a print does not stack the marker, and a different size is a different name`() {
        val size = PrintSize(30.0, 40.0)
        assertEquals("a-print-30x40cm.jpg", printFileName("a-print-30x40cm.jpg", size, emptySet()))
        assertEquals("a-print-50x70cm.jpg", printFileName("a-print-30x40cm.jpg", PrintSize(50.0, 70.0), emptySet()))
        assertNotEquals(printFileName("a.jpg", size, emptySet()), printFileName("a.jpg", PrintSize(50.0, 70.0), emptySet()))
    }

    @Test
    fun `the name never ends up equal to the photo it came from`() {
        // A source that is itself called exactly the new name still gets a number in a library that lists it.
        val sourceName = "a-print-30x40cm.jpg"
        assertNotEquals(sourceName, printFileName("a.jpg", PrintSize(30.0, 40.0), setOf(sourceName)))
    }
}
