package com.fotoxplorr.app.moments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyMomentDetector] against exact, hand-built frame sequences whose right answer is known by
 * construction -- a real video only has opinions about where its key moments are; a synthetic
 * one, built one histogram at a time, has a provably correct answer.
 *
 * Two histogram shapes cover everything below:
 * - [spike] puts all mass in one bin -- two spikes in DIFFERENT bins are always at the maximum
 *   possible L1 distance (2.0) from each other, however numerically close the bin indices are,
 *   because L1 distance over a probability distribution measures shared MASS, not numeric
 *   distance between bin indices.
 * - [split] puts mass across two bins in a chosen ratio, which is what lets a test dial in a
 *   distance strictly BETWEEN "no change" and "maximum change" -- needed to prove the
 *   spacing/cap logic picks the stronger of two candidates rather than merely the first.
 */
class KeyMomentDetectorTest {

    private fun spike(positionMs: Long, bin: Int, luma: Float = 0.5f): FrameSignature {
        val histogram = IntArray(FrameSignature.HISTOGRAM_BINS)
        histogram[bin] = 10_000
        return FrameSignature(positionMs, histogram, luma)
    }

    private fun split(positionMs: Long, binA: Int, fractionA: Float, binB: Int, luma: Float = 0.5f): FrameSignature {
        val histogram = IntArray(FrameSignature.HISTOGRAM_BINS)
        val massA = (10_000 * fractionA).toInt()
        histogram[binA] = massA
        histogram[binB] = 10_000 - massA
        return FrameSignature(positionMs, histogram, luma)
    }

    // ---- the property that matters most ----

    @Test
    fun `a static clip of identical frames yields no moments`() {
        // A tripod shot, or a screen recording of a still slide: forty samples, all identical.
        // If this ever found a "moment" here it would be finding one in noise, and a feature
        // that always finds something is one people switch off -- see the class doc.
        val signatures = (0 until 40).map { i -> spike(positionMs = i * 500L, bin = 20) }

        val moments = KeyMomentDetector.detect(signatures, durationMs = 20_000L)

        assertTrue("a static clip must yield no moments, got $moments", moments.isEmpty())
    }

    // ---- hard cuts are found, at the right position, with a confident score ----

    @Test
    fun `hard cuts are detected at their positions`() {
        // Four four-second scenes, each internally static, each a completely different tone from
        // its neighbour. Three cuts, each 4000ms apart -- comfortably past the 3s minimum
        // spacing, so nothing here should be collapsed by non-max suppression.
        val bins = listOf(5, 40, 15, 55)
        val signatures = bins.flatMapIndexed { scene, bin ->
            val sceneStartMs = scene * 4000L
            listOf(0L, 1000L, 2000L, 3000L).map { offset -> spike(sceneStartMs + offset, bin) }
        }

        val moments = KeyMomentDetector.detect(signatures, durationMs = 16_000L)

        assertEquals(listOf(4000L, 8000L, 12000L), moments.map { it.positionMs })
        moments.forEach { moment ->
            assertEquals("a maximal cut should read as full confidence", 1f, moment.confidence, 0.001f)
            assertEquals("Scene change", moment.label)
        }
    }

    // ---- two cuts closer than the minimum spacing collapse to the stronger one ----

    @Test
    fun `cuts closer than the minimum spacing collapse to the stronger one`() {
        val signatures = listOf(
            split(0L, binA = 10, fractionA = 0.75f, binB = 50),
            split(400L, binA = 10, fractionA = 0.75f, binB = 50), // identical to the frame before it
            split(800L, binA = 10, fractionA = 0.25f, binB = 50), // weaker cut: distance 1.0
            spike(1200L, bin = 60), // stronger cut only 400ms later: distance 2.0
            spike(1600L, bin = 60), // identical to the frame before it
        )

        val moments = KeyMomentDetector.detect(signatures, durationMs = 2_000L)

        assertEquals("the weaker, nearby candidate must be suppressed", 1, moments.size)
        assertEquals(1200L, moments.single().positionMs)
    }

    // ---- the cap is honoured, keeping the strongest candidates, not just the first ones ----

