package com.fotoxplorr.app.pano

import com.fotoxplorr.app.pano.SeamBlend.WEIGHT_ONE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * The feathered seam: weights that always add to one, no matter how short the overlap, pixels that
 * blend to the right colour, validity that wins over the seam, and a measurement of how much a
 * brightness difference between two frames shows at the join with and without the feather
 * (PANO-BLEND lines).
 */
class SeamBlendTest {

    companion object {
        @JvmStatic
        @org.junit.AfterClass
        fun release() = SyntheticPanoScene.releaseRigs()
    }

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int) = argb(v, v, v)

    // ---- weights ----

    @Test
    fun `weights are monotone, bounded and mirror around the seam for every overlap`() {
        for (width in listOf(0, 1, 2, 7, 40, 100)) {
            for (start in 0..width step maxOf(1, width / 5)) {
                for (end in start..width step maxOf(1, width / 7)) {
                    for (feather in listOf(0, 1, 2, 5, 32, 200)) {
                        val label = "width $width overlap $start..$end feather $feather"
                        val w = SeamBlend.secondWeights(width, start, end, feather)
                        assertEquals(label, width, w.size)
                        for (x in 0 until width) {
                            assertTrue("$label: w[$x]=${w[x]}", w[x] in 0..WEIGHT_ONE)
                            if (x > 0) assertTrue("$label: not monotone at $x", w[x] >= w[x - 1])
                        }
                        val ramp = minOf(feather, end - start)
                        // Outside the overlap it is one photo or the other, with no fade.
                        for (x in 0 until start) assertEquals("$label: left of overlap at $x", 0, w[x])
                        for (x in end until width) assertEquals("$label: right of overlap at $x", WEIGHT_ONE, w[x])
                        // The fade is no wider than the feather and no wider than the overlap.
                        val fading = w.count { it != 0 && it != WEIGHT_ONE }
                        assertTrue("$label: $fading fading columns for ramp $ramp", fading <= ramp)
                        if (ramp > 0) {
                            // The ramp mirrors itself exactly and is centred on the overlap to within a column.
                            val rampStart = start + (end - start - ramp) / 2
                            for (k in 0 until ramp) {
                                assertEquals("$label: mirror of $k", WEIGHT_ONE, w[rampStart + k] + w[rampStart + ramp - 1 - k])
                            }
                            val centreError = (rampStart * 2 + ramp) - (start + end)
                            assertTrue("$label: ramp off-centre by $centreError half-columns", abs(centreError) <= 1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a zero width or reversed overlap is a hard cut and one pixel is half and half`() {
        assertArrayEquals(intArrayOf(0, 0, 0, WEIGHT_ONE, WEIGHT_ONE), SeamBlend.secondWeights(5, 3, 3))
        // A gap between the photos (end before start) is treated as zero width.
        assertArrayEquals(intArrayOf(0, 0, 0, WEIGHT_ONE, WEIGHT_ONE), SeamBlend.secondWeights(5, 3, 1))
        assertArrayEquals(intArrayOf(0, 0, WEIGHT_ONE / 2, WEIGHT_ONE, WEIGHT_ONE), SeamBlend.secondWeights(5, 2, 3))
        // An overlap past the band is clamped into it (here to the single last column).
        assertArrayEquals(intArrayOf(0, 0, 0, 0, WEIGHT_ONE / 2), SeamBlend.secondWeights(5, 4, 99))
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), SeamBlend.secondWeights(5, 9, 99))
        assertArrayEquals(IntArray(0), SeamBlend.secondWeights(0, 0, 0))
    }

    @Test
    fun `a wide overlap fades over exactly the feather around its middle`() {
        val w = SeamBlend.secondWeights(400, 100, 300, 32)
        assertEquals(0, w[183])
        assertEquals(WEIGHT_ONE, w[216])
        assertEquals(32, w.count { it in 1 until WEIGHT_ONE })
        assertTrue(w[184] in 1 until WEIGHT_ONE && w[215] in 1 until WEIGHT_ONE)
        // The seam (half and half) falls on the middle of the overlap, column 200.
        assertTrue(w[199] < WEIGHT_ONE / 2 && w[200] > WEIGHT_ONE / 2)
    }

    // ---- pixels ----

    @Test
    fun `two identical bands blend to themselves exactly`() {
        val random = Random(7)
        val band = IntArray(60 * 5) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val out = SeamBlend.blendBand(band, band.copyOf(), 60, 5, 10, 50)
        assertArrayEquals(band, out)
    }

    @Test
    fun `columns outside the overlap are the untouched photo`() {
        val width = 100
        val first = IntArray(width * 3) { argb(200, 10, 30) }
        val second = IntArray(width * 3) { argb(20, 220, 90) }
        val out = SeamBlend.blendBand(first, second, width, 3, 40, 60)
        for (row in 0 until 3) {
            for (x in 0 until 40) assertEquals(first[0], out[row * width + x])
            for (x in 60 until width) assertEquals(second[0], out[row * width + x])
        }
    }

    @Test
    fun `blended channels follow the weights and stay between the two photos`() {
        val random = Random(11)
        val width = 90
        val rows = 4
        val first = IntArray(width * rows) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val second = IntArray(width * rows) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val weights = SeamBlend.secondWeights(width, 20, 70, 32)
        val out = SeamBlend.blendBand(first, second, width, rows, 20, 70, 32)
        for (i in out.indices) {
            val w = weights[i % width] / WEIGHT_ONE.toDouble()
            assertEquals(255, out[i] ushr 24)
            for (shift in intArrayOf(16, 8, 0)) {
                val a = (first[i] shr shift) and 0xFF
                val b = (second[i] shr shift) and 0xFF
                val c = (out[i] shr shift) and 0xFF
                val expected = a * (1 - w) + b * w
                assertTrue("pixel $i shift $shift: $c vs $expected", abs(c - expected) <= 0.5001)
                assertTrue(c >= minOf(a, b) && c <= maxOf(a, b))
            }
        }
    }

    @Test
    fun `a pixel with no data gives way to the other photo wherever the seam is`() {
        val first = intArrayOf(grey(100), 0, grey(100), 0)
        val second = intArrayOf(0, grey(200), grey(200), 0)
        // Hard cut after column 1: the first photo's side is columns 0-1, the second's 2-3.
        val out = SeamBlend.blendBand(first, second, 4, 1, 2, 2)
        assertEquals(grey(100), out[0])    // only the first has data, on its own side
        assertEquals(grey(200), out[1])    // first has none here, second fills it though the seam says "first"
        assertEquals(grey(200), out[2])    // both have data, right of the seam: second
        assertEquals(0, out[3])            // neither: stays empty, not black
        // Data from one photo comes out opaque even if the input alpha was partial.
        val half = SeamBlend.blendBand(intArrayOf(argb(10, 20, 30, 7)), intArrayOf(0), 1, 1, 0, 0)
        assertEquals(argb(10, 20, 30), half[0])
    }

    @Test
    fun `the output may be one of the inputs`() {
        val random = Random(3)
        val first = IntArray(50 * 2) { grey(random.nextInt(256)) }
        val second = IntArray(50 * 2) { grey(random.nextInt(256)) }
        val expected = SeamBlend.blendBand(first, second, 50, 2, 10, 40)
        val inPlace = first.copyOf()
        SeamBlend.blendBand(inPlace, second, 50, 2, 10, 40, out = inPlace)
        assertArrayEquals(expected, inPlace)
    }

    @Test
    fun `zero, one and short overlaps blend without a crash and without a stray colour`() {
        for (overlap in 0..6) {
            val width = 12
            val first = IntArray(width) { grey(50) }
            val second = IntArray(width) { grey(150) }
            val out = SeamBlend.blendBand(first, second, width, 1, 4, 4 + overlap)
            for (x in 0 until width) {
                val v = out[x] and 0xFF
                assertTrue("overlap $overlap x $x: $v", v in 50..150)
                if (x < 4) assertEquals(50, v)
                if (x >= 4 + overlap) assertEquals(150, v)
            }
        }
    }

    @Test
    fun `band sizes must agree`() {
        try {
            SeamBlend.blendBand(IntArray(10), IntArray(12), 5, 2, 0, 5)
            fail("accepted bands of different sizes")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SeamBlend.blendBand(IntArray(10), IntArray(10), 5, 2, 0, 5, out = IntArray(9))
            fail("accepted a short output")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, SeamBlend.blendBand(IntArray(0), IntArray(0), 0, 0, 0, 0).size)
    }

    // ---- on a procedural pair ----

    private fun toArgb(image: GrayImage, canvasW: Int, canvasH: Int, x0: Int, y0: Int): IntArray {
        val out = IntArray(canvasW * canvasH)
        for (y in 0 until image.height) for (x in 0 until image.width) out[(y + y0) * canvasW + x + x0] = grey(image.at(x, y))
        return out
    }

    /** Largest difference between neighbouring columns on one row, between columns [from] and [to]. */
    private fun worstStep(band: IntArray, width: Int, row: Int, from: Int, to: Int): Int {
        var worst = 0
        for (x in from until to - 1) worst = maxOf(worst, abs((band[row * width + x + 1] and 0xFF) - (band[row * width + x] and 0xFF)))
        return worst
    }

    @Test
    fun `a clean aligned pair blends back to the scene exactly`() {
        val rig = SyntheticPanoScene.rig(1, 0.45)
        val pair = rig.take(0, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
        val w = SyntheticPanoScene.FRAME_WIDTH + pair.dx
        val h = SyntheticPanoScene.FRAME_HEIGHT
        val a = toArgb(pair.first, w, h, 0, 0)
        val b = toArgb(pair.second, w, h, pair.dx, 0)
        val out = SeamBlend.blendBand(a, b, w, h, pair.dx, SyntheticPanoScene.FRAME_WIDTH)
        // The overlap is identical in both photos, so whatever the weights the result is the same pixels.
        var wrong = 0
        for (y in 0 until h) for (x in 0 until w) {
            val truth = if (x < pair.dx) a[y * w + x] else b[y * w + x]
            if (out[y * w + x] != truth) wrong++
        }
        assertEquals(0, wrong)
    }

    @Test
    fun `the feather hides the exposure step between two procedural frames`() {
        val rows = mutableListOf<String>()
        for (seed in 1..3) {
            val rig = SyntheticPanoScene.rig(seed, 0.45)
            val dy = SyntheticPanoScene.jitterFor(seed, 0.45)
            // The second frame came out 12% brighter with a black level 6 higher (auto-exposure moved).
            val pair = rig.take(dy, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot(gain = 1.12, bias = 6.0))
            val frameW = SyntheticPanoScene.FRAME_WIDTH
            val frameH = SyntheticPanoScene.FRAME_HEIGHT
            val w = frameW + pair.dx
            val h = frameH + abs(dy)
            val y0a = if (dy < 0) -dy else 0
            val y0b = if (dy < 0) 0 else dy
            val a = toArgb(pair.first, w, h, 0, y0a)
            val b = toArgb(pair.second, w, h, pair.dx, y0b)
            val feathered = SeamBlend.blendBand(a, b, w, h, pair.dx, frameW)
            val hard = SeamBlend.blendBand(a, b, w, h, pair.dx, frameW, feather = 0)
            // The scene has hard edges of its own, so measure what the join ADDS: the step across the
            // seam column in the output minus the step the first photo has there by itself.
            val seam = (pair.dx + frameW) / 2
            var hardExcess = 0.0
            var featherExcess = 0.0
            var n = 0
            for (y in maxOf(y0a, y0b) until minOf(y0a, y0b) + frameH) {
                val own = (a[y * w + seam] and 0xFF) - (a[y * w + seam - 1] and 0xFF)
                hardExcess += abs(((hard[y * w + seam] and 0xFF) - (hard[y * w + seam - 1] and 0xFF)) - own)
                featherExcess += abs(((feathered[y * w + seam] and 0xFF) - (feathered[y * w + seam - 1] and 0xFF)) - own)
                n++
            }
            hardExcess /= n
            featherExcess /= n
            rows += "seed $seed dy $dy: step added at the seam column, hard cut ${"%.2f".format(hardExcess)}, feather 32: ${"%.2f".format(featherExcess)} grey levels (mean over rows)"
            assertTrue("seed $seed: feather $featherExcess vs hard $hardExcess", featherExcess < hardExcess / 3)
        }
        rows.forEach { println("PANO-BLEND $it") }
    }

    @Test
    fun `a flat exposure step of 40 levels is at most two levels per column with the feather`() {
        val width = 400
        val first = IntArray(width) { grey(100) }
        val second = IntArray(width) { grey(140) }
        val hard = SeamBlend.blendBand(first, second, width, 1, 100, 300, feather = 0)
        val soft = SeamBlend.blendBand(first, second, width, 1, 100, 300)
        val hardStep = worstStep(hard, width, 0, 0, width)
        val softStep = worstStep(soft, width, 0, 0, width)
        println("PANO-BLEND exposure step 40: hard cut $hardStep, feather 32: $softStep levels per column")
        assertEquals(40, hardStep)
        assertTrue("soft step $softStep", softStep <= 2)
    }

    @Test
    fun `a two pixel misalignment ghosts across the ramp instead of jumping at one column`() {
        val rig = SyntheticPanoScene.rig(2, 0.45)
        val pair = rig.take(0, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
        val frameW = SyntheticPanoScene.FRAME_WIDTH
        val frameH = SyntheticPanoScene.FRAME_HEIGHT
        val wrongBy = 2
        val w = frameW + pair.dx + wrongBy
        val a = toArgb(pair.first, w, frameH, 0, 0)
        val b = toArgb(pair.second, w, frameH, pair.dx + wrongBy, 0)
        val start = pair.dx + wrongBy
        val feathered = SeamBlend.blendBand(a, b, w, frameH, start, frameW)
        val hard = SeamBlend.blendBand(a, b, w, frameH, start, frameW, feather = 0)
        // Compare with the truth (the first photo covers the whole overlap in the right place).
        val truth = toArgb(pair.first, w, frameH, 0, 0)
        val mid = (start + frameW) / 2
        var hardErr = 0L
        var softErr = 0L
        var n = 0L
        for (y in 0 until frameH) for (x in mid - 16 until mid + 16) {
            hardErr += abs((hard[y * w + x] and 0xFF) - (truth[y * w + x] and 0xFF))
            softErr += abs((feathered[y * w + x] and 0xFF) - (truth[y * w + x] and 0xFF))
            n++
        }
        println("PANO-BLEND 2 px misalignment, mean abs error over the 32 columns at the seam (grey levels): hard cut ${"%.2f".format(hardErr / n.toDouble())}, feather ${"%.2f".format(softErr / n.toDouble())}")
        // Not a claim that the feather is better: it spreads the error over the ramp. Only that it stays the same order.
        assertTrue("feather $softErr vs hard $hardErr", softErr <= hardErr * 2)
    }
}
