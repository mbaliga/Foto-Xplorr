package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The grey warp the aligner works on ([PanoramaJob.warpToGrey]) has to be the SAME cylinder as the
 * one the compositor draws, or every offset the aligner finds is off by a fraction of a pixel and the
 * seams shift. Three independent checks: against the scene it photographed (ground truth), against
 * the compositor's own canvas for the same photo, and against an exact identity where there is no
 * warp at all.
 */
class PanoramaWarpTest {

    private fun luma(argb: Int): Float = ((((argb shr 16) and 0xFF) * 77) + (((argb shr 8) and 0xFF) * 150) + ((argb and 0xFF) * 29)) / 256f

    @Test
    fun `with no curvature the warp is the photo's own grey, exactly`() {
        val scene = ColourScene.of(300, 200, 2)
        val photo = scene.tile(20, 10, 240, 160)
        val grey = PanoramaJob.warpToGrey(photo, 1.0e7)
        assertEquals(240, grey.width)
        assertEquals(160, grey.height)
        var worst = 0
        for (i in photo.argb.indices) {
            val want = Math.round(luma(photo.argb[i])).coerceIn(0, 255)
            worst = maxOf(worst, abs(grey.pixels[i].toInt().and(0xFF) - want))
        }
        assertEquals("every pixel is its own grey to rounding", 0, worst)
    }

    @Test
    fun `the warped size is the geometry's warped size`() {
        for ((w, h, f) in listOf(Triple(640, 480, 517.7), Triple(1200, 900, 970.0), Triple(30, 20, 40.0), Triple(2, 2, 3.0), Triple(1, 1, 1.0))) {
            val photo = PanoPixels(w, h, IntArray(w * h) { 0xFF404040.toInt() })
            val grey = PanoramaJob.warpToGrey(photo, f)
            assertEquals(PanoGeometry.warpedSize(w, h, f).width, grey.width)
            assertEquals(h, grey.height)
        }
    }

    @Test
    fun `the warped photo is the cylinder scene it was photographed from, in place`() {
        val rig = CylinderRig()
        val photo = rig.render(0, rig.photoW, rig.photoH)
        val grey = PanoramaJob.warpToGrey(photo, rig.f)
        // Photo 0 sits at left 0, top topsUsed[0] on the cylinder canvas: grey(i, row) is the scene's grey at (i, top + row).
        val top = rig.topsUsed[0]
        fun error(shiftX: Int, shiftY: Int): Double {
            var sum = 0.0
            var n = 0
            val margin = 40
            for (row in margin until grey.height - margin) for (i in margin until grey.width - margin) {
                val sx = i + shiftX
                val sy = top + row + shiftY
                val expected = luma(ColourScene.pack(rig.scene.red.grey[sy * rig.scene.width + sx].toDouble(), rig.scene.green.grey[sy * rig.scene.width + sx].toDouble(), rig.scene.blue.grey[sy * rig.scene.width + sx].toDouble()))
                sum += abs(grey.at(i, row) - expected)
                n++
            }
            return sum / n
        }
        val right = error(0, 0)
        println("PANO-JOB grey warp against the scene: mean %.2f grey levels (three pixels off in x: %.2f, in y: %.2f)".format(right, error(3, 0), error(0, 3)))
        assertTrue("the warp lands on the scene: $right", right <= 1.5)
        // The scene is smooth (1/f), so only a slip of a few pixels stands out; the comparison with the
        // compositor's canvas below is the sharp test of the half-pixel conventions.
        assertTrue("and a three pixel slip would be seen (x): ${error(3, 0)}", error(3, 0) > 2 * right)
        assertTrue("and in y: ${error(0, 3)}", error(0, 3) > 2 * right)
    }

