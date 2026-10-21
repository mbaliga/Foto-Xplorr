package com.fotoxplorr.app.video

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure options-to-Transformer-request translation [VideoExporter] otherwise buries inside
 * Media3 `Composition`/`EditedMediaItem` construction. These pin the two conversions that are
 * easy to get backwards without a test: Media3's counter-clockwise rotation sign versus this
 * app's clockwise convention, and which codec/quality maps to which mime type and target.
 */
class ResolvedExportPlanTest {

    @Test
    fun `a clockwise 90 degree rotation becomes a 270 degree Media3 rotation`() {
        val plan = resolveExportPlan(
            VideoExportOptions(rotationDegrees = 90),
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceDurationMs = 10_000L,
        )
        assertEquals(270f, plan.media3RotationDegrees, 0f)
    }

    @Test
    fun `no rotation maps to zero Media3 degrees`() {
        val plan = resolveExportPlan(VideoExportOptions(), 1920, 1080, 10_000L)
        assertEquals(0f, plan.media3RotationDegrees, 0f)
    }

    @Test
    fun `mirror flips scaleX and nothing else`() {
        val mirrored = resolveExportPlan(VideoExportOptions(mirror = true), 1920, 1080, 10_000L)
        val plain = resolveExportPlan(VideoExportOptions(mirror = false), 1920, 1080, 10_000L)
        assertEquals(-1f, mirrored.scaleX, 0f)
        assertEquals(1f, plain.scaleX, 0f)
    }

    @Test
    fun `crop aspect is resolved against the rotated frame`() {
        val plan = resolveExportPlan(
            VideoExportOptions(rotationDegrees = 90, cropAspect = CropAspect.WIDE),
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceDurationMs = 10_000L,
        )
        assertNotNull(plan.crop)
    }

    @Test
    fun `no crop aspect resolves to a null crop`() {
        val plan = resolveExportPlan(VideoExportOptions(), 1920, 1080, 10_000L)
        assertNull(plan.crop)
    }

    @Test
    fun `codec choice selects the matching mime type`() {
        val h264 = resolveExportPlan(VideoExportOptions(codec = VideoCodec.H264), 1920, 1080, 10_000L)
        val hevc = resolveExportPlan(VideoExportOptions(codec = VideoCodec.HEVC), 1920, 1080, 10_000L)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, h264.videoMimeType)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, hevc.videoMimeType)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, h264.audioMimeType)
    }

    @Test
    fun `quality preset carries through to the resolved plan`() {
        val plan = resolveExportPlan(VideoExportOptions(quality = VideoQuality.P720), 1920, 1080, 10_000L)
        assertEquals(720, plan.targetShortSide)
        assertNotNull(plan.targetBitrate)
    }

    @Test
    fun `the resolved plan carries the same exported duration as the options`() {
        val options = VideoExportOptions(trimStartMs = 1_000L, trimEndMs = 5_000L, speedFactor = 2f)
        val plan = resolveExportPlan(options, 1920, 1080, 10_000L)
        assertEquals(options.exportedDurationMs(10_000L), plan.exportedDurationMs)
    }
}
