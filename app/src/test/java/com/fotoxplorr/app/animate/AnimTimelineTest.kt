package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions of the animation job: timelines, the way down when a plan does not fit, and small pixel helpers. */
class AnimTimelineTest {

    // ---- a slideshow's timeline -------------------------------------------------------------------

    @Test
    fun `a slideshow of cuts has one entry per still and its holds`() {
        val t = StillsTimeline.build(intArrayOf(1000, 2000, 500), null)
        assertEquals(3, t.steps.size)
        assertArrayEquals(intArrayOf(1000, 2000, 500), t.delaysMs)
        assertTrue(t.steps.all { it.to == -1 })
        assertEquals(listOf(0, 1, 2), t.steps.map { it.from })
    }

    @Test
    fun `a transition adds its in-betweens after every still but the last`() {
        val t = StillsTimeline.build(intArrayOf(1000, 1000, 1000), StillTransition(frames = 3, frameMs = 50))
        assertEquals("3 holds and 2 gaps of 3", 3 + 2 * 3, t.steps.size)
        val between = t.steps.filter { it.to >= 0 }
        assertEquals(6, between.size)
        assertTrue("every in-between lasts the frame time", t.delaysMs.indices.all { i -> t.steps[i].to < 0 || t.delaysMs[i] == 50 })
        // Between still 0 and still 1 the mix rises and stays inside 0..1.
        val first = between.take(3).map { it.mix }
        assertTrue(first.zipWithNext().all { (a, b) -> b > a })
        assertTrue(first.all { it > 0.0 && it < 1.0 })
        assertEquals(listOf(0, 0, 0, 1, 1, 1), between.map { it.from })
        assertEquals(listOf(1, 1, 1, 2, 2, 2), between.map { it.to })
    }

    @Test
    fun `looping adds a gap from the last still back to the first`() {
        val t = StillsTimeline.build(intArrayOf(500, 500), StillTransition(frames = 2, loop = true))
        assertEquals(2 + 2 * 2, t.steps.size)
        val back = t.steps.filter { it.from == 1 && it.to == 0 }
        assertEquals(2, back.size)
    }

    @Test
    fun `one still never fades into itself, loop or not`() {
        val t = StillsTimeline.build(intArrayOf(500), StillTransition(frames = 4, loop = true))
        assertEquals(1, t.steps.size)
        assertArrayEquals(intArrayOf(500), t.delaysMs)
    }

    @Test
    fun `a transition of zero frames is the same as none`() {
        val t = StillsTimeline.build(intArrayOf(500, 600), StillTransition(frames = 0))
        assertEquals(2, t.steps.size)
    }

    @Test
    fun `the mixes are the eased fractions the cross-fade uses`() {
        val easing = Easing.EASE_IN_OUT
        val t = StillsTimeline.build(intArrayOf(100, 100), StillTransition(frames = 3, easing = easing))
        val mixes = t.steps.filter { it.to >= 0 }.map { it.mix }
        assertEquals(listOf(1, 2, 3).map { easing.apply(it / 4.0) }, mixes)
    }

    // ---- a clip's ticks ------------------------------------------------------------------------------

    @Test
    fun `a clip at 10 fps is ticks of 100 ms read at the start of each`() {
        val c = ClipTimeline.build(2000, 3000, 10.0)
        assertEquals(10, c.delaysMs.size)
        assertTrue(c.delaysMs.all { it == 100 })
        assertEquals(2000L, c.timeMs(0))
        assertEquals(2100L, c.timeMs(1))
        assertEquals(2900L, c.timeMs(9))
    }

    @Test
    fun `a clip at 15 fps keeps its real length with ticks of 67 and 66 ms`() {
        val c = ClipTimeline.build(0, 1000, 15.0)
        assertEquals(15, c.delaysMs.size)
        assertEquals(1000, c.delaysMs.sum())
        assertTrue(c.delaysMs.all { it == 66 || it == 67 })
        assertEquals("a tick starts where the one before ended", c.delaysMs.take(5).sum().toLong(), c.timeMs(5))
    }

    @Test
    fun `a very long clip is cut at the tick limit`() {
        val c = ClipTimeline.build(0, 10_000_000, 60.0)
        assertEquals(ClipTimeline.MAX_TICKS, c.delaysMs.size)
    }

    // ---- a video's ticks ------------------------------------------------------------------------------

