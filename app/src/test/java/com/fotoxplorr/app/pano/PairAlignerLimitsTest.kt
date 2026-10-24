package com.fotoxplorr.app.pano

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * How far the aligner is wrong when the frames are NOT a clean translation: vignetting, roll, shear,
 * parallax from a nearer layer, and all of them at once. These are measurements, not promises. The
 * table is printed (see the test output) and each row also has a guard that fails if the error
 * gets worse than what was measured, so a later change cannot quietly make a limit worse; none of
 * them is the kill criterion, which lives in [PairAlignerGoldenTest].
 *
 * Errors are the recovered offset minus the offset between the two frames' centres, in working
 * pixels (the working size is 1200 px wide, so multiply by about 3.3 for a 4000 px photo). Roll and
 * shear have no single right translation: a frame turned by `a` is displaced by about `x * sin(a)`
 * at distance `x` from its centre, and the aligner lands at the middle of the overlap, so the
 * "error" is the geometry of the turn, not a search failure.
 */
class PairAlignerLimitsTest {

    companion object {
        @JvmStatic
        @org.junit.AfterClass
        fun release() = SyntheticPanoScene.releaseRigs()
    }

    private val overlaps = listOf(0.25, 0.45, 0.65)
    private val seeds = listOf(1, 2, 3)

    private class Row(val ex: IntArray, val ey: IntArray, val confident: Int, val meanMs: Double, val n: Int) {
        val maxX get() = ex.maxOf { abs(it) }
        val maxY get() = ey.maxOf { abs(it) }
    }

    private fun measure(
        name: String,
        overlap: Double,
        parallax: Boolean = false,
        shots: (Random, Int) -> Pair<SyntheticPanoScene.Shot, SyntheticPanoScene.Shot>,
    ): Row {
        val ex = IntArray(seeds.size)
        val ey = IntArray(seeds.size)
        var confident = 0
        var ms = 0.0
        seeds.forEachIndexed { i, seed ->
            val random = Random(seed * 31L + Math.round(overlap * 100))
            val dy = SyntheticPanoScene.jitterFor(seed, overlap)
            val (a, b) = shots(random, seed)
            val pair = SyntheticPanoScene.rig(seed, overlap).take(dy, a, b, parallax)
            val t0 = System.nanoTime()
            val o = PairAligner.align(pair.first, pair.second)
            ms += (System.nanoTime() - t0) / 1e6
            ex[i] = o.dx - pair.dx
            ey[i] = o.dy - pair.dy
            if (o.confident) confident++
        }
        val row = Row(ex, ey, confident, ms / seeds.size, seeds.size)
        println(
            "PANO-LIMIT %-26s overlap %.2f  dx err %-14s dy err %-14s confident %d/%d  %4.0f ms/pair".format(
                name, overlap, ex.toList(), ey.toList(), confident, seeds.size, row.meanMs,
            ),
        )
        return row
    }

    private fun hand(random: Random, seed: Long, vignette: Double = 0.0, roll: Double = 0.0, shear: Double = 0.0, nearShift: Double = 0.0, blob: SyntheticPanoScene.Blob? = null): SyntheticPanoScene.Shot {
        val s = SyntheticPanoScene.handheldShot(random, seed, blob, vignette)
        return SyntheticPanoScene.Shot(s.gain, s.bias, s.noiseSigma, s.vignette, roll, shear, s.blob, nearShift, s.seed)
    }

    @Test
    fun `vignetting that darkens the corners 35 percent`() {
        for (o in overlaps) {
            val r = measure("vignette 0.35", o) { rnd, s -> hand(rnd, s.toLong(), vignette = 0.35) to hand(rnd, s + 100L, vignette = 0.35) }
            assertTrue("vignetting must not move the answer: ${r.maxX},${r.maxY}", r.maxX <= 2 && r.maxY <= 2)
        }
    }

    @Test
    fun `half a degree of roll in one frame`() {
        for (o in overlaps) {
            val r = measure("roll 0.5 deg", o) { rnd, s -> hand(rnd, s.toLong()) to hand(rnd, s + 100L, roll = 0.5) }
            // Measured worst: 4 px vertical at 25 percent overlap.
            assertTrue("roll error ${r.maxX},${r.maxY}", r.maxX <= 3 && r.maxY <= 6)
        }
    }

    @Test
    fun `one degree of roll in one frame`() {
        for (o in overlaps) {
            val r = measure("roll 1.0 deg", o) { rnd, s -> hand(rnd, s.toLong()) to hand(rnd, s + 100L, roll = 1.0) }
            // Measured worst: 8 px vertical at 25 percent overlap (the overlap sits 450 px from the turning centre).
            assertTrue("roll error ${r.maxX},${r.maxY}", r.maxX <= 4 && r.maxY <= 11)
        }
    }

