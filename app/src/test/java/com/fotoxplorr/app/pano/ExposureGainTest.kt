package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * The exposure multipliers against sweeps cut from procedural scenes where each frame's exposure is
 * known (see [SyntheticPanoScene]). "Recovered" means: after each frame is multiplied by its answer,
 * the frames agree, i.e. `answer[i] * exposure[i]` is the same number for every i, whatever it is
 * (only the ratios between photos are knowable; the mean-1 rule picks the overall level).
 *
 * The first tests are the stated bar: exposures 0.8..1.25 recovered within 2%. The later ones
 * measure what happens when the frames are NOT just a gain apart (offset, vignetting, a mover, clipped
 * highlights) and print it (grep PANO-GAIN) so the limits are numbers, not adjectives. As elsewhere
 * these are procedural scenes: a photograph's auto-exposure also moves its tone curve, which no
 * scalar multiplier can undo.
 */
class ExposureGainTest {

    companion object {
        private val scenes = java.util.concurrent.ConcurrentHashMap<String, SyntheticPanoScene.Canvas>()

        @JvmStatic
        @org.junit.AfterClass
        fun release() {
            scenes.clear()
            SyntheticPanoScene.releaseRigs()
        }
    }

    private val width = SyntheticPanoScene.FRAME_WIDTH
    private val height = SyntheticPanoScene.FRAME_HEIGHT
    private val margin = 60

    private class Sweep(val images: List<GrayImage>, val positions: List<PanoPoint>)

    /** [count] frames of one scene, [overlap] apart, each frame taken as `shot(i)` with a vertical jitter of up to [jitter]. */
    private fun sweep(seed: Int, count: Int, overlap: Double, jitter: Int = 0, shot: (Int) -> SyntheticPanoScene.Shot): Sweep {
        val step = Math.round(width * (1 - overlap)).toInt()
        val sceneWidth = width + step * (count - 1) + 2 * margin
        val scene = scenes.computeIfAbsent("$seed/$count/$step") {
            SyntheticPanoScene.scene(sceneWidth, height + 2 * margin, seed)
        }
        val random = Random(seed * 131L + count)
        val ys = List(count) { if (jitter == 0) 0 else random.nextInt(2 * jitter + 1) - jitter }
        val images = List(count) { i -> SyntheticPanoScene.photograph(scene, margin + i * step, margin + ys[i], width, height, shot(i)) }
        val low = ys.min()
        return Sweep(images, List(count) { PanoPoint(it * step, ys[it] - low) })
    }

    /** The largest departure of `answer[i] * exposure[i]` from its mean, as a fraction. */
    private fun worstError(result: ExposureGain.Result, exposure: List<Double>): Double {
        val product = result.gains.indices.map { result.gains[it] * exposure[it] }
        val mean = product.average()
        return product.maxOf { abs(it / mean - 1) }
    }

    private fun gainShot(exposure: List<Double>, noise: Double = 0.0, vignette: Double = 0.0, bias: Double = 0.0): (Int) -> SyntheticPanoScene.Shot =
        { i -> SyntheticPanoScene.Shot(gain = exposure[i], bias = bias * (if (i % 2 == 0) 1 else -1), noiseSigma = noise, vignette = vignette, seed = 100L + i) }

    // ---- the bar ------------------------------------------------------------------------------

    @Test
    fun `known gains of 0_8 to 1_25 are recovered within 2 percent`() {
        val exposure = listOf(1.0, 0.8, 1.25, 0.9, 1.1)
        for (overlap in listOf(0.25, 0.45, 0.65)) {
            val s = sweep(1, exposure.size, overlap, shot = gainShot(exposure))
            val result = ExposureGain.compute(s.images, s.positions)
            assertTrue("overlap $overlap: ${result.gains}", worstError(result, exposure) <= 0.02)
            assertFalse(result.clamped)
        }
    }