    @Test
    fun `ticks add up to the real length within one tick, whatever the delays round to`() {
        val delays = IntArray(40) { 33 + (it % 3) }
        val ticks = VideoTimeline.ticks(delays, 30)
        val seconds = delays.sum() / 1000.0
        assertEquals(seconds * 30, ticks.sum().toDouble(), 1.0)
        assertTrue(ticks.all { it >= 1 })
    }

    @Test
    fun `a delay shorter than a tick still makes a frame`() {
        val ticks = VideoTimeline.ticks(intArrayOf(5, 5, 5, 200), 30)
        assertTrue(ticks.all { it >= 1 })
        assertEquals(1, ticks[0])
    }

    @Test
    fun `a 2 second still at 30 fps is 60 ticks, and its duration is rounded up so no frame is lost`() {
        assertArrayEquals(intArrayOf(60), VideoTimeline.ticks(intArrayOf(2000), 30))
        assertEquals(33_334L, VideoTimeline.durationUs(1, 30))
        assertEquals(2_000_000L, VideoTimeline.durationUs(60, 30))
        // The frame count Transformer derives: duration x rate, rounded, is the tick count.
        for (fps in listOf(1, 7, 12, 24, 30, 60)) for (ticks in listOf(1, 2, 3, 17, 90)) {
            val derived = Math.round(VideoTimeline.durationUs(ticks, fps) / 1_000_000.0 * fps)
            assertEquals("ticks $ticks at $fps fps", ticks.toLong(), derived)
        }
    }

    @Test
    fun `the video rate is a whole number in range`() {
        assertEquals(30, VideoTimeline.fpsFor(null))
        assertEquals(12, VideoTimeline.fpsFor(12.2))
        assertEquals(13, VideoTimeline.fpsFor(12.6))
        assertEquals(1, VideoTimeline.fpsFor(0.2))
        assertEquals(60, VideoTimeline.fpsFor(240.0))
    }

    @Test
    fun `video sizes are made even, rounding down`() {
        assertEquals(480 to 360, VideoTimeline.evenSize(480, 360))
        assertEquals(480 to 358, VideoTimeline.evenSize(481, 359))
        assertEquals(2 to 2, VideoTimeline.evenSize(1, 1))
    }

    @Test
    fun `the bitrate follows the pixels and the rate, inside its floor and ceiling`() {
        assertEquals(400_000, VideoTimeline.bitrate(100, 100, 10))
        assertEquals(20_000_000, VideoTimeline.bitrate(7680, 4320, 60))
        val a = VideoTimeline.bitrate(1280, 720, 30)
        val b = VideoTimeline.bitrate(640, 360, 30)
        assertTrue(a > b)
    }

    // ---- the way down ----------------------------------------------------------------------------------

    private fun planOf(w: Int, h: Int, frames: Int, limits: PlanLimits = PlanLimits()): AnimPlan = AnimPlanner.plan(
        PlanRequest(w, h, IntArray(frames) { 100 }, limits = limits),
    )

    @Test
    fun `a memory failure shrinks the picture and thins the frames`() {
        val limits = PlanLimits(maxLongEdge = 480, maxFrames = 100)
        val plan = planOf(480, 360, 100, PlanLimits(maxLongEdge = 480, maxFrames = 100, maxBytes = Long.MAX_VALUE / 4, maxDurationMs = 60_000))
        val next = AnimLadder.next(limits, plan, null, 0)
        assertNotNull(next)
        assertTrue(next!!.maxLongEdge < 480)
        assertTrue(next.maxFrames < 100)
        assertEquals(480 * 8 / 10, next.maxLongEdge)
        assertEquals(75, next.maxFrames)
    }

    @Test
    fun `a size failure at the first rung shrinks the picture only, by about the root of how far over it was`() {
        val limits = PlanLimits(maxLongEdge = 480, maxBytes = 1_000_000)
        val plan = planOf(480, 360, 20, limits)
        val next = AnimLadder.next(limits, plan, 4_000_000, 0)!!
        // sqrt(1/4) = 0.5, with a margin, kept within the best shrink of one half.
        assertEquals(240, next.maxLongEdge)
        assertEquals("frames are kept at what the plan used, at the first rung", plan.picks.size, next.maxFrames)
    }

    @Test
    fun `a small overshoot takes a small step`() {
        val limits = PlanLimits(maxLongEdge = 480, maxBytes = 1_000_000)
        val plan = planOf(480, 360, 20, limits)
        val next = AnimLadder.next(limits, plan, 1_050_000, 0)!!
        assertEquals("never less than the 0.8 step", 480 * 8 / 10, next.maxLongEdge)
    }

