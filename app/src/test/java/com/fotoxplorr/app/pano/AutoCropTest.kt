package com.fotoxplorr.app.pano

import com.fotoxplorr.app.pano.AutoCrop.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin

/**
 * The crop must be a rectangle with NO hole pixel in it, and close to the largest one there is.
 * The exact pass is checked against brute force and against hand-drawn masks with known answers;
 * the coarse pass is checked against the exact pass on ragged panorama-shaped masks, including
 * sizes that are not a multiple of the grid, and on a 24 MP mask for time. Measurements are printed
 * with the PANO-CROP tag.
 */
class AutoCropTest {

    /** Rows of '#' (valid) and '.' (not) into a mask. */
    private fun ascii(vararg rows: String): Triple<BooleanArray, Int, Int> {
        val w = rows[0].length
        return Triple(BooleanArray(w * rows.size) { rows[it / w][it % w] == '#' }, w, rows.size)
    }

    private fun rectOf(m: Triple<BooleanArray, Int, Int>) = AutoCrop.largestRect(m.first, m.second, m.third)

    // ---- exact pass ----

    @Test
    fun `a ragged mask gives the known largest rectangle`() {
        val a = ascii(
            "..####..",
            ".######.",
            ".######.",
            "########",
        )
        assertEquals(CropRect(1, 1, 7, 4), rectOf(a))

        val b = ascii(
            "##########",
            ".#########",
            ".#########",
            ".########.",
            "..#######.",
        )
        assertEquals(CropRect(2, 0, 9, 5), rectOf(b))
    }

    @Test
    fun `an empty mask has no crop and a full mask is the whole picture`() {
        assertNull(AutoCrop.largestRect(BooleanArray(7 * 5), 7, 5))
        assertNull(AutoCrop.cropRect(BooleanArray(700 * 500), 700, 500))
        assertEquals(CropRect(0, 0, 7, 5), AutoCrop.largestRect(BooleanArray(35) { true }, 7, 5))
        assertEquals(CropRect(0, 0, 701, 503), AutoCrop.cropRect(BooleanArray(701 * 503) { true }, 701, 503))
    }

    @Test
    fun `a single pixel and one-pixel lines are found`() {
        val one = BooleanArray(9 * 9).also { it[4 * 9 + 6] = true }
        assertEquals(CropRect(6, 4, 7, 5), AutoCrop.largestRect(one, 9, 9))
        assertEquals(CropRect(0, 0, 1, 6), AutoCrop.largestRect(BooleanArray(6) { true }, 1, 6))
        assertEquals(CropRect(0, 0, 6, 1), AutoCrop.largestRect(BooleanArray(6) { true }, 6, 1))
    }

    @Test
    fun `the exact pass matches brute force on random masks`() {
        for (seed in 1L..300L) {
            val random = Random(seed)
            val w = 3 + random.nextInt(12)
            val h = 3 + random.nextInt(10)
            val density = 0.55 + 0.4 * random.nextDouble()
            val mask = BooleanArray(w * h) { random.nextDouble() < density }
            val expected = bruteForceArea(mask, w, h)
            val got = AutoCrop.largestRect(mask, w, h)
            assertEquals("seed $seed area", expected, got?.area ?: 0L)
            if (got != null) assertTrue("seed $seed has a hole", AutoCrop.allValid(mask, w, got))
        }
    }

    private fun bruteForceArea(mask: BooleanArray, w: Int, h: Int): Long {
        var best = 0L
        for (y0 in 0 until h) for (y1 in y0 + 1..h) for (x0 in 0 until w) for (x1 in x0 + 1..w) {
            val rect = CropRect(x0, y0, x1, y1)
            if (rect.area > best && AutoCrop.allValid(mask, w, rect)) best = rect.area
        }
        return best
    }

    // ---- coarse pass ----

    @Test
    fun `a valid block that is not on the coarse grid is recovered to the pixel`() {
        val w = 503
        val h = 397
        val mask = BooleanArray(w * h) { val x = it % w; val y = it / w; x in 13 until 413 && y in 5 until 305 }
        for (scale in listOf(2, 3, 8, 16)) {
            assertEquals("scale $scale", CropRect(13, 5, 413, 305), AutoCrop.cropRect(mask, w, h, scale))
        }
    }

    @Test
    fun `one hole pixel is never inside the crop`() {
        val w = 800
        val h = 600
        val mask = BooleanArray(w * h) { true }
        mask[300 * w + 417] = false
        val rect = AutoCrop.cropRect(mask, w, h)
        assertNotNull(rect)
        assertTrue(AutoCrop.allValid(mask, w, rect!!))
        // The best cut avoids the hole by taking the bigger side: more than half the picture remains.
        assertTrue("area ${rect.area}", rect.area > w.toLong() * h / 2)
    }