    @Test
    fun `the aligner's warp and the compositor's canvas show the same picture for one photo`() {
        val rig = CylinderRig()
        val photo = rig.render(2, rig.photoW, rig.photoH)
        val grey = PanoramaJob.warpToGrey(photo, rig.f)
        val source = FunctionSource(rig.photoW, rig.photoH, rig.f) { _, _ -> photo }
        val drawn = stitch(listOf(source), listOf(PanoPoint(0, 0)))
        assertEquals(grey.width, drawn.canvas.width)
        assertEquals(grey.height, drawn.canvas.height)
        var sum = 0.0
        var worst = 0.0
        var n = 0
        for (row in 0 until grey.height) for (i in 0 until grey.width) {
            val p = drawn.canvas.pixels[row * grey.width + i]
            if (p ushr 24 == 0) continue
            val e = abs(grey.at(i, row) - luma(p))
            sum += e
            worst = maxOf(worst, e.toDouble())
            n++
        }
        println("PANO-JOB grey warp against the compositor's canvas: mean %.3f, max %.1f over %d px".format(sum / n, worst, n))
        assertTrue("most of the canvas is drawn: $n", n > 0.9 * grey.width * grey.height)
        // Measured 0.26 mean, 1.0 max (the two bilinear kernels differ in fixed against floating point); half a pixel off is far more.
        assertTrue("the same picture to rounding: ${sum / n}", sum / n <= 0.4)
        assertTrue("and nowhere more than a couple of levels: $worst", worst <= 2.0)
    }

    @Test
    fun `the cylinder's corners repeat the photo's edge instead of going black`() {
        val rig = CylinderRig()
        val photo = rig.render(0, rig.photoW, rig.photoH)
        val grey = PanoramaJob.warpToGrey(photo, rig.f)
        // The top row of the warped picture near its left and right ends is outside the photo; it is still image, not 0.
        for (i in listOf(0, 1, grey.width - 2, grey.width - 1)) {
            assertTrue("column $i of the top row is not black: ${grey.at(i, 0)}", grey.at(i, 0) > 0)
        }
    }

    @Test
    fun `the aligner and the exposure solver read the warped grey exactly, wide lens corners and all`() {
        // The corners the cylinder leaves empty are a repeat of the photo's edge in this grey (see warpToGrey). Measured
        // on a 14 mm and a 28 mm lens: the offsets are exact and a 12% exposure step is read to 0.2%; only the score
        // pays (0.71 to 0.99), which stays far above the trust threshold.
        var worstRatio = 0.0
        var lowestScore = 1f
        for (f35 in listOf(14, 28)) for (overlap in listOf(0.25, 0.4)) for (seed in listOf(5, 6)) {
            val step = Math.round(CylinderRig(f35 = f35, seed = seed).warpedW * (1 - overlap)).toInt()
            val rig = CylinderRig(f35 = f35, seed = seed, step = step, count = 3)
            val gains = listOf(0.88, 1.0, 1.12)
            val greys = (0 until 3).map { i ->
                PanoramaJob.warpToGrey(rig.render(i, rig.photoW, rig.photoH, SyntheticPanoScene.Shot(gain = gains[i], noiseSigma = 4.0, seed = 3L + i)), rig.f)
            }
            for (i in 0 until 2) {
                val dx = rig.lefts[i + 1] - rig.lefts[i]
                val dy = rig.topsUsed[i + 1] - rig.topsUsed[i]
                val o = PairAligner.align(greys[i], greys[i + 1])
                assertTrue("f35 $f35 overlap $overlap seed $seed pair $i: got (${o.dx},${o.dy}) truth ($dx,$dy), score ${o.score}", o.confident && abs(o.dx - dx) <= 1 && abs(o.dy - dy) <= 1)
                lowestScore = minOf(lowestScore, o.score)
                val m = ExposureGain.measure(greys[i], greys[i + 1], dx, dy)
                assertTrue("f35 $f35 overlap $overlap seed $seed pair $i: the exposure step was not read", m != null)
                worstRatio = maxOf(worstRatio, abs(m!!.ratio / (gains[i] / gains[i + 1]) - 1.0))
            }
        }
        println("PANO-JOB wide-lens warp: lowest score %.3f, worst exposure ratio error %.4f".format(lowestScore, worstRatio))
        assertTrue("an exposure step is read to within 1%: $worstRatio", worstRatio < 0.01)
        assertTrue("the corners cost score, not the answer: $lowestScore", lowestScore > 0.6f)
    }

    @Test
    fun `a photo of one pixel does not break the warp`() {
        val grey = PanoramaJob.warpToGrey(PanoPixels(1, 1, intArrayOf(0xFF808080.toInt())), 10.0)
        assertEquals(1, grey.height)
        assertTrue(grey.pixels.all { it.toInt() and 0xFF in 120..136 })
    }
}