    @Test
    fun `from the second rung on a size failure thins the frames too`() {
        val limits = PlanLimits(maxLongEdge = 400, maxFrames = 60, maxBytes = 1_000_000)
        val plan = planOf(400, 300, 60, limits)
        val next = AnimLadder.next(limits, plan, 2_000_000, 1)!!
        assertTrue(next.maxFrames < plan.picks.size)
        assertTrue(next.maxLongEdge < 400)
    }

    @Test
    fun `the way down always ends and every rung is smaller than the one before`() {
        var limits: PlanLimits? = PlanLimits(maxLongEdge = 1080, maxFrames = 400, maxBytes = 1_000_000, maxDurationMs = 120_000)
        var last = Long.MAX_VALUE
        var rungs = 0
        while (limits != null && rungs < 100) {
            val plan = planOf(1920, 1080, 400, limits)
            val size = plan.width.toLong() * plan.height * plan.picks.size
            assertTrue("rung $rungs: $size must be below $last", size < last)
            last = size
            limits = AnimLadder.next(limits, plan, if (rungs % 2 == 0) null else 5_000_000L, rungs)
            rungs++
        }
        assertNull("the ladder ran out", limits)
        assertTrue("and in a sensible number of rungs: $rungs", rungs in 3..40)
    }

    @Test
    fun `a plan that cannot get smaller has no next rung`() {
        val limits = PlanLimits(maxLongEdge = AnimLadder.MIN_EDGE, maxFrames = 2)
        val plan = planOf(64, 48, 2, limits)
        assertNull(AnimLadder.next(limits, plan, null, 0))
        assertNull(AnimLadder.next(limits, plan, 99_999_999, 3))
    }

    // ---- small choices --------------------------------------------------------------------------------

    @Test
    fun `the palette sample spreads over the animation, first and last included, and is never more than asked`() {
        assertArrayEquals(intArrayOf(0, 1, 2), AnimChoices.paletteSample(3))
        val s = AnimChoices.paletteSample(100)
        assertEquals(8, s.size)
        assertEquals(0, s.first())
        assertEquals(99, s.last())
        assertTrue(s.toList().zipWithNext().all { (a, b) -> b > a })
        assertArrayEquals(intArrayOf(0), AnimChoices.paletteSample(1))
    }

    @Test
    fun `a still is read at the canvas edge, doubled for cover, widened for a crop, and never past the ceiling`() {
        assertEquals(480, AnimChoices.stillEdge(480, 360, FitMode.CONTAIN))
        assertEquals(480, AnimChoices.stillEdge(480, 360, FitMode.STRETCH))
        assertEquals(960, AnimChoices.stillEdge(480, 360, FitMode.COVER))
        assertEquals(960, AnimChoices.stillEdge(480, 360, FitMode.CONTAIN, cropFraction = 0.5))
        assertEquals(AnimChoices.CEILING, AnimChoices.stillEdge(1500, 1000, FitMode.COVER, cropFraction = 0.5))
        assertEquals("a crop fraction of nothing is not a division by zero", AnimChoices.CEILING, AnimChoices.stillEdge(480, 360, FitMode.CONTAIN, cropFraction = 0.0))
    }

    @Test
    fun `an APNG fits when its frames take under a third of the heap`() {
        // 100 frames of 480 x 360 x 4 bytes = 69 MB.
        assertTrue(AnimChoices.sequenceFits(100, 480, 360, 512L * 1024 * 1024))
        assertFalse(AnimChoices.sequenceFits(100, 480, 360, 128L * 1024 * 1024))
        assertFalse(AnimChoices.sequenceFits(1, 4000, 4000, 100L * 1024 * 1024))
    }

    // ---- pixel helpers ----------------------------------------------------------------------------------

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `flattening draws clear pixels as the background, soft ones mixed, opaque ones untouched`() {
        val px = intArrayOf(0, argb(255, 10, 20, 30), argb(128, 255, 0, 0), argb(0, 99, 99, 99))
        FrameOps.flatten(px, argb(255, 0, 0, 255))
        assertEquals(argb(255, 0, 0, 255), px[0])
        assertEquals(argb(255, 10, 20, 30), px[1])
        assertEquals("half red over blue", 255, px[2] ushr 24)
        val r = (px[2] shr 16) and 255
        val b = px[2] and 255
        assertTrue("red $r", r in 126..130)
        assertTrue("blue $b", b in 125..129)
        assertEquals(argb(255, 0, 0, 255), px[3])
    }

