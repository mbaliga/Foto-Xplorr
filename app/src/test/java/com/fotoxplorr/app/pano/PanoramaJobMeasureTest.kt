package com.fotoxplorr.app.pano

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * How much do the imperfections of a handheld sweep cost, measured on the job's own chain: the grey
 * cylinder warp, the real aligner, the real layout, exposure solver and compositor, behind a fake
 * phone. Every condition prints one `PANO-JOB-MEASURE` line with
 *
 * - **align**: the worst error of the four pair offsets against the truth, in working pixels (dx / dy),
 *   and how many of the four the aligner called confident;
 * - **job**: whether the job saved a panorama (or the sentence it gave instead), and for a saved one the
 *   canvas against the scene over the auto-crop, as the mean and worst channel error in grey levels after
 *   removing the best overall gain and bias (a brightness that is wrong everywhere is not a seam), and
 *   the same raw.
 *
 * The scene is a procedural cylinder (1/f value noise, hard-edged blocks, gradients), photographed
 * through a pinhole camera: no real photograph is involved, and there is no lens distortion, rolling
 * shutter or motion blur. The asserts leave margin over what was measured and are the floor the
 * numbers must not fall below; for the conditions the aligner cannot handle by design (a large moving
 * subject, parallax, a wrong lens) the assert is that the job either says it cannot join or saves a
 * picture within a stated worst-case error, never that it is good. Real-photo quality is unproven until
 * the owner's three real handheld sweeps.
 */
class PanoramaJobMeasureTest {

    private class Row(
        val name: String,
        val dx: Int,
        val dy: Int,
        val confident: Int,
        val saved: Boolean,
        val message: String,
        val fitMean: Double,
        val fitMax: Double,
        val rawMean: Double,
        val crop: String,
    ) {
        override fun toString() = "%-34s align dx %d dy %d px, confident %d/4 | job %s".format(
            name, dx, dy, confident,
            if (saved) "saved, canvas residual mean %.2f max %.1f (raw mean %.2f), crop %s".format(fitMean, fitMax, rawMean, crop) else "said: $message",
        )
    }

    private fun shot(gain: Double = 1.0, bias: Double = 0.0, noise: Double = 0.0, vignette: Double = 0.0, roll: Double = 0.0, near: Double = 0.0, blob: SyntheticPanoScene.Blob? = null, seed: Long = 1L) =
        SyntheticPanoScene.Shot(gain = gain, bias = bias, noiseSigma = noise, vignette = vignette, rollDegrees = roll, nearShift = near, blob = blob, seed = seed)

    private fun measure(
        name: String,
        rig: CylinderRig = CylinderRig(),
        withNear: Boolean = false,
        pipelineF35: ((Int) -> Int?)? = null,
        /** Scene pixels (x, y) the canvas is not compared at, where the truth is not the far scene (a near layer). */
        exclude: ((Int, Int) -> Boolean)? = null,
        shots: (Int) -> SyntheticPanoScene.Shot,
    ): Row {
        val f35 = pipelineF35 ?: { rig.f35 }
        val host = rigHost(rig, shots, withNear, f35)

        // 1. alignment alone: what the job's warp and the aligner say against where the camera really was
        val f = PanoGeometry.focalPixels(f35(0), rig.photoW, rig.photoH)
        val greys = rig.assets().indices.map { PanoramaJob.warpToGrey(rig.render(it, rig.photoW, rig.photoH, shots(it), withNear), f) }
        val offsets = (0 until greys.size - 1).map { PairAligner.align(greys[it], greys[it + 1]) }
        var dx = 0
        var dy = 0
        for ((i, o) in offsets.withIndex()) {
            dx = max(dx, abs(o.dx - (rig.lefts[i + 1] - rig.lefts[i])))
            dy = max(dy, abs(o.dy - (rig.topsUsed[i + 1] - rig.topsUsed[i])))
        }

        // 2. the whole job
        val sink = FakeSink()
        val outcome = runBlocking { PanoramaJob.run(PanoramaRequest(host.assets), host, sink) }
        if (outcome !is PanoramaOutcome.Done) {
            return Row(name, dx, dy, offsets.count { it.confident }, false, (outcome as PanoramaOutcome.Failed).message, 0.0, 0.0, 0.0, "")
        }
        val saved = sink.published.single()
        val crop = saved.crop
        val top = rig.topsUsed.min()
        // Green channel, canvas against scene, every third pixel.
        val truth = DoubleArray(3)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (y in crop.top until crop.bottom step 3) for (x in crop.left until crop.right step 3) {
            if (exclude != null && exclude(x, y + top)) continue
            rig.scene.expected(x, y + top, 1.0, truth)
            xs += truth[1]
            ys += ((saved.pixels[y * saved.canvas.width + x] shr 8) and 0xFF).toDouble()
        }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var fit = 0.0
        var worst = 0.0
        var raw = 0.0
        for (i in xs.indices) {
            val e = abs(ys[i] - (slope * xs[i] + intercept))
            fit += e
            worst = max(worst, e)
            raw += abs(ys[i] - xs[i])
        }
        return Row(name, dx, dy, offsets.count { it.confident }, true, "", fit / xs.size, worst, raw / xs.size, "${crop.width}x${crop.height}")
    }

