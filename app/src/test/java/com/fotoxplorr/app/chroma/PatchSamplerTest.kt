package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PatchSamplerTest {

    private fun value(a: Answer<Patch>): Patch = (a as Answer.Value).value
    private fun reason(a: Answer<Patch>): Refusal = (a as Answer.Refused).reason

    @Test
    fun aFlatPatchGivesItsOwnLinearLevel() {
        val p = value(PatchSampler.sample(ChromaTestKit.flat(128, 64, 200)))
        assertEquals(ColourSpace.decode(128), p.linear.r, 1e-12)
        assertEquals(ColourSpace.decode(64), p.linear.g, 1e-12)
        assertEquals(ColourSpace.decode(200), p.linear.b, 1e-12)
        assertEquals(0.0, p.clippedShare, 0.0)
        assertTrue(p.evenness < 1e-9)
    }

    @Test
    fun onlyTheMiddleIsRead() {
        val rng = Random(1)
        val f = ChromaTestKit.frame(
            LinearRgb(0.2, 0.4, 0.1), LinearRgb(1.0, 1.0, 1.0), 1.0, 0.0, 0.0, rng, ring = LinearRgb(0.9, 0.9, 0.9),
        )
        val p = value(PatchSampler.sample(f))
        assertEquals(0.2, p.linear.r, 0.003)
        assertEquals(0.4, p.linear.g, 0.004)
    }

    @Test
    fun aSpeckAndAStrayEdgePixelDoNotMoveTheMedian() {
        val f = ChromaTestKit.flat(120, 120, 120)
        val px = f.argb
        // Ten per cent of the middle is dust: black and white specks.
        for (i in 0 until 30) px[(20 + i % 8) * f.width + 18 + i / 8] = if (i % 2 == 0) ChromaTestKit.argb(30, 30, 30) else ChromaTestKit.argb(240, 240, 240)
        val p = value(PatchSampler.sample(f))
        assertEquals(ColourSpace.decode(120), p.linear.g, 2e-3)
    }

    @Test
    fun aClippedPatchIsRefused() {
        assertEquals(Refusal.CLIPPED, reason(PatchSampler.sample(ChromaTestKit.flat(255, 255, 255))))
        // One channel pinned at the top is clipped even though the others are fine.
        assertEquals(Refusal.CLIPPED, reason(PatchSampler.sample(ChromaTestKit.flat(255, 100, 100))))
        assertEquals(Refusal.CLIPPED, reason(PatchSampler.sample(ChromaTestKit.flat(PatchSampler.CLIP_CODE, 50, 50))))
    }

    @Test
    fun aLittleClippingIsTolerated() {
        val f = ChromaTestKit.flat(120, 120, 120)
        // 5% of the middle blown out.
        var n = 0
        loop@ for (y in 15 until 33) for (x in 15 until 33) { f.argb[y * f.width + x] = ChromaTestKit.argb(255, 255, 255); if (++n >= 12) break@loop }
        val p = value(PatchSampler.sample(f))
        assertTrue(p.clippedShare in 0.01..PatchSampler.MAX_CLIPPED_SHARE)
        assertEquals(ColourSpace.decode(120), p.linear.g, 1e-9)
    }

    @Test
    fun aCrushedPatchIsRefused() {
        assertEquals(Refusal.TOO_DARK, reason(PatchSampler.sample(ChromaTestKit.flat(3, 2, 4))))
        assertEquals(Refusal.TOO_DARK, reason(PatchSampler.sample(ChromaTestKit.flat(0, 0, 0))))
    }

    @Test
    fun anAmbientFrameOfAnUnlitRoomIsKeptWhenAskedTo() {
        val black = ChromaTestKit.flat(0, 1, 0)
        assertEquals(Refusal.TOO_DARK, reason(PatchSampler.sample(black)))
        val p = value(PatchSampler.sample(black, keepDark = true))
        assertTrue(p.linear.g < 0.001)
        // Clipping is still refused for an ambient frame.
        assertEquals(Refusal.CLIPPED, reason(PatchSampler.sample(ChromaTestKit.flat(255, 255, 255), keepDark = true)))
    }

    @Test
    fun aPatchOverTwoSurfacesIsRefusedAsUneven() {
        val f = ChromaTestKit.flat(60, 60, 60)
        for (y in 0 until f.height) for (x in f.width / 2 until f.width) f.argb[y * f.width + x] = ChromaTestKit.argb(200, 200, 200)
        assertEquals(Refusal.UNEVEN, reason(PatchSampler.sample(f)))
    }

    @Test
    fun noiseAtTwoPercentIsNotUneven() {
        val rng = Random(2)
        val f = ChromaTestKit.frame(LinearRgb(0.3, 0.3, 0.3), LinearRgb(1.0, 1.0, 1.0), 1.0, 0.02, 0.0, rng, ring = LinearRgb(0.3, 0.3, 0.3))
        val p = value(PatchSampler.sample(f))
        assertTrue("evenness ${p.evenness}", p.evenness < 0.1)
        assertEquals(0.3, p.linear.g, 0.003)
    }

    @Test
    fun standardErrorShrinksWithMorePixelsAndNeverReachesZero() {
        val rng = Random(4)
        val small = value(PatchSampler.sample(ChromaTestKit.frame(LinearRgb(0.3, 0.3, 0.3), LinearRgb(1.0, 1.0, 1.0), 1.0, 0.05, 0.0, rng, ring = LinearRgb(0.3, 0.3, 0.3), size = 24)))
        val big = value(PatchSampler.sample(ChromaTestKit.frame(LinearRgb(0.3, 0.3, 0.3), LinearRgb(1.0, 1.0, 1.0), 1.0, 0.05, 0.0, rng, ring = LinearRgb(0.3, 0.3, 0.3), size = 96)))
        assertTrue(big.standardError.g < small.standardError.g)
        val flat = value(PatchSampler.sample(ChromaTestKit.flat(128, 128, 128)))
        assertTrue("a flat patch is still only known to a code step", flat.standardError.g > 0.0)
    }

    @Test
    fun aTinyRegionIsRefused() {
        val f = ChromaTestKit.flat(100, 100, 100, size = 48)
        assertEquals(Refusal.TOO_SMALL, reason(PatchSampler.sample(f, PatchRegion(0.5, 0.5, 0.52, 0.52))))
    }

    @Test
    fun aHugeRegionIsSubsampledAndStillRight() {
        val f = ChromaTestKit.flat(90, 140, 40, size = 1200)
        val p = value(PatchSampler.sample(f, PatchRegion(0.0, 0.0, 1.0, 1.0)))
        assertTrue(p.used <= PatchSampler.MAX_SAMPLES)
        assertEquals(ColourSpace.decode(140), p.linear.g, 1e-12)
    }

    @Test
    fun theSamePixelsGiveTheSamePatch() {
        val rng = Random(9)
        val f = ChromaTestKit.frame(LinearRgb(0.2, 0.5, 0.3), LinearRgb(1.0, 1.0, 1.0), 1.0, 0.03, 0.0, rng)
        val a = value(PatchSampler.sample(f))
        val b = value(PatchSampler.sample(f))
        assertEquals(a.linear, b.linear)
        assertEquals(a.standardError, b.standardError)
    }
}