    @Test
    fun `the background's own alpha byte is ignored`() {
        val px = intArrayOf(0)
        FrameOps.flatten(px, 0x00123456)
        assertEquals(0xFF123456.toInt(), px[0])
    }

    private fun grid3x2() = RawFrame(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))

    @Test
    fun `a quarter turn clockwise puts the left column on the top row`() {
        val r = FrameOps.rotateClockwise(grid3x2(), 90)
        assertEquals(2, r.width)
        assertEquals(3, r.height)
        // 1 2 3        4 1
        // 4 5 6   ->   5 2
        //              6 3
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), r.argb)
    }

    @Test
    fun `a half turn and a three quarter turn`() {
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), FrameOps.rotateClockwise(grid3x2(), 180).argb)
        val r = FrameOps.rotateClockwise(grid3x2(), 270)
        assertEquals(2, r.width)
        assertArrayEquals(intArrayOf(3, 6, 2, 5, 1, 4), r.argb)
    }

    @Test
    fun `four quarter turns, and a turn then its opposite, give the picture back`() {
        var f = grid3x2()
        repeat(4) { f = FrameOps.rotateClockwise(f, 90) }
        assertArrayEquals(grid3x2().argb, f.argb)
        val there = FrameOps.rotateClockwise(grid3x2(), 90)
        assertArrayEquals(grid3x2().argb, FrameOps.rotateClockwise(there, 270).argb)
        assertTrue("no turn is the same object", FrameOps.rotateClockwise(grid3x2(), 0).argb.contentEquals(grid3x2().argb))
        assertArrayEquals(grid3x2().argb, FrameOps.rotateClockwise(grid3x2(), 360).argb)
        assertArrayEquals(FrameOps.rotateClockwise(grid3x2(), 270).argb, FrameOps.rotateClockwise(grid3x2(), -90).argb)
    }

    @Test
    fun `transparency is the least that describes the pixels`() {
        assertEquals(Transparency.NONE, FrameOps.transparencyOf(intArrayOf(argb(255, 1, 2, 3))))
        assertEquals(Transparency.PARTIAL, FrameOps.transparencyOf(intArrayOf(argb(255, 1, 2, 3), argb(200, 1, 2, 3))))
        assertEquals(Transparency.CLEAR, FrameOps.transparencyOf(intArrayOf(argb(255, 1, 2, 3), argb(127, 1, 2, 3))))
        assertEquals(Transparency.CLEAR, FrameOps.transparencyOf(intArrayOf(0)))
    }

    @Test
    fun `placing copies a picture of the right size and letterboxes one of another shape`() {
        val wide = AnimateKit.solid(40, 20, argb(255, 200, 0, 0))
        val same = FrameOps.place(wide, 40, 20, FitMode.CONTAIN, argb(255, 0, 0, 0))
        assertTrue(same.all { it == argb(255, 200, 0, 0) })
        assertTrue("a copy, not the picture's own array", same !== wide.argb)
        // Onto a square canvas: bars above and below.
        val boxed = FrameOps.place(wide, 40, 40, FitMode.CONTAIN, argb(255, 0, 0, 255))
        assertEquals(argb(255, 0, 0, 255), boxed[0])
        assertEquals(argb(255, 200, 0, 0), boxed[20 * 40 + 20])
        assertEquals(argb(255, 0, 0, 255), boxed[39 * 40 + 20])
        // Cover fills the canvas with the picture.
        val covered = FrameOps.place(wide, 40, 40, FitMode.COVER, argb(255, 0, 0, 255))
        assertTrue(covered.all { it == argb(255, 200, 0, 0) })
    }

    @Test
    fun `a size a pixel off in rounding counts as the same shape, a real difference does not`() {
        assertTrue(FrameOps.aspectAlmostEqual(640, 480, 480, 360))
        assertTrue(FrameOps.aspectAlmostEqual(640, 480, 481, 360))
        assertTrue(FrameOps.aspectAlmostEqual(1000, 667, 480, 320))
        assertFalse(FrameOps.aspectAlmostEqual(640, 480, 480, 480))
        assertFalse(FrameOps.aspectAlmostEqual(640, 480, 640, 360))
    }
}