    @Test
    fun `gains survive sensor noise and vertical jitter`() {
        val worst = ArrayList<Double>()
        for (seed in 1..3) for (overlap in listOf(0.25, 0.45, 0.65)) {
            val random = Random(seed * 7L + Math.round(overlap * 100))
            val exposure = List(4) { 0.8 + 0.45 * random.nextDouble() }
            val s = sweep(seed, 4, overlap, jitter = 20, shot = gainShot(exposure, noise = 0.03 * 255))
            val result = ExposureGain.compute(s.images, s.positions)
            val error = worstError(result, exposure)
            worst += error
            assertTrue("seed $seed overlap $overlap: error $error, exposure $exposure, gains ${result.gains}", error <= 0.02)
        }
        println("PANO-GAIN noise 3 percent + jitter 20 px, 9 sweeps of 4: worst error %.2f%%".format(100 * worst.max()))
    }

    @Test
    fun `many random sweeps of up to twelve frames recover within 2 percent`() {
        val worst = ArrayList<Double>()
        for ((k, count) in listOf(2, 3, 6, 8, 12).withIndex()) {
            val random = Random(900L + k)
            val exposure = List(count) { 0.8 + 0.45 * random.nextDouble() }
            val s = sweep(20 + k, count, 0.45, jitter = 10, shot = gainShot(exposure, noise = 0.02 * 255))
            val result = ExposureGain.compute(s.images, s.positions)
            val error = worstError(result, exposure)
            worst += error
            assertEquals(count, result.gains.size)
            assertTrue("$count frames: error $error", error <= 0.02)
        }
        println("PANO-GAIN random exposures, 2/3/6/8/12 frames at 45 percent: worst error %.2f%%".format(100 * worst.max()))
    }

    @Test
    fun `the answer has a mean of 1 and stays inside the limits`() {
        val exposure = listOf(0.8, 1.25, 1.0, 1.1)
        val s = sweep(2, 4, 0.45, shot = gainShot(exposure))
        val result = ExposureGain.compute(s.images, s.positions)
        assertEquals(1.0, result.gains.average(), 1e-9)
        assertTrue(result.gains.all { it in ExposureGain.MIN_GAIN..ExposureGain.MAX_GAIN })
        // The brightest photo is dimmed, the darkest brightened.
        assertTrue(result.gains[1] < 1.0 && result.gains[0] > 1.0)
    }

    @Test
    fun `identical exposures give gains of one`() {
        val s = sweep(3, 4, 0.45, jitter = 15, shot = { SyntheticPanoScene.Shot(noiseSigma = 4.0, seed = 40L + it) })
        val result = ExposureGain.compute(s.images, s.positions)
        for (g in result.gains) assertEquals(1.0, g, 0.01)
        assertTrue(result.worstMismatch < 0.02)
    }

    @Test
    fun `aligner then layout then gains recovers the exposures from the aligner's own offsets`() {
        val exposure = listOf(1.1, 0.8, 1.25, 0.95)
        for (overlap in listOf(0.35, 0.55)) {
            val s = sweep(2, 4, overlap, jitter = 20, shot = gainShot(exposure, noise = 0.03 * 255))
            val offsets = (0 until 3).map { PairAligner.align(s.images[it], s.images[it + 1]) }
            val layout = PanoLayout.layout(s.images.map { PanoSize(it.width, it.height) }, offsets)
            assertTrue("overlap $overlap: ${offsets.map { it.confident }}", layout.complete)
            val result = ExposureGain.compute(s.images, layout.positions)
            val error = worstError(result, exposure)
            println("PANO-GAIN end to end (aligner offsets, layout, gains), overlap %.2f: worst error %.2f%%".format(overlap, 100 * error))
            assertTrue("overlap $overlap: error $error", error <= 0.02)
        }
    }

    // ---- pairs --------------------------------------------------------------------------------