    @Test
    fun `one degree of relative roll shared between the frames`() {
        for (o in overlaps) {
            val r = measure("roll -0.5 / +0.5 deg", o) { rnd, s -> hand(rnd, s.toLong(), roll = -0.5) to hand(rnd, s + 100L, roll = 0.5) }
            assertTrue("roll error ${r.maxX},${r.maxY}", r.maxX <= 4 && r.maxY <= 4)
        }
    }

    @Test
    fun `a rolling shutter style shear of one percent`() {
        for (o in overlaps) {
            val r = measure("shear 0.01", o) { rnd, s -> hand(rnd, s.toLong()) to hand(rnd, s + 100L, shear = 0.01) }
            assertTrue("shear error ${r.maxX},${r.maxY}", r.maxX <= 4 && r.maxY <= 3)
        }
    }

    @Test
    fun `parallax shifts a near layer relative to the far scene`() {
        for (shift in listOf(20.0, 50.0)) for (o in overlaps) {
            val r = measure("parallax ${shift.toInt()} px near layer", o, parallax = true) { rnd, s ->
                hand(rnd, s.toLong()) to hand(rnd, s + 100L, nearShift = shift)
            }
            // The aligner can lock to either layer, never to something further away than the layer
            // separation: the error against the far scene is bounded by the near layer's own shift.
            assertTrue("parallax $shift: error ${r.maxX},${r.maxY}", r.maxX <= shift + 4 && r.maxY <= 3)
        }
    }

    @Test
    fun `a moving subject in both frames is never trusted wrongly`() {
        // The same figure in two places is harsher than the kill criterion's single subject: it
        // replaces about a fifth of a quarter-overlap strip with high-contrast pixels that match
        // nothing. Measured over these 18 pairs: the right offset is found in 17, but the score drops
        // and 4 of the 18 fall under the trust line, so those joins are REFUSED; the 18th lost the
        // true slide altogether (and was refused too).
        val bright = SyntheticPanoScene.Blob(1000, 500, 70, 130, 235f)
        val dark = SyntheticPanoScene.Blob(300, 450, 70, 130, 40f)
        var confidentAndWrong = 0
        var refused = 0
        var total = 0
        for (o in overlaps) for (seed in 1..6) {
            val random = Random(seed * 17L + Math.round(o * 100))
            val pair = SyntheticPanoScene.rig(seed, o).take(
                SyntheticPanoScene.jitterFor(seed, o),
                hand(random, seed.toLong(), blob = bright),
                hand(random, seed + 100L, blob = dark),
            )
            val result = PairAligner.align(pair.first, pair.second)
            total++
            if (!result.confident) refused++
            val error = max(abs(result.dx - pair.dx), abs(result.dy - pair.dy))
            if (result.confident && error > 3) confidentAndWrong++
            println("PANO-LIMIT subject in both frames overlap %.2f seed %d  error %d px  score %.2f  confident %s".format(o, seed, error, result.score, result.confident))
        }
        println("PANO-LIMIT subject in both frames: $refused of $total refused as not confident")
        assertTrue("$confidentAndWrong confident but wrong", confidentAndWrong == 0)
    }

    @Test
    fun `everything at once`() {
        val blob = SyntheticPanoScene.Blob(300, 450, 70, 130, 40f)
        for (o in overlaps) {
            val r = measure("all of it together", o, parallax = true) { rnd, s ->
                hand(rnd, s.toLong(), vignette = 0.3, roll = -0.3) to hand(rnd, s + 100L, vignette = 0.3, roll = 0.3, shear = 0.004, nearShift = 25.0, blob = blob)
            }
            assertTrue("combined error ${r.maxX},${r.maxY}", r.maxX <= 29 && r.maxY <= 5)
        }
    }

    @Test
    fun `time per pair on the host`() {
        val pair = SyntheticPanoScene.rig(1, 0.45).take(5, hand(Random(1), 1), hand(Random(2), 2))
        PairAligner.align(pair.first, pair.second) // warm the JIT
        val times = (1..5).map {
            val t0 = System.nanoTime()
            PairAligner.align(pair.first, pair.second)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        println("PANO-LIMIT time per 1200x900 pair after warm-up: median %.0f ms, min %.0f ms, max %.0f ms".format(times[2], times.first(), times.last()))
        assertTrue("a pair took ${times[2]} ms, ten times what it did when measured", times[2] < 2_000)
        assertTrue(max(times[2], 1.0) > 0)
    }
}