    @Test
    fun `the moment cap keeps the strongest candidates when there are more than room for`() {
        // Six well-separated (4000ms apart, well past the 3s spacing floor) scenes, alternating
        // strong (fully disjoint, distance 2.0) and weak (partial-overlap, distance well under
        // 2.0 but still past the candidate floor) transitions. Five candidates in total; asking
        // for three must keep exactly the three strong ones, wherever they fall, not "the first
        // three encountered".
        val signatures = listOf(
            spike(0L, bin = 5), spike(2000L, bin = 5),
            spike(4000L, bin = 55), spike(6000L, bin = 55), // transition@4000: strong (2.0)
            split(8000L, binA = 55, fractionA = 0.6f, binB = 20), split(10000L, binA = 55, fractionA = 0.6f, binB = 20), // @8000: weak (0.8)
            spike(12000L, bin = 30), spike(14000L, bin = 30), // @12000: strong (2.0)
            split(16000L, binA = 30, fractionA = 0.65f, binB = 45), split(18000L, binA = 30, fractionA = 0.65f, binB = 45), // @16000: weak (0.7)
            spike(20000L, bin = 62), spike(22000L, bin = 62), // @20000: strong (2.0)
        )

        val moments = KeyMomentDetector.detect(signatures, durationMs = 24_000L, maxMoments = 3)

        assertEquals(listOf(4000L, 12000L, 20000L), moments.map { it.positionMs })
    }

    @Test
    fun `the default cap of eight is honoured when nothing else would trim the list`() {
        // Ten transitions, all tied at maximum strength and all 4000ms apart -- nothing here
        // would be removed by spacing, so the count coming back to exactly eight is the cap and
        // nothing else.
        val signatures = (0..10).map { i -> spike(i * 4000L, bin = if (i % 2 == 0) 10 else 50) }

        val moments = KeyMomentDetector.detect(signatures, durationMs = 44_000L)

        assertEquals(8, moments.size)
    }

    // ---- crash safety on degenerate input ----

    @Test
    fun `an empty signature list does not crash and yields no moments`() {
        assertTrue(KeyMomentDetector.detect(emptyList(), durationMs = 10_000L).isEmpty())
    }

    @Test
    fun `a single-frame signature list does not crash and yields no moments`() {
        val moments = KeyMomentDetector.detect(listOf(spike(0L, bin = 10)), durationMs = 10_000L)
        assertTrue(moments.isEmpty())
    }

    // ---- ordering: output is sorted by position, and input order is not trusted ----

    @Test
    fun `moments come back sorted by position even when fed out of order and even when the strongest one is not first`() {
        val early = spike(0L, bin = 5) // baseline
        val weakMiddle = split(2000L, binA = 5, fractionA = 0.7f, binB = 30) // distance 0.6 from `early`
        val strongLate = spike(6000L, bin = 60) // distance 2.0 from `weakMiddle`

        // Fed in scrambled order on purpose: a detector that trusted list order instead of
        // sorting by positionMs would compare the wrong pairs of frames and report nonsense.
        val moments = KeyMomentDetector.detect(listOf(strongLate, early, weakMiddle), durationMs = 8_000L)

        assertEquals(listOf(2000L, 6000L), moments.map { it.positionMs })
    }

    // ---- labels: a best-effort guess from the two signals available, each checked in isolation ----

    @Test
    fun `a spike dominated by a mean-luma shift is labelled a brightness shift`() {
        val signatures = listOf(spike(0L, bin = 10, luma = 0.1f), spike(500L, bin = 50, luma = 0.9f))

        val moment = KeyMomentDetector.detect(signatures, durationMs = 1_000L).single()

        assertEquals("Brightness shift", moment.label)
    }

    @Test
    fun `a maximal histogram change with a small luma shift is labelled a scene change`() {
        val signatures = listOf(spike(0L, bin = 10, luma = 0.50f), spike(500L, bin = 50, luma = 0.55f))

        val moment = KeyMomentDetector.detect(signatures, durationMs = 1_000L).single()

        assertEquals("Scene change", moment.label)
    }

    @Test
    fun `a moderate histogram change with no luma shift is labelled big movement`() {
        val signatures = listOf(
            split(0L, binA = 10, fractionA = 0.75f, binB = 50, luma = 0.5f),
            split(500L, binA = 10, fractionA = 0.25f, binB = 50, luma = 0.5f),
        )

        val moment = KeyMomentDetector.detect(signatures, durationMs = 1_000L).single()

        assertEquals("Big movement", moment.label)
    }

    // ---- other degenerate inputs ----

    @Test
    fun `a non-positive duration yields no moments regardless of the frames given`() {
        val signatures = listOf(spike(0L, bin = 5), spike(500L, bin = 55))
        assertTrue(KeyMomentDetector.detect(signatures, durationMs = 0L).isEmpty())
    }

    @Test
    fun `a maxMoments of zero yields no moments even with strong candidates present`() {
        val signatures = listOf(spike(0L, bin = 5), spike(500L, bin = 55))
        assertTrue(KeyMomentDetector.detect(signatures, durationMs = 1_000L, maxMoments = 0).isEmpty())
    }
}