    @Test
    fun `a generous sweep also measures the photos two apart`() {
        val exposure = listOf(1.0, 1.2, 0.85, 1.1, 0.95)
        val s = sweep(1, 5, 0.65, shot = gainShot(exposure))
        val pairs = ExposureGain.measureAll(s.images, s.positions)
        val seen = pairs.map { it.a to it.b }.toSet()
        // 65 percent overlap: neighbours (0,1) and photos two apart (0,2) share a view; three apart do not.
        for (i in 0 until 4) assertTrue(seen.contains(i to i + 1))
        for (i in 0 until 3) assertTrue(seen.contains(i to i + 2))
        assertFalse(seen.contains(0 to 3))
        assertEquals(7, pairs.size)
        // Every ratio matches the truth for its pair.
        for (p in pairs) assertEquals(exposure[p.a] / exposure[p.b], p.ratio, 0.01 * p.ratio)
    }

    @Test
    fun `a thin overlap or a dark flat one says nothing`() {
        val bright = SyntheticPanoScene.pattern(width, height, 3.0, 5L) { x, y -> 90.0 + 40.0 * kotlin.math.sin(x / 17.0) * kotlin.math.cos(y / 23.0) }
        // Nearly no overlap.
        assertNull(ExposureGain.measure(bright, bright, width - 20, 0))
        // No overlap at all, and well off the grid.
        assertNull(ExposureGain.measure(bright, bright, width + 50, 0))
        assertNull(ExposureGain.measure(bright, bright, -width - 50, 10))
        // Overlap is there but it is black.
        val black = GrayImage(width, height, ByteArray(width * height) { 2 })
        assertNull(ExposureGain.measure(black, black, 300, 0))
        // And full white.
        val white = GrayImage(width, height, ByteArray(width * height) { 255.toByte() })
        assertNull(ExposureGain.measure(white, white, 300, 0))
        // Dark but not black: below the reading floor.
        val dim = GrayImage(width, height, ByteArray(width * height) { 9 })
        assertNull(ExposureGain.measure(dim, dim, 300, 0))
        // A control: the same bright pattern against itself is exactly 1.
        val self = ExposureGain.measure(bright, bright, 0, 0)
        assertNotNull(self)
        assertEquals(1.0, self!!.ratio, 1e-9)
    }

    @Test
    fun `measure follows the offset convention both ways round`() {
        val exposure = listOf(1.0, 1.25)
        val s = sweep(1, 2, 0.45, jitter = 20, shot = gainShot(exposure))
        val dx = s.positions[1].x - s.positions[0].x
        val dy = s.positions[1].y - s.positions[0].y
        val forward = ExposureGain.measure(s.images[0], s.images[1], dx, dy)!!
        val back = ExposureGain.measure(s.images[1], s.images[0], -dx, -dy)!!
        assertEquals(1.0 / 1.25, forward.ratio, 0.01)
        assertEquals(1.25, back.ratio, 0.015)
        assertEquals(1.0, forward.ratio * back.ratio, 0.002)
    }

    @Test
    fun `clipped highlights do not bend the ratio`() {
        // Gains up to 1.25 push the bright blocks of the scene past 255 in the brighter frame.
        val exposure = listOf(1.25, 0.8)
        val s = sweep(4, 2, 0.45, shot = gainShot(exposure))
        val clipped = s.images[0].pixels.count { (it.toInt() and 0xFF) >= 251 }
        assertTrue("the scene must really clip for this to prove anything ($clipped px)", clipped > 1000)
        val result = ExposureGain.compute(s.images, s.positions)
        assertTrue(worstError(result, exposure) <= 0.02)
    }

    // ---- solving ------------------------------------------------------------------------------

    private fun pair(a: Int, b: Int, ratio: Double, weight: Double = 100.0) = ExposureGain.PairRatio(a, b, ratio, weight)

