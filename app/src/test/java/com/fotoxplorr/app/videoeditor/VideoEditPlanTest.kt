package com.fotoxplorr.app.videoeditor

import androidx.compose.runtime.saveable.SaverScope
import com.fotoxplorr.app.video.CropAspect
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
        assertFalse(plan().copy(brightness = 0.2f).isIdentity)
        assertFalse(plan().copy(contrast = 0.2f).isIdentity)
        assertFalse(plan().copy(saturation = 0.2f).isIdentity)
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

    @Test(expected = IllegalArgumentException::class)
    fun `a filter value outside the -1 to 1 range is refused`() {
        plan().copy(contrast = 2f)
    }

    @Test
    fun `rotation swaps dimensions only on odd quarter turns`() {
        assertFalse(plan().swapsDimensions)
        assertTrue(plan().copy(quarterTurns = 1).swapsDimensions)
        assertFalse(plan().copy(quarterTurns = 2).swapsDimensions)
        assertTrue(plan().copy(quarterTurns = 3).swapsDimensions)
    }

    // ── Crop geometry (via com.fotoxplorr.app.video.CropAspect) ────────────────────────

    @Test
    fun `square crop of a landscape frame trims the sides symmetrically`() {
        val crop = CropAspect.SQUARE.ndcCrop(1920, 1080, rotatedDegrees = 0)
        assertNotNull(crop)
        // Keep 1080/1920 of the width, centred.
        assertEquals(-0.5625f, crop!!.left, 1e-4f)
        assertEquals(0.5625f, crop.right, 1e-4f)
        assertEquals(-1f, crop.bottom, 0f)
        assertEquals(1f, crop.top, 0f)
    }

    @Test
    fun `wide crop of a portrait frame trims top and bottom`() {
        val crop = CropAspect.WIDE.ndcCrop(1080, 1920, rotatedDegrees = 0)
        assertNotNull(crop)
        assertEquals(-1f, crop!!.left, 0f)
        assertEquals(1f, crop.right, 0f)
        // Keep (1080/1920)/(16/9) = 0.3164 of the height.
        assertEquals(-0.31640625f, crop.bottom, 1e-4f)
        assertEquals(0.31640625f, crop.top, 1e-4f)
    }

    @Test
    fun `a crop matching the source aspect is a no-op`() {
        assertNull(CropAspect.WIDE.ndcCrop(1920, 1080, rotatedDegrees = 0))
        assertNull(CropAspect.SQUARE.ndcCrop(1080, 1080, rotatedDegrees = 0))
    }

    @Test
    fun `rotation is applied before the crop decides which sides to trim`() {
        // A landscape source turned 90 degrees presents as portrait: 16:9 must now trim
        // vertically, exactly as it would for a native portrait frame.
        val crop = CropAspect.WIDE.ndcCrop(1920, 1080, rotatedDegrees = 90)
        assertNotNull(crop)
        assertEquals(-1f, crop!!.left, 0f)
        assertEquals(1f, crop.right, 0f)
        assertTrue(crop.top < 1f)
    }

    @Test
    fun `degenerate source dimensions yield no crop rather than a crash`() {
        assertNull(CropAspect.SQUARE.ndcCrop(0, 0, rotatedDegrees = 0))
    }

    // ── Export options mapping (pure options-to-Transformer-request translation) ───────

    @Test
    fun `toExportOptions maps quarter turns to clockwise degrees`() {
        val options = plan().copy(quarterTurns = 3).toExportOptions()
        assertEquals(270, options.rotationDegrees)
    }

    @Test
    fun `toExportOptions leaves trimEnd null when the trim reaches the source's own end`() {
        val options = plan().toExportOptions()
        assertNull(options.trimEndMs)
    }

    @Test
    fun `toExportOptions carries a real trim end through unchanged`() {
        val options = plan().copy(trimEndMs = 4_000).toExportOptions()
        assertEquals(4_000L, options.trimEndMs)
    }

    @Test
    fun `toExportOptions carries speed, mute, crop and filters through unchanged`() {
        val options = plan()
            .copy(speed = 2f, muted = true, cropAspect = CropAspect.TALL, brightness = 0.3f)
            .toExportOptions()
        assertEquals(2f, options.speedFactor, 0f)
        assertTrue(options.muted)
        assertEquals(CropAspect.TALL, options.cropAspect)
        assertEquals(0.3f, options.brightness, 0f)
    }

    // ── Rotation-survival Saver ──────────────────────────────────────────────────────

    private val permissiveSaverScope = SaverScope { true }

    private fun roundTrip(plan: VideoEditPlan): VideoEditPlan? {
        val saved = with(permissiveSaverScope) { with(VideoEditPlan.Saver) { save(plan) } }
        return saved?.let { VideoEditPlan.Saver.restore(it) }
    }

    @Test
    fun `the saver round-trips every field, including a null crop`() {
        val original = plan().copy(trimStartMs = 1_000, speed = 1.5f, saturation = -0.4f)
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `the saver round-trips a chosen crop aspect by name`() {
        val original = plan().copy(cropAspect = CropAspect.CLASSIC)
        assertEquals(CropAspect.CLASSIC, roundTrip(original)?.cropAspect)
    }
}
