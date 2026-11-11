package com.fotoxplorr.app.pano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * The aligner against frames cut from procedural scenes whose true offset is known by construction
 * (see [SyntheticPanoScene] for what the scenes imitate and, just as important, what they do not).
 * The first two tests are the feature's kill criterion, written down before any code existed: within
 * 2 px at 25, 45 and 65 percent overlap on the clean scene, within 3 px with the design's handheld
 * imperfections and a moving subject. They are not to be loosened to pass; the measured limits of the
 * harder conditions are reported by [PairAlignerLimitsTest] instead.
 */
class PairAlignerGoldenTest {

    companion object {
        @JvmStatic
        @org.junit.AfterClass
        fun release() = SyntheticPanoScene.releaseRigs()
    }

    private val overlaps = listOf(0.25, 0.45, 0.65)
    private val seeds = listOf(1, 2, 3)

    private fun worst(offsets: List<Pair<Offset, SyntheticPanoScene.Pair>>): Int =
        offsets.maxOf { (o, p) -> max(abs(o.dx - p.dx), abs(o.dy - p.dy)) }

    @Test
    fun `clean frames are recovered within 2 px at 25, 45 and 65 percent overlap`() {
        val results = ArrayList<Pair<Offset, SyntheticPanoScene.Pair>>()
        for (overlap in overlaps) for (seed in seeds) {
            val pair = SyntheticPanoScene.rig(seed, overlap)
                .take(SyntheticPanoScene.jitterFor(seed, overlap), SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
            val offset = PairAligner.align(pair.first, pair.second)
            results += offset to pair
            assertTrue("overlap $overlap seed $seed: not confident, score ${offset.score}", offset.confident)
            assertTrue(
                "overlap $overlap seed $seed: got (${offset.dx},${offset.dy}), truth (${pair.dx},${pair.dy})",
                abs(offset.dx - pair.dx) <= 2 && abs(offset.dy - pair.dy) <= 2,
            )
        }
        assertTrue("worst clean error ${worst(results)} px", worst(results) <= 2)
    }

    @Test
    fun `gain bias noise jitter and a moving subject stay within 3 px`() {
        val results = ArrayList<Pair<Offset, SyntheticPanoScene.Pair>>()
        for (overlap in overlaps) for (seed in seeds) {
            val random = Random(seed * 17L + Math.round(overlap * 100))
            // One moving subject, a dark 140x260 px figure that is in the second frame only and stands in
            // the overlap (the design's prototype did the same). A subject in BOTH frames is harsher and
            // is reported by PairAlignerLimitsTest.
            val subject = SyntheticPanoScene.Blob(300, 450, 70, 130, 40f)
            val pair = SyntheticPanoScene.rig(seed, overlap).take(
                SyntheticPanoScene.jitterFor(seed, overlap),
                SyntheticPanoScene.handheldShot(random, seed.toLong()),
                SyntheticPanoScene.handheldShot(random, seed + 100L, subject),
            )
            val offset = PairAligner.align(pair.first, pair.second)
            results += offset to pair
            assertTrue(
                "overlap $overlap seed $seed: got (${offset.dx},${offset.dy}), truth (${pair.dx},${pair.dy}), score ${offset.score}",
                abs(offset.dx - pair.dx) <= 3 && abs(offset.dy - pair.dy) <= 3,
            )
            assertTrue("overlap $overlap seed $seed: the right answer was not trusted (score ${offset.score})", offset.confident)
        }
        assertTrue("worst error ${worst(results)} px", worst(results) <= 3)
    }

    @Test
    fun `the second photo may sit left of the first`() {
        val pair = SyntheticPanoScene.rig(1, 0.45)
            .take(7, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
        val backwards = PairAligner.align(pair.second, pair.first)
        assertTrue(backwards.confident)
        assertTrue("dx ${backwards.dx} vs ${-pair.dx}", abs(backwards.dx + pair.dx) <= 2)
        assertTrue("dy ${backwards.dy} vs ${-pair.dy}", abs(backwards.dy + pair.dy) <= 2)
    }

    @Test
    fun `the same photo twice sits at the origin`() {
        val image = SyntheticPanoScene.rig(2, 0.45).frame(0, 0, SyntheticPanoScene.Shot(noiseSigma = 7.6, seed = 3))
        val offset = PairAligner.align(image, image)
        assertEquals(0, offset.dx)
        assertEquals(0, offset.dy)
        assertEquals(1.0f, offset.score, 1e-3f)
        assertEquals(1.0f, offset.overlapFraction, 1e-6f)
        assertTrue(offset.confident)
    }

    @Test
    fun `photos of different sizes are placed by their top-left corners`() {
        val whole = SyntheticPanoScene.rig(3, 0.45).frame(0, 0, SyntheticPanoScene.Shot())
        val second = SyntheticPanoScene.crop(whole, 400, 60, 700, 600)
        val offset = PairAligner.align(whole, second)
        assertTrue("got (${offset.dx},${offset.dy})", abs(offset.dx - 400) <= 1 && abs(offset.dy - 60) <= 1)
        assertTrue(offset.confident)
        assertEquals(1.0f, offset.overlapFraction, 1e-3f)
    }

    @Test
    fun `smaller images with a shorter pyramid still align`() {
        val whole = SyntheticPanoScene.rig(1, 0.65).frame(0, 0, SyntheticPanoScene.Shot(noiseSigma = 3.0, seed = 1))
        val a = SyntheticPanoScene.crop(whole, 100, 100, 400, 300)
        val b = SyntheticPanoScene.crop(whole, 290, 108, 400, 300)
        val offset = PairAligner.align(a, b)
        assertTrue("got (${offset.dx},${offset.dy})", abs(offset.dx - 190) <= 2 && abs(offset.dy - 8) <= 2)
        assertTrue(offset.confident)
    }

    @Test
    fun `thumbnail sized images do not crash and are not trusted`() {
        val whole = SyntheticPanoScene.rig(1, 0.65).frame(0, 0, SyntheticPanoScene.Shot())
        for (size in listOf(1, 5, 16, 40)) {
            val a = SyntheticPanoScene.crop(whole, 100, 100, size, size)
            val b = SyntheticPanoScene.crop(whole, 100 + size / 2, 100, size, size)
            val offset = PairAligner.align(a, b)
            assertTrue("size $size: score ${offset.score}", offset.score.isFinite())
            assertFalse("size $size is far too small to trust", offset.confident)
        }
    }

    @Test
    fun `the same inputs give the same answer`() {
        val pair = SyntheticPanoScene.rig(2, 0.25).take(-11, SyntheticPanoScene.Shot(noiseSigma = 5.0, seed = 1), SyntheticPanoScene.Shot(noiseSigma = 5.0, seed = 2))
        assertEquals(PairAligner.align(pair.first, pair.second), PairAligner.align(pair.first, pair.second))
    }

    @Test
    fun `a stop request ends the search with a cancellation`() {
        val pair = SyntheticPanoScene.rig(1, 0.45).take(3, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
        try {
            PairAligner.align(pair.first, pair.second, shouldStop = { true })
            fail("a search told to stop from the start must not finish")
        } catch (expected: CancellationException) {
        }
        var asked = 0
        try {
            PairAligner.align(pair.first, pair.second, shouldStop = { ++asked > 2_000 })
            fail("a stop part-way must be honoured")
        } catch (expected: CancellationException) {
        }
        assertEquals("it noticed on the very next slide", 2_001, asked)
    }

    @Test
    fun `the answer is the same whether or not anyone is asking to stop`() {
        val pair = SyntheticPanoScene.rig(3, 0.45).take(9, SyntheticPanoScene.Shot(), SyntheticPanoScene.Shot())
        var asked = 0
        val watched = PairAligner.align(pair.first, pair.second, shouldStop = { asked++; false })
        assertEquals(PairAligner.align(pair.first, pair.second), watched)
        assertTrue("the search checks often enough to be cancellable", asked > 1_000)
        assertFalse(watched.dx == 0 && watched.dy == 0)
    }
}