    @Test
    fun `solve uses the sign convention a over b`() {
        // Image 0 is twice as bright as image 1 over their overlap, so it is multiplied by less.
        val result = ExposureGain.solve(2, listOf(pair(0, 1, 2.0)))
        assertEquals(result.gains[1] / result.gains[0], 2.0, 1e-6)
        assertEquals(1.0, result.gains.average(), 1e-9)
        assertEquals(0.0, result.worstMismatch, 1e-6)
    }

    @Test
    fun `solve chains neighbours exactly`() {
        val truth = listOf(1.0, 1.2, 0.8, 1.25, 0.9)
        val ratios = (0 until 4).map { pair(it, it + 1, truth[it] / truth[it + 1]) }
        val result = ExposureGain.solve(5, ratios)
        assertTrue(worstErrorOf(result.gains, truth) < 1e-6)
        assertEquals(0.0, result.worstMismatch, 1e-6)
    }

    private fun worstErrorOf(gains: List<Double>, exposure: List<Double>): Double {
        val product = gains.indices.map { gains[it] * exposure[it] }
        val mean = product.average()
        return product.maxOf { abs(it / mean - 1) }
    }

    @Test
    fun `extra pairs average out noisy measurements`() {
        val truth = listOf(1.0, 1.2, 0.8, 1.25, 0.9, 1.1)
        val random = Random(11)
        fun rms(errors: List<Double>) = kotlin.math.sqrt(errors.sumOf { it * it } / errors.size)
        val chainErrors = ArrayList<Double>()
        val skipErrors = ArrayList<Double>()
        repeat(300) {
            fun noisy(a: Int, b: Int) = pair(a, b, truth[a] / truth[b] * (1 + 0.02 * random.nextGaussian()))
            val chainOnly = ArrayList<ExposureGain.PairRatio>()
            val withSkips = ArrayList<ExposureGain.PairRatio>()
            for (i in 0 until 5) { val p = noisy(i, i + 1); chainOnly += p; withSkips += p }
            for (i in 0 until 4) withSkips += noisy(i, i + 2)
            for (i in 0 until 3) withSkips += noisy(i, i + 3)
            chainErrors += worstErrorOf(ExposureGain.solve(6, chainOnly).gains, truth)
            skipErrors += worstErrorOf(ExposureGain.solve(6, withSkips).gains, truth)
        }
        println("PANO-GAIN 2 percent noise per pair, 6 photos, 300 trials, rms of the worst photo: chain only %.2f%%, with extra pairs %.2f%%".format(100 * rms(chainErrors), 100 * rms(skipErrors)))
        assertTrue("extra pairs must help: chain ${rms(chainErrors)}, skips ${rms(skipErrors)}", rms(skipErrors) < rms(chainErrors))
    }

    @Test
    fun `an image no pair reaches is left unchanged and islands are centred on their own`() {
        // 0 and 1 differ by 25 percent; 2 is in no pair; 3 and 4 differ by 10 percent, apart from the rest.
        val result = ExposureGain.solve(5, listOf(pair(0, 1, 1.25), pair(3, 4, 1.1)))
        assertEquals(1.25, result.gains[1] / result.gains[0], 1e-4)
        assertEquals(1.1, result.gains[4] / result.gains[3], 1e-4)
        // Each island is centred on 1 in the log, so the image in no pair sits at the geometric mean.
        val geometricMean = kotlin.math.exp(result.gains.sumOf { ln(it) } / result.gains.size)
        assertEquals(1.0, result.gains[2] / geometricMean, 1e-4)
        assertEquals(1.0, result.gains[0] * result.gains[1] / (geometricMean * geometricMean), 1e-4)
        assertEquals(1.0, result.gains.average(), 1e-9)
    }

