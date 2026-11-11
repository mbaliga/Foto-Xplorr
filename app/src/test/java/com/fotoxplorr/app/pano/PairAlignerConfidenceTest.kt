package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The aligner must never be CONFIDENT about a wrong answer. These are the pictures a real sweep
 * contains that have no single right slide -- empty sky, a fence, a tiled wall, photos that do not
 * share a view -- each run over several noise seeds, and every one must come back `confident = false`.
 * A control at the end proves the checks are not simply refusing everything.
 */
class PairAlignerConfidenceTest {

    companion object {
        @JvmStatic
        @org.junit.AfterClass
        fun release() = SyntheticPanoScene.releaseRigs()
    }

    private val width = SyntheticPanoScene.FRAME_WIDTH
    private val height = SyntheticPanoScene.FRAME_HEIGHT
    private val seeds = 1L..4L

    private fun assertNotConfident(label: String, a: GrayImage, b: GrayImage) {
        val offset = PairAligner.align(a, b)
        assertFalse(
            "$label: confident about (${offset.dx},${offset.dy}) with score ${offset.score}, overlap ${offset.overlapFraction}",
            offset.confident,
        )
    }

    private fun both(label: String, sigma: Double, f: (Int, Int) -> Double) {
        for (seed in seeds) {
            assertNotConfident(
                "$label seed $seed",
                SyntheticPanoScene.pattern(width, height, sigma, seed, f),
                SyntheticPanoScene.pattern(width, height, sigma, seed + 100, f),
            )
        }
    }

    @Test
    fun `a flat sky is never confident`() {
        both("flat with sensor noise", 7.6) { _, _ -> 140.0 }
        both("flat with faint noise", 1.5) { _, _ -> 140.0 }
        both("perfectly flat", 0.0) { _, _ -> 140.0 }
    }

    @Test
    fun `a smooth gradient sky is never confident`() {
        both("vertical gradient", 3.0) { _, y -> 90.0 + 80.0 * y / height }
        both("diagonal gradient", 3.0) { x, y -> 90.0 + 50.0 * y / height + 30.0 * x / width }
    }

    @Test
    fun `a repeating stripe tile is never confident`() {
        both("vertical stripes, 60 px period", 4.0) { x, _ -> if ((x / 30) % 2 == 0) 80.0 else 180.0 }
        both("vertical stripes, 24 px period", 4.0) { x, _ -> if ((x / 12) % 2 == 0) 80.0 else 180.0 }
        both("horizontal stripes", 4.0) { _, y -> if ((y / 30) % 2 == 0) 80.0 else 180.0 }
    }

    @Test
    fun `a tiled facade is never confident`() {
        both("window grid", 4.0) { x, y -> if (x % 60 < 40 && y % 80 < 55) 70.0 else 190.0 }
    }

    @Test
    fun `two photos that share no view are never confident`() {
        for (seed in 1..8) {
            val random = java.util.Random(seed * 17L)
            val a = SyntheticPanoScene.scene(1300, 1020, seed * 2)
            val b = SyntheticPanoScene.scene(1300, 1020, seed * 2 + 1)
            assertNotConfident(
                "unrelated scenes seed $seed",
                SyntheticPanoScene.photograph(a, 20, 40, width, height, SyntheticPanoScene.handheldShot(random, seed.toLong())),
                SyntheticPanoScene.photograph(b, 20, 40, width, height, SyntheticPanoScene.handheldShot(random, seed + 99L)),
            )
        }
    }

    @Test
    fun `neighbouring frames with no overlap or a sliver of it are never confident`() {
        for (seed in 1..6) for (overlap in listOf(0.0, 0.05, 0.10)) {
            val random = java.util.Random(seed * 13L)
            val pair = SyntheticPanoScene.rig(seed, overlap).take(
                random.nextInt(41) - 20,
                SyntheticPanoScene.handheldShot(random, seed.toLong()),
                SyntheticPanoScene.handheldShot(random, seed + 99L),
            )
            assertNotConfident("overlap $overlap seed $seed", pair.first, pair.second)
        }
    }

    @Test
    fun `an overlap under 15 percent is called too thin even when the match is right`() {
        // The honest edge of the feature: a 14 percent overlap IS found, and still not trusted.
        val random = java.util.Random(5)
        val pair = SyntheticPanoScene.rig(1, 0.14).take(-6, SyntheticPanoScene.handheldShot(random, 1), SyntheticPanoScene.handheldShot(random, 2))
        val offset = PairAligner.align(pair.first, pair.second)
        assertTrue("found (${offset.dx},${offset.dy}) not (${pair.dx},${pair.dy})", abs(offset.dx - pair.dx) <= 2 && abs(offset.dy - pair.dy) <= 2)
        assertTrue("overlap ${offset.overlapFraction}", offset.overlapFraction < 0.15f)
        assertFalse(offset.confident)
    }

    @Test
    fun `an unreadable pair reports nothing found rather than a guess dressed as an answer`() {
        val flat = SyntheticPanoScene.pattern(width, height, 0.0, 1) { _, _ -> 128.0 }
        val offset = PairAligner.align(flat, flat)
        assertFalse(offset.confident)
        assertEquals(0f, offset.score, 0f)
    }

    @Test
    fun `control - a textured sweep is trusted, so the refusals above mean something`() {
        for (overlap in listOf(0.25, 0.45, 0.65)) {
            val pair = SyntheticPanoScene.rig(1, overlap).take(8, SyntheticPanoScene.Shot(noiseSigma = 7.6, seed = 1), SyntheticPanoScene.Shot(noiseSigma = 7.6, seed = 2))
            val offset = PairAligner.align(pair.first, pair.second)
            assertTrue("overlap $overlap", offset.confident)
            assertEquals(pair.dx, offset.dx)
            assertEquals(pair.dy, offset.dy)
        }
    }
}
