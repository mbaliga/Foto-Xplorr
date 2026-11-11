package com.fotoxplorr.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportOptionsTest {

    // ── Invariants ───────────────────────────────────────────────────────────────────

    @Test
    fun `default options are valid`() {
        VideoExportOptions()
    }

    @Test
    fun `an unsupported rotation is refused`() {
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(rotationDegrees = 45) }
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(rotationDegrees = -90) }
    }

    @Test
    fun `speed outside the supported band is refused`() {
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(speedFactor = 0.1f) }
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(speedFactor = 5f) }
    }

    @Test
    fun `a filter value outside the -1 to 1 range is refused`() {
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(brightness = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(contrast = -2f) }
        assertThrows(IllegalArgumentException::class.java) { VideoExportOptions(saturation = 2f) }
    }

    @Test
    fun `hasFilter is true only when a filter value is non-zero`() {
        assertTrue(VideoExportOptions().hasFilter.not())
        assertTrue(VideoExportOptions(brightness = 0.1f).hasFilter)
        assertTrue(VideoExportOptions(contrast = -0.1f).hasFilter)
        assertTrue(VideoExportOptions(saturation = 0.1f).hasFilter)
    }

    // ── Duration math ────────────────────────────────────────────────────────────────

    @Test
    fun `exportedDurationMs uses the source duration when trimEnd is null`() {
        val options = VideoExportOptions(trimStartMs = 1_000L, speedFactor = 2f)
        assertEquals(4_500L, options.exportedDurationMs(sourceDurationMs = 10_000L))
    }

    @Test
    fun `exportedDurationMs respects an explicit trim end`() {
        val options = VideoExportOptions(trimStartMs = 2_000L, trimEndMs = 8_000L, speedFactor = 2f)
        assertEquals(3_000L, options.exportedDurationMs(sourceDurationMs = 100_000L))
    }

    @Test
    fun `slow motion lengthens the export`() {
        val options = VideoExportOptions(speedFactor = 0.5f)
        assertEquals(20_000L, options.exportedDurationMs(sourceDurationMs = 10_000L))
    }

    // ── Quality presets ──────────────────────────────────────────────────────────────

    @Test
    fun `original quality leaves resolution and bitrate alone`() {
        assertNull(targetShortSideForQuality(VideoQuality.ORIGINAL))
        assertNull(targetBitrateForQuality(VideoQuality.ORIGINAL))
    }

    @Test
    fun `1080p and 720p presets have distinct, descending targets`() {
        val p1080 = targetShortSideForQuality(VideoQuality.P1080)!!
        val p720 = targetShortSideForQuality(VideoQuality.P720)!!
        assertTrue(p1080 > p720)
        assertTrue(targetBitrateForQuality(VideoQuality.P1080)!! > targetBitrateForQuality(VideoQuality.P720)!!)
    }

    // ── Crop geometry ────────────────────────────────────────────────────────────────

    @Test
    fun `square crop of a landscape frame trims the sides symmetrically`() {
        val crop = CropAspect.SQUARE.ndcCrop(1920, 1080, rotatedDegrees = 0)
        assertNotNull(crop)
        assertEquals(-0.5625f, crop!!.left, 1e-4f)
        assertEquals(0.5625f, crop.right, 1e-4f)
    }

    @Test
    fun `a crop matching the source aspect is a no-op`() {
        assertNull(CropAspect.WIDE.ndcCrop(1920, 1080, rotatedDegrees = 0))
    }

    @Test
    fun `rotation is applied before the crop decides which sides to trim`() {
        val crop = CropAspect.WIDE.ndcCrop(1920, 1080, rotatedDegrees = 90)
        assertNotNull(crop)
        assertTrue(crop!!.top < 1f)
    }

    @Test
    fun `degenerate source dimensions yield no crop rather than a crash`() {
        assertNull(CropAspect.SQUARE.ndcCrop(0, 0, rotatedDegrees = 0))
    }

    @Test
    fun `an inverted or out-of-range NdcCrop is refused`() {
        assertThrows(IllegalArgumentException::class.java) { NdcCrop(left = 0.5f, right = -0.5f, bottom = -1f, top = 1f) }
        assertThrows(IllegalArgumentException::class.java) { NdcCrop(left = -2f, right = 1f, bottom = -1f, top = 1f) }
    }
}