    private fun report(row: Row): Row {
        println("PANO-JOB-MEASURE $row")
        return row
    }

    private fun assertJoined(row: Row, maxOffsetError: Int, maxFitMean: Double) {
        assertTrue("${row.name}: ${row}", row.saved)
        assertTrue("${row.name}: offsets ${row.dx}/${row.dy} px", row.dx <= maxOffsetError && row.dy <= maxOffsetError)
        assertTrue("${row.name}: residual ${row.fitMean}", row.fitMean <= maxFitMean)
    }

    // ---- what the design lists as imperfections, one at a time ---------------------------------

    @Test
    fun `clean, and the overlaps from a fifth to three fifths`() {
        assertJoined(report(measure("clean, 30% overlap") { shot() }), 1, 1.6)
        assertJoined(report(measure("clean, 20% overlap", CylinderRig(step = 460)) { shot() }), 1, 1.6)
        assertJoined(report(measure("clean, 60% overlap", CylinderRig(step = 230)) { shot() }), 1, 1.6)
    }

    @Test
    fun `per-frame gain, then gain with bias, as an auto-exposing phone does`() {
        val gains = listOf(1.0, 1.1, 0.9, 1.05, 0.95)
        assertJoined(report(measure("gain 0.9-1.1") { shot(gain = gains[it]) }), 1, 2.0)
        val random = Random(4)
        val handheld = List(5) { 0.8 + 0.4 * random.nextDouble() to (random.nextDouble() * 2 - 1) * 25.5 }
        // A bias is not a gain: the solver can only scale, so a +-25 level bias leaves steps at the seams (the residual).
        assertJoined(report(measure("gain 0.8-1.2 plus bias +-25") { shot(gain = handheld[it].first, bias = handheld[it].second) }), 1, 14.0)
    }

    @Test
    fun `sensor noise, and vignetting`() {
        assertJoined(report(measure("noise sigma 7.7 (3%)") { shot(noise = 7.65, seed = it + 10L) }), 1, 6.0)
        assertJoined(report(measure("vignette 0.3 on every frame") { shot(vignette = 0.3) }), 1, 7.0)
    }

    @Test
    fun `vertical wander of 20 pixels`() {
        assertJoined(report(measure("vertical jitter up to 25 px", CylinderRig(tops = listOf(0, 20, 5, 25, 10))) { shot() }), 1, 1.6)
    }

