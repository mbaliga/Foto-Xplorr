package com.fotoxplorr.app.animate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size and length the screen quotes before a file exists: the same timelines, planner and ceilings the job uses. */
class AnimateEstimateTest {
    private val photos = (1L..120L).map { AnimateKit.asset(it, width = 4000, height = 3000) }
    private val video = AnimateKit.video(500, width = 1920, height = 1080, durationMs = 20_000)
    private val gif = AnimateKit.asset(600, "party.gif", mime = "image/gif")
    private val library = AnimateLibrary(photos + video + gif)

    private fun ticked(n: Int): AnimateDraft = (1L..n).fold(AnimateDraft()) { d, id -> d.togglePhoto(id) }

    private fun clip(): AnimateDraft = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video)

    @Test
    fun `a short slideshow is drawn at the planner's edge with one frame a photo and the length it was given`() {
        val e = AnimateEstimator.of(ticked(5), library)!!
        assertEquals(480, e.width)
        assertEquals(360, e.height)
        assertEquals(5, e.frames)
        assertEquals(5_000L, e.playMs)
        assertTrue(e.bytes.lowBytes < e.bytes.typicalBytes && e.bytes.typicalBytes < e.bytes.highBytes)
        assertTrue(e.fitsBytes)
        assertEquals(listOf(PlanStep.SHRUNK), e.steps)
        assertFalse(e.largeGif)
    }

    @Test
    fun `it agrees with the planner fed the timeline the job builds`() {
        val d = ticked(4).select(1).holdBy(500).speedBy(2)
        val e = AnimateEstimator.of(d, library)!!
        val delays = StillsTimeline.build(IntArray(4) { d.effectiveHoldMs(d.photos[it]) }, null).delaysMs
        val plan = AnimPlanner.plan(PlanRequest(4000, 3000, delays, AnimFormat.GIF, ContentKind.PHOTO, 1.0, null, AnimateDefaults.limits(AnimOutput.GIF)))
        assertEquals(plan.estimate.typicalBytes, e.bytes.typicalBytes)
        assertEquals(plan.playMs, e.playMs)
    }

    @Test
    fun `more photos and longer times make a bigger guess`() {
        val few = AnimateEstimator.of(ticked(3), library)!!
        val many = AnimateEstimator.of(ticked(12), library)!!
        assertTrue(many.bytes.typicalBytes > few.bytes.typicalBytes)
        assertTrue(many.playMs > few.playMs)
    }

    @Test
    fun `a fade adds the in-between frames and loops back with the repeat`() {
        val plain = AnimateEstimator.of(ticked(4), library)!!
        val fade = AnimateEstimator.of(ticked(4).withFade(true), library)!!
        // Three gaps, four frames each, and with the repeat the last fades back to the first.
        assertEquals(plain.frames + 4 * 4, fade.frames)
        val once = AnimateEstimator.of(ticked(4).withFade(true).withLoop(false), library)!!
        assertEquals(plain.frames + 3 * 4, once.frames)
        assertTrue(fade.bytes.typicalBytes > plain.bytes.typicalBytes)
    }

    @Test
    fun `a long GIF is called large, a short one is not, and an APNG never is`() {
        val long = ticked(100).withFade(true)
        val big = AnimateEstimator.of(long, library)!!
        assertTrue("typical ${big.bytes.typicalBytes}", big.bytes.typicalBytes >= AnimateEstimator.LARGE_GIF_BYTES || !big.fitsBytes)
        assertTrue(big.largeGif)
        assertFalse(AnimateEstimator.of(ticked(3), library)!!.largeGif)
        assertFalse(AnimateEstimator.of(long.withOutput(AnimOutput.APNG), library)!!.largeGif)
    }

    @Test
    fun `the planner's steps are passed on so the screen can say what was given up`() {
        val big = AnimateEstimator.of(ticked(100).withFade(true), library)!!
        assertTrue(big.steps.isNotEmpty())
        assertTrue(PlanStep.SHRUNK in big.steps)
    }

    @Test
    fun `a video is guessed from its bitrate and length at even sizes, and is not thinned for size`() {
        val d = ticked(6).withOutput(AnimOutput.VIDEO)
        val e = AnimateEstimator.of(d, library)!!
        // Photos are 4000x3000 and the video ceiling is 720.
        assertEquals(720, e.width)
        assertEquals(540, e.height)
        assertEquals(6, e.frames)
        val expected = VideoTimeline.bitrate(720, 540, VideoTimeline.DEFAULT_FPS) / 8.0 * 6.0
        assertEquals(expected, e.bytes.typicalBytes.toDouble(), 1.0)
        assertTrue(e.bytes.lowBytes < e.bytes.typicalBytes && e.bytes.typicalBytes < e.bytes.highBytes)
        assertFalse(e.largeGif)
        // The same slideshow as a GIF is drawn smaller (480) but as a video the cap is 720.
        assertEquals(480, AnimateEstimator.of(ticked(6), library)!!.width)
    }

    @Test
    fun `a video made from a clip uses the clip's rate`() {
        val d = clip().withOutput(AnimOutput.VIDEO).fpsBy(3)
        val e = AnimateEstimator.of(d, library)!!
        val fps = 15
        val (w, h) = VideoTimeline.evenSize(e.width, e.height)
        assertEquals(w, e.width)
        assertEquals(h, e.height)
        val expected = VideoTimeline.bitrate(e.width, e.height, fps) / 8.0 * (e.playMs / 1000.0)
        assertEquals(expected, e.bytes.typicalBytes.toDouble(), 1.0)
    }

    @Test
    fun `a video part is sized from the video and clamped to its length`() {
        val e = AnimateEstimator.of(clip(), library)!!
        assertEquals(480, e.width)
        assertEquals(270, e.height)
        // Five seconds at 12 a second.
        assertEquals(60, e.frames)
        assertEquals(5_000.0, e.playMs.toDouble(), 100.0)
        val long = clip().endBy(1_000_000, 20_000)
        assertEquals(20_000L, long.clipEndMs)
        val planned = AnimateEstimator.of(long, library)!!
        // 20 s at 12 a second is 240 ticks; the planner holds it to 120 frames, keeping the length.
        assertTrue(planned.frames <= 120)
        assertTrue(planned.steps.contains(PlanStep.FRAMES_THINNED))
        assertEquals(20_000.0, planned.playMs.toDouble(), 400.0)
    }

    @Test
    fun `an existing file or an incomplete draft has no estimate`() {
        assertNull(AnimateEstimator.of(AnimateDraft().withSource(AnimateSource.FILE).pickFile(gif), library))
        assertNull(AnimateEstimator.of(AnimateDraft(), library))
        assertNull(AnimateEstimator.of(ticked(1), library))
        assertNull(AnimateEstimator.of(AnimateDraft().withSource(AnimateSource.CLIP), library))
    }

    @Test
    fun `a photo with no size in the library still gets an estimate`() {
        val blank = AnimateLibrary(listOf(AnimateKit.asset(1, width = 0, height = 0), AnimateKit.asset(2, width = 0, height = 0)))
        val e = AnimateEstimator.of(ticked(2), blank)
        assertNotNull(e)
        assertTrue(e!!.width > 0 && e.height > 0)
    }

    @Test
    fun `frames of a part are counted from its length and rate`() {
        assertEquals(60, AnimateEstimator.clipFrames(5_000, 12))
        assertEquals(1, AnimateEstimator.clipFrames(100, 1))
        assertTrue(AnimateEstimator.clipFrames(60_000, 30) >= 1_000)
    }
}