    @Test
    fun `nothing measured gives gains of one`() {
        val none = ExposureGain.solve(4, emptyList())
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), none.gains)
        assertFalse(none.clamped)
        assertEquals(listOf(1.0), ExposureGain.solve(1, emptyList()).gains)
    }

    @Test
    fun `an absurd exposure step is held to the limits and said so`() {
        // 6 times brighter: asking for 0.28 and 1.7 would be correct, but 0.5..2 is the rule.
        val result = ExposureGain.solve(2, listOf(pair(0, 1, 6.0)))
        assertTrue(result.clamped)
        assertTrue(result.gains.all { it in ExposureGain.MIN_GAIN..ExposureGain.MAX_GAIN })
        assertEquals(ExposureGain.MIN_GAIN, result.gains[0], 1e-9)
        assertTrue("the seam is still off and the answer says so: ${result.worstMismatch}", result.worstMismatch > 0.5)
        // Three photos with one extreme: the others still end near mean 1 and inside the limits.
        val three = ExposureGain.solve(3, listOf(pair(0, 1, 1.0), pair(1, 2, 5.0)))
        assertTrue(three.clamped)
        assertTrue(three.gains.all { it in ExposureGain.MIN_GAIN..ExposureGain.MAX_GAIN })
        assertEquals(1.0, three.gains.average(), 0.15)
    }

    @Test
    fun `a gain inside the limits is not marked clamped`() {
        val result = ExposureGain.solve(3, listOf(pair(0, 1, 1.6), pair(1, 2, 0.7)))
        assertFalse(result.clamped)
    }

    @Test
    fun `bad pairs are refused`() {
        for (bad in listOf(pair(0, 0, 1.0), pair(0, 5, 1.0), pair(-1, 1, 1.0), pair(0, 1, 0.0), pair(0, 1, Double.NaN), pair(0, 1, 1.0, 0.0))) {
            try { ExposureGain.solve(2, listOf(bad)); fail("accepted a bad pair") } catch (_: IllegalArgumentException) {}
        }
        try { ExposureGain.solve(0, emptyList()); fail("no images") } catch (_: IllegalArgumentException) {}
        try { ExposureGain.measureAll(listOf(GrayImage(1, 1, ByteArray(1))), emptyList()); fail("positions") } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `measuring can be cancelled between pairs`() {
        val s = sweep(1, 4, 0.65, shot = gainShot(listOf(1.0, 1.0, 1.0, 1.0)))
        var calls = 0
        try {
            ExposureGain.measureAll(s.images, s.positions) { ++calls >= 2 }
            fail("not cancelled")
        } catch (_: CancellationException) {}
        assertEquals(2, calls)
    }

    // ---- limits: what a pure gain cannot be expected to fix ------------------------------------

    @Test
    fun `limits are measured and bounded`() {
        val exposure = listOf(1.0, 0.8, 1.25, 0.9)
        data class Row(val label: String, val errors: List<Double>)
        val rows = ArrayList<Row>()

        fun trial(label: String, jitter: Int = 0, shot: (List<Double>) -> (Int) -> SyntheticPanoScene.Shot): Row {
            val errors = ArrayList<Double>()
            for (seed in 1..3) for (overlap in listOf(0.25, 0.45, 0.65)) {
                val s = sweep(seed, exposure.size, overlap, jitter, shot(exposure))
                errors += worstError(ExposureGain.compute(s.images, s.positions), exposure)
            }
            return Row(label, errors).also { rows += it }
        }

        val clean = trial("pure gain") { gainShot(it) }
        val noisy = trial("noise 3%") { gainShot(it, noise = 0.03 * 255) }
        val vignette = trial("vignette 0.3") { gainShot(it, noise = 0.03 * 255, vignette = 0.3) }
        val bias10 = trial("bias +-10 grey") { gainShot(it, noise = 0.03 * 255, bias = 10.0) }
        val bias25 = trial("bias +-25 grey") { gainShot(it, noise = 0.03 * 255, bias = 25.0) }
        for (row in rows) {
            println("PANO-GAIN %-16s worst error %5.2f%%  mean %5.2f%%  over 9 sweeps of 4".format(row.label, 100 * row.errors.max(), 100 * row.errors.average()))
        }
        assertTrue(clean.errors.max() <= 0.02)
        assertTrue(noisy.errors.max() <= 0.02)
        // The vignette is symmetric about the overlap so it mostly cancels.
        assertTrue("vignette ${vignette.errors.max()}", vignette.errors.max() <= 0.04)
        // An offset is NOT a gain: the error is real and about the offset over the mean brightness (the
        // frames alternate +b and -b, the worst case, so the neighbours differ by 2b of about 130 grey).
        // Measured 10.5% at +-10 and 26.4% at +-25; the guards sit just above so a regression shows.
        assertTrue("bias 10: ${bias10.errors.max()}", bias10.errors.max() <= 0.13)
        assertTrue("bias 25: ${bias25.errors.max()}", bias25.errors.max() <= 0.30)
    }

    @Test
    fun `a moving subject is shrugged off by the block median where the plain mean is not`() {
        val exposure = listOf(1.0, 1.2)
        val errorsMedian = ArrayList<Double>()
        val errorsMean = ArrayList<Double>()
        for (seed in 1..3) for (overlap in listOf(0.45, 0.65)) {
            // A dark figure in the second frame only, filling about a quarter of the overlap.
            val figure = SyntheticPanoScene.Blob(300, 450, 150, 300, 20f)
            val s = sweep(seed, 2, overlap) { i ->
                SyntheticPanoScene.Shot(gain = exposure[i], blob = if (i == 1) figure else null, seed = 50L + i)
            }
            val dx = s.positions[1].x
            val truth = exposure[0] / exposure[1]
            errorsMedian += abs(ExposureGain.measure(s.images[0], s.images[1], dx, 0)!!.ratio / truth - 1)
            errorsMean += abs(plainMeanRatio(s.images[0], s.images[1], dx, 0) / truth - 1)
        }
        println("PANO-GAIN moving subject (a quarter of the overlap): block median error %.2f%%, plain mean error %.2f%%".format(100 * errorsMedian.max(), 100 * errorsMean.max()))
        assertTrue("median ${errorsMedian.max()}", errorsMedian.max() <= 0.02)
        assertTrue("the control must hurt the plain mean: ${errorsMean.max()}", errorsMean.max() > 0.04)
    }

    @Test
    fun `a subject covering most of the overlap does defeat it and the answer stays inside the limits`() {
        // Honest limit: past half the overlap the median follows the mover, not the scene. The ratio is
        // wrong (a dark figure fills most of the overlap), and all that can be promised is the limits.
        val exposure = listOf(1.0, 1.0)
        val figure = SyntheticPanoScene.Blob(330, 450, 330, 440, 20f)
        val s = sweep(1, 2, 0.45) { i -> SyntheticPanoScene.Shot(gain = exposure[i], blob = if (i == 1) figure else null, seed = 70L + i) }
        val r = ExposureGain.measure(s.images[0], s.images[1], s.positions[1].x, 0)!!
        val result = ExposureGain.compute(s.images, s.positions)
        println("PANO-GAIN subject covering ~70 percent of the overlap: ratio %.2f (truth 1.00), gains %s, clamped %s".format(r.ratio, result.gains.map { "%.2f".format(it) }, result.clamped))
        assertTrue(r.ratio > 1.5)
        assertTrue(result.gains.all { it in ExposureGain.MIN_GAIN..ExposureGain.MAX_GAIN })
    }

    /** What the design's "ratio of mean colours" would give: used only as the control above. */
    private fun plainMeanRatio(a: GrayImage, b: GrayImage, dx: Int, dy: Int): Double {
        var sumA = 0.0
        var sumB = 0.0
        var n = 0
        for (y in max(0, dy) until minOf(a.height, dy + b.height)) {
            for (x in max(0, dx) until minOf(a.width, dx + b.width)) {
                val va = a.at(x, y)
                val vb = b.at(x - dx, y - dy)
                if (va <= 4 || va >= 251 || vb <= 4 || vb >= 251) continue
                sumA += va; sumB += vb; n++
            }
        }
        return sumA / sumB
    }
}