    @Test
    fun `the lens, with a rounded tag, no tag, and a wrong tag`() {
        // The camera's true focal length is 520 px; the tag says 28 mm, which is 517.7 px.
        val rounded = CylinderRig(cameraFocal = 520.0)
        assertJoined(report(measure("tag rounded to a whole mm (0.4%)", rounded) { shot() }), 1, 2.5)
        // No tag: a 78 degree guess, 494 px against the true 517.7. Still joins, with a visible cost.
        assertJoined(report(measure("no tag, 78 degree guess (4.6% short)", pipelineF35 = { null }) { shot() }), 4, 12.0)
        // A tag 3 mm off: 10.7% either way. The aligner still calls every pair confident, so nothing in
        // the job can tell: this is the case the "straight lines may look curved" note cannot cover.
        for ((label, tag) in listOf("tag says 25 mm, truth 28 (10.7% short)" to 25, "tag says 31 mm, truth 28 (10.7% long)" to 31)) {
            val row = report(measure(label, pipelineF35 = { tag }) { shot() })
            assertTrue("$label: $row", row.saved && row.dx <= 10 && row.dy <= 4 && row.fitMean <= 22.0)
        }
    }

    // ---- what a translation model cannot be right about ------------------------------------

    @Test
    fun `roll from a hand that is not level`() {
        val signs = listOf(1, -1, 1, -1, 1)
        for (degrees in listOf(0.25, 0.5, 1.0, 2.0)) {
            val row = report(measure("roll +-$degrees degrees, alternating") { shot(roll = degrees * signs[it]) })
            if (degrees <= 0.5) assertJoined(row, 5, 6.0)
        }
    }

    @Test
    fun `a moving subject in the overlap`() {
        // An ellipse of 120 x 200 px, dark, in the left part of the third photo, which is its overlap with the second.
        for ((label, blob) in listOf(
            "small blob 60x100 px" to SyntheticPanoScene.Blob(90, 240, 30, 50, 30f),
            "large blob 120x200 px" to SyntheticPanoScene.Blob(90, 240, 60, 100, 30f),
        )) {
            val row = report(measure("moving subject, $label") { if (it == 2) shot(blob = blob) else shot() })
            if (label.startsWith("small")) assertJoined(row, 4, 6.0)
        }
    }

    @Test
    fun `parallax from a near layer that shifts more than the far scene`() {
        // The canvas is compared with the far scene only where no near post can be (the truth is not defined under one).
        for (shift in listOf(3.0, 8.0, 20.0)) {
            val rig = CylinderRig()
            val covers = rig.near.covers
            val width = rig.fullW
            val avoid = { x: Int, y: Int -> (0 until 5).any { i -> covers[y * width + (x + (shift * i).toInt()).coerceIn(0, width - 1)] } }
            val row = report(measure("near layer shifts ${shift.toInt()} px per frame", rig, withNear = true, exclude = avoid) { shot(near = shift * it) })
            // The aligner follows the near layer (its error equals the shift per frame): the far scene ghosts.
            assertTrue("${row.name}: $row", row.dx <= shift.toInt() + 2 && row.dy <= 2)
        }
    }

    @Test
    fun `everything a handheld sweep has at once`() {
        val random = Random(9)
        val gains = List(5) { 0.85 + 0.3 * random.nextDouble() }
        val biases = List(5) { (random.nextDouble() * 2 - 1) * 12.0 }
        val signs = listOf(1, -1, 1, -1, 1)
        val row = report(
            measure("gain, bias, noise, vignette, roll 0.5, jitter", CylinderRig(tops = listOf(0, 14, 4, 18, 8))) {
                shot(gain = gains[it], bias = biases[it], noise = 4.0, vignette = 0.2, roll = 0.5 * signs[it], seed = it + 20L)
            },
        )
        // The honest floor: it joins, within a few pixels, or it says it cannot; it never throws.
        assertTrue(row.name, !row.saved || (row.dx <= 6 && row.dy <= 6))
    }

    companion object {
        @JvmStatic
        @AfterClass
        fun release() = SyntheticPanoScene.releaseRigs()
    }
}
