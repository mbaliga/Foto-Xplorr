package com.fotoxplorr.app.animate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Colour reduction on its own: palettes, the exact nearest-colour search, and error diffusion. */
class QuantizerTest {

    private fun hist(pixels: IntArray): ColorHistogram = ColorHistogram().also { h -> pixels.forEach { h.add(it) } }

    private fun meanError(pixels: IntArray, palette: IntArray): Double {
        val lookup = PaletteLookup(palette)
        var sum = 0.0
        for (p in pixels) sum += rgbDistance2(p, palette[lookup.nearest(p)])
        return sum / pixels.size / 3.0
    }

    @Test
    fun `pixels that already fit become exactly their own palette`() {
        val rnd = Random(1)
        val colors = IntArray(40) { rnd.nextInt(0xFFFFFF) }
        val pixels = IntArray(5000) { colors[rnd.nextInt(colors.size)] }
        val pal = MedianCut.build(hist(pixels), 255)
        assertEquals(colors.toSet(), pal.colors.toSet())
        assertEquals(0.0, meanError(pixels, pal.colors), 0.0)
    }

    @Test
    fun `two colours the 5-5-5 grid would merge are still told apart when they fit`() {
        val a = 0x80_80_80
        val b = 0x81_80_80 // same 5-bit cell as a
        val pal = MedianCut.build(hist(intArrayOf(a, b, a, b)), 4)
        assertEquals(setOf(a, b), pal.colors.toSet())
    }

    @Test
    fun `more than the limit distinct colours is reported as not exact`() {
        val h = ColorHistogram()
        for (i in 0 until 300) h.add(i * 1000)
        assertNull(h.exactColors(255))
        val small = ColorHistogram()
        for (i in 0 until 255) small.add(i * 1000)
        assertNotNull(small.exactColors(255))
        assertEquals(255, small.exactColors(255)!!.size)
    }

    @Test
    fun `a photo gets at most the colours asked for and beats a fixed 3-3-2 palette`() {
        val px = AnimTestKit.photoFrame(120, 90, 0, 60, 45)
        val rgb = IntArray(px.size) { px[it] and 0xFFFFFF }
        for (n in listOf(255, 128, 32, 8)) {
            val pal = MedianCut.build(hist(rgb), n)
            assertTrue("n=$n got ${pal.size}", pal.size in 1..n)
        }
        val adaptive = MedianCut.build(hist(rgb), 255).colors
        val fixed = IntArray(256) { (((it shr 5) * 36) shl 16) or ((((it shr 2) and 7) * 36) shl 8) or ((it and 3) * 85) }
        val eA = meanError(rgb, adaptive)
        val eF = meanError(rgb, fixed)
        assertTrue("adaptive=$eA fixed=$eF", eA < eF / 3)
    }

    @Test
    fun `more colours never fit worse`() {
        val px = AnimTestKit.photoFrame(100, 80, 0, 50, 40)
        val rgb = IntArray(px.size) { px[it] and 0xFFFFFF }
        var last = Double.MAX_VALUE
        for (n in listOf(4, 16, 64, 255)) {
            val e = meanError(rgb, MedianCut.build(hist(rgb), n).colors)
            assertTrue("n=$n e=$e last=$last", e <= last + 1e-9)
            last = e
        }
    }

    @Test
    fun `a colour that dominates the picture is kept almost exactly`() {
        val rnd = Random(9)
        val main = 0x3A7BD5
        val pixels = IntArray(20_000) { if (it < 12_000) main else rnd.nextInt(0xFFFFFF) }
        val pal = MedianCut.build(hist(pixels), 64).colors
        val lookup = PaletteLookup(pal)
        assertTrue(rgbDistance2(main, pal[lookup.nearest(main)]) <= 12)
    }

    @Test
    fun `an empty histogram gives a one colour palette`() {
        assertEquals(1, MedianCut.build(ColorHistogram(), 255).size)
    }

    @Test
    fun `the palette is the same every time`() {
        val px = AnimTestKit.photoFrame(80, 60, 0, 40, 30)
        val rgb = IntArray(px.size) { px[it] and 0xFFFFFF }
        assertTrue(MedianCut.build(hist(rgb), 200).colors.contentEquals(MedianCut.build(hist(rgb), 200).colors))
    }

    @Test
    fun `the fast nearest-colour search always agrees with a scan of the whole palette`() {
        val rnd = Random(77)
        for (size in listOf(1, 2, 3, 5, 17, 100, 255)) {
            val pal = IntArray(size) { rnd.nextInt(0xFFFFFF) }
            val lookup = PaletteLookup(pal)
            repeat(4000) {
                val c = rnd.nextInt(0xFFFFFF)
                val got = lookup.nearest(c)
                var best = 0
                var bd = Int.MAX_VALUE
                for (j in pal.indices) {
                    val d = rgbDistance2(c, pal[j])
                    if (d < bd) {
                        bd = d
                        best = j
                    }
                }
                assertEquals("size=$size colour=${c.toString(16)}", bd, rgbDistance2(c, pal[got]))
                assertEquals(best, got) // ties go to the lower index in both
            }
        }
    }

