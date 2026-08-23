package com.fotoxplorr.app.videoedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditPlanTest {

    private fun plan(duration: Long = 10_000L) = VideoEditPlan(sourceDurationMs = duration)

    // ── Identity ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh plan is identity and must not be savable`() {
        assertTrue(plan().isIdentity)
    }

    @Test
    fun `every single control breaks identity`() {
        assertFalse(plan().copy(trimStartMs = 1).isIdentity)
        assertFalse(plan().copy(trimEndMs = 9_999).isIdentity)
        assertFalse(plan().copy(quarterTurns = 1).isIdentity)
        assertFalse(plan().copy(flipHorizontal = true).isIdentity)
        assertFalse(plan().copy(speed = 2f).isIdentity)
        assertFalse(plan().copy(muted = true).isIdentity)
        assertFalse(plan().copy(cropAspect = CropAspect.SQUARE).isIdentity)
    }

    // ── Durations ────────────────────────────────────────────────────────────────────

    @Test
    fun `trimmed and exported durations follow trim and speed`() {
        val p = plan().copy(trimStartMs = 2_000, trimEndMs = 8_000, speed = 2f)
        assertEquals(6_000L, p.trimmedDurationMs)
        assertEquals(3_000L, p.exportedDurationMs)
    }

    @Test
    fun `slow motion lengthens the export`() {
        val p = plan().copy(speed = 0.5f)
        assertEquals(20_000L, p.exportedDurationMs)
    }

    // ── Invariants ───────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `trim end before trim start is refused`() {
        plan().copy(trimStartMs = 5_000, trimEndMs = 4_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trim beyond the video is refused`() {
        plan().copy(trimEndMs = 10_001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-length video is refused`() {
        VideoEditPlan(sourceDurationMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `speed outside the supported band is refused`() {
        plan().copy(speed = 10f)
    }

    @Test
    fun `rotation swaps dimensions only on odd quarter turns`() {
        assertFalse(plan().swapsDimensions)
        assertTrue(plan().copy(quarterTurns = 1).swapsDimensions)
        assertFalse(plan().copy(quarterTurns = 2).swapsDimensions)
        assertTrue(plan().copy(quarterTurns = 3).swapsDimensions)
    }

    // ── Crop geometry ────────────────────────────────────────────────────────────────

    @Test
    fun `square crop of a landscape frame trims the sides symmetrically`() {
        val crop = CropAspect.SQUARE.ndcCrop(1920, 1080, rotatedQuarterTurns = 0)
        assertNotNull(crop)
        // Keep 1080/1920 of the width, centred.
        assertEquals(-0.5625f, crop!!.left, 1e-4f)
        assertEquals(0.5625f, crop.right, 1e-4f)
        assertEquals(-1f, crop.bottom, 0f)
        assertEquals(1f, crop.top, 0f)
    }

    @Test
    fun `wide crop of a portrait frame trims top and bottom`() {
        val crop = CropAspect.WIDE.ndcCrop(1080, 1920, rotatedQuarterTurns = 0)
        assertNotNull(crop)
        assertEquals(-1f, crop!!.left, 0f)
        assertEquals(1f, crop.right, 0f)
        // Keep (1080/1920)/(16/9) = 0.3164 of the height.
        assertEquals(-0.31640625f, crop.bottom, 1e-4f)
        assertEquals(0.31640625f, crop.top, 1e-4f)
    }

    @Test
    fun `a crop matching the source aspect is a no-op`() {
        assertNull(CropAspect.WIDE.ndcCrop(1920, 1080, rotatedQuarterTurns = 0))
        assertNull(CropAspect.SQUARE.ndcCrop(1080, 1080, rotatedQuarterTurns = 0))
    }

    @Test
    fun `rotation is applied before the crop decides which sides to trim`() {
        // A landscape source turned 90 degrees presents as portrait: 16:9 must now trim
        // vertically, exactly as it would for a native portrait frame.
        val crop = CropAspect.WIDE.ndcCrop(1920, 1080, rotatedQuarterTurns = 1)
        assertNotNull(crop)
        assertEquals(-1f, crop!!.left, 0f)
        assertEquals(1f, crop.right, 0f)
        assertTrue(crop.top < 1f)
    }

    @Test
    fun `degenerate source dimensions yield no crop rather than a crash`() {
        assertNull(CropAspect.SQUARE.ndcCrop(0, 0, rotatedQuarterTurns = 0))
    }
}