    @Test
    fun `a valid strip thinner than a cell is still found`() {
        val w = 640
        val h = 400
        val mask = BooleanArray(w * h) { it / w in 101..105 }
        assertEquals(CropRect(0, 101, w, 106), AutoCrop.cropRect(mask, w, h))
    }

    @Test
    fun `panorama shaped ragged masks lose little to the coarse grid`() {
        val rows = mutableListOf<String>()
        for (seed in 1L..6L) {
            val random = Random(seed)
            val photos = 3 + random.nextInt(4)
            val w = 700 * (photos - 1) + 1000 + (seed.toInt() % 3) // not a multiple of the grid
            val h = 740 + (seed.toInt() % 5)
            val mask = panoramaMask(random, photos, w, h)
            val exact = AutoCrop.largestRect(mask, w, h)!!
            val crop = AutoCrop.cropRect(mask, w, h)!!
            assertTrue("seed $seed: hole in the crop", AutoCrop.allValid(mask, w, crop))
            val ratio = crop.area.toDouble() / exact.area
            rows += "seed $seed photos $photos ${w}x$h exact ${exact.width}x${exact.height} crop ${crop.width}x${crop.height} ratio ${"%.4f".format(ratio)}"
            assertTrue("seed $seed: crop area ratio $ratio", ratio >= 0.97)
            for (scale in listOf(2, 4, 16)) {
                val c = AutoCrop.cropRect(mask, w, h, scale)!!
                assertTrue("seed $seed scale $scale: hole in the crop", AutoCrop.allValid(mask, w, c))
                assertTrue("seed $seed scale $scale: ratio ${c.area.toDouble() / exact.area}", c.area >= exact.area * 0.95)
            }
        }
        rows.forEach { println("PANO-CROP $it") }
    }

    /** Photos 1000 x 700 about 700 px apart, each jittered up to +-20 px and rolled up to +-0.6 degrees. */
    private fun panoramaMask(random: Random, photos: Int, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        for (p in 0 until photos) {
            val cx = 500.0 + 700.0 * p
            val cy = height / 2.0 + random.nextInt(41) - 20
            val angle = Math.toRadians((random.nextDouble() * 2 - 1) * 0.6)
            val c = cos(angle)
            val s = sin(angle)
            for (y in 0 until height) for (x in 0 until width) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val u = dx * c + dy * s
                val v = -dx * s + dy * c
                if (u >= -500 && u < 500 && v >= -350 && v < 350) mask[y * width + x] = true
            }
        }
        return mask
    }

    @Test
    fun `a 24 megapixel ragged mask is cropped quickly and close to exact`() {
        val w = 6000
        val h = 4000
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val left = 40 + (y * 37) % 29
            val right = w - 40 - (y * 53) % 31
            val topEdge = 60
            for (x in left until right) {
                // A wavy top and bottom edge, 0..16 px deep, like the stair-steps of jittered frames.
                val wave = (x / 200) % 17
                if (y >= topEdge + wave && y < h - 60 - wave) mask[y * w + x] = true
            }
        }
        var t = System.nanoTime()
        val crop = AutoCrop.cropRect(mask, w, h)!!
        val coarseMs = (System.nanoTime() - t) / 1_000_000
        t = System.nanoTime()
        val exact = AutoCrop.largestRect(mask, w, h)!!
        val exactMs = (System.nanoTime() - t) / 1_000_000
        println("PANO-CROP 24MP ${w}x$h coarse ${coarseMs} ms ${crop.width}x${crop.height}, exact ${exactMs} ms ${exact.width}x${exact.height}, ratio ${"%.5f".format(crop.area.toDouble() / exact.area)}")
        assertTrue(AutoCrop.allValid(mask, w, crop))
        assertTrue("ratio ${crop.area.toDouble() / exact.area}", crop.area >= exact.area * 0.99)
        assertTrue("coarse pass took $coarseMs ms", coarseMs < 5_000)
    }

    // ---- contract ----

    @Test
    fun `cancelling stops both passes`() {
        val mask = BooleanArray(64 * 64) { true }
        try {
            AutoCrop.largestRect(mask, 64, 64) { true }
            fail("exact pass ignored shouldStop")
        } catch (_: CancellationException) {
        }
        try {
            AutoCrop.cropRect(mask, 64, 64) { true }
            fail("coarse pass ignored shouldStop")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `areas are Long and a mask the Int index cannot hold is refused`() {
        assertEquals(4_900_000_000L, CropRect(0, 0, 70_000, 70_000).area)
        // 65536 x 65536 wraps to 0 in Int arithmetic; an empty array must not pass for it.
        try {
            AutoCrop.largestRect(BooleanArray(0), 65_536, 65_536)
            fail("accepted a mask whose size wraps")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AutoCrop.cropRect(BooleanArray(10), 5, 3)
            fail("accepted a short mask")
        } catch (_: IllegalArgumentException) {
        }
    }
}