    @Test
    fun `the nearest search is exact for palettes clustered inside one cell`() {
        val rnd = Random(5)
        val pal = IntArray(60) { 0x404040 + (rnd.nextInt(8) shl 16) + (rnd.nextInt(8) shl 8) + rnd.nextInt(8) }
        val lookup = PaletteLookup(pal)
        repeat(3000) {
            val c = 0x404040 + (rnd.nextInt(10) shl 16) + (rnd.nextInt(10) shl 8) + rnd.nextInt(10)
            val got = lookup.nearest(c)
            val bd = pal.minOf { rgbDistance2(c, it) }
            assertEquals(bd, rgbDistance2(c, pal[got]))
        }
    }

    // ---- dither ----

    private fun mapAll(argb: IntArray, w: Int, h: Int, pal: IntArray, strength: Float, mask: ByteArray? = null, transparent: Int = pal.size): ByteArray {
        val out = ByteArray(w * h)
        Dither.map(argb, w, 0, 0, w, h, mask, PaletteLookup(pal), pal, transparent, strength, out)
        return out
    }

    @Test
    fun `no dither is plain nearest colour`() {
        val pal = intArrayOf(0x000000, 0xFFFFFF)
        val w = 8
        val px = IntArray(w) { 0xFF000000.toInt() or (it * 36 * 0x010101) }
        val out = mapAll(px, w, 1, pal, 0f)
        for (x in 0 until w) assertEquals(if (x * 36 < 128) 0 else 1, out[x].toInt())
    }

    @Test
    fun `dither of a flat mid grey between black and white keeps the average at mid grey`() {
        val pal = intArrayOf(0x000000, 0xFFFFFF)
        val w = 64
        val h = 64
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val out = mapAll(px, w, h, pal, 1f)
        val whites = out.count { it.toInt() == 1 }
        assertTrue("whites=$whites of ${w * h}", Math.abs(whites - w * h / 2) < w * h / 20)
        // And it is not a stripe: both colours appear in nearly every row.
        for (y in 0 until h) {
            val rowWhites = (0 until w).count { out[y * w + it].toInt() == 1 }
            assertTrue("row $y whites=$rowWhites", rowWhites in 8..(w - 8))
        }
    }

    @Test
    fun `strength scales the error spread`() {
        val pal = intArrayOf(0x000000, 0xFFFFFF)
        val w = 64
        val h = 16
        val px = IntArray(w * h) { 0xFF606060.toInt() }
        val full = mapAll(px, w, h, pal, 1f).count { it.toInt() == 1 }
        val half = mapAll(px, w, h, pal, 0.5f).count { it.toInt() == 1 }
        val none = mapAll(px, w, h, pal, 0f).count { it.toInt() == 1 }
        assertEquals(0, none)
        assertTrue("none=$none half=$half full=$full", half in 1 until full)
        // Full strength keeps the average: 0x60 of 0xFF is 37.6% white.
        assertEquals(0.376 * w * h, full.toDouble(), 0.04 * w * h)
    }

    @Test
    fun `masked pixels get the transparent index and give or take no error`() {
        val pal = intArrayOf(0x000000, 0xFFFFFF)
        val w = 6
        val h = 4
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val mask = ByteArray(w * h) { if (it % w < 2) 0 else 1 }
        val out = mapAll(px, w, h, pal, 1f, mask, transparent = 2)
        for (i in out.indices) assertEquals(if (mask[i].toInt() == 0) 2 else out[i].toInt().coerceIn(0, 1), out[i].toInt())
        // Without a mask, alpha under the cut-off is the mask.
        val clear = IntArray(w * h) { if (it % w < 2) 0 else 0xFF808080.toInt() }
        val out2 = mapAll(clear, w, h, pal, 0f, null, transparent = 2)
        for (i in out2.indices) assertEquals(if (i % w < 2) 2 else out2[i].toInt(), out2[i].toInt())
        assertTrue(out2.count { it.toInt() == 2 } == 2 * h)
    }

    @Test
    fun `a sub-rectangle is mapped in place`() {
        val pal = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF)
        val w = 10
        val h = 8
        val px = IntArray(w * h) { 0xFF000000.toInt() or pal[(it % w + it / w) % 3] }
        val out = ByteArray(4 * 3)
        Dither.map(px, w, 3, 2, 7, 5, null, PaletteLookup(pal), pal, 3, 0f, out)
        for (ry in 0 until 3) for (rx in 0 until 4) assertEquals((3 + rx + 2 + ry) % 3, out[ry * 4 + rx].toInt())
    }
}
