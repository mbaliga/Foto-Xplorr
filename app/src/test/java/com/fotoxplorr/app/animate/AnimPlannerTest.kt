package com.fotoxplorr.app.animate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The size and duration planner. */
class AnimPlannerTest {

    private fun steady(n: Int, ms: Int) = IntArray(n) { ms }

    private fun req(
        w: Int = 1920,
        h: Int = 1080,
        delays: IntArray = steady(100, 100),
        format: AnimFormat = AnimFormat.GIF,
        kind: ContentKind = ContentKind.PHOTO,
        change: Double = 0.5,
        fps: Double? = null,
        limits: PlanLimits = PlanLimits(),
    ) = PlanRequest(w, h, delays, format, kind, change, fps, limits)

    private fun check(plan: AnimPlan, sourceFrames: Int) {
        assertEquals(plan.picks.size, plan.delaysMs.size)
        assertTrue(plan.picks.isNotEmpty())
        for (i in plan.picks.indices) {
            assertTrue(plan.picks[i] in 0 until sourceFrames)
            if (i > 0) assertTrue("picks rise: ${plan.picks.toList()}", plan.picks[i] > plan.picks[i - 1])
            assertTrue("delay ${plan.delaysMs[i]}", plan.delaysMs[i] >= 0)
        }
    }

    @Test
    fun `the long edge is capped, the shape kept, and nothing is enlarged`() {
        val p = AnimPlanner.plan(req(1920, 1080, limits = PlanLimits(maxLongEdge = 480, maxBytes = Long.MAX_VALUE)))
        assertEquals(480, p.width)
        assertEquals(270, p.height)
        assertEquals(listOf(PlanStep.SHRUNK), p.steps)
        val small = AnimPlanner.plan(req(200, 100, limits = PlanLimits(maxLongEdge = 480, maxBytes = Long.MAX_VALUE)))
        assertEquals(200, small.width)
        assertEquals(100, small.height)
        assertTrue(small.steps.isEmpty())
        val tall = AnimPlanner.plan(req(1080, 1920, limits = PlanLimits(maxLongEdge = 480, maxBytes = Long.MAX_VALUE)))
        assertEquals(270, tall.width)
        assertEquals(480, tall.height)
    }

    @Test
    fun `a long source is cut at the length cap, in whole frames with the last one shortened`() {
        val d = steady(600, 100) // 60 s
        val p = AnimPlanner.plan(req(320, 240, d, limits = PlanLimits(maxDurationMs = 30_000, maxFrames = 10_000, maxBytes = Long.MAX_VALUE)))
        check(p, 600)
        assertTrue(PlanStep.TRIMMED in p.steps)
        assertEquals(30_000, p.delaysMs.sum())
        assertEquals(300, p.picks.size)
        // A cut that falls inside a frame shortens that frame.
        val odd = AnimPlanner.plan(req(320, 240, steady(10, 1000), limits = PlanLimits(maxDurationMs = 3_500, maxBytes = Long.MAX_VALUE)))
        assertEquals(listOf(1000, 1000, 1000, 500), odd.delaysMs.toList())
    }

    @Test
    fun `a target rate lowers the frame count and keeps the length`() {
        val d = steady(300, 33).also { it[299] = 34 } // 30 fps, 10 s
        val p = AnimPlanner.plan(req(320, 240, d, fps = 10.0, limits = PlanLimits(maxFrames = 1000, maxBytes = Long.MAX_VALUE)))
        check(p, 300)
        assertTrue(PlanStep.RATE_LOWERED in p.steps)
        assertEquals(99, p.picks.size) // 9.901 s at 10 fps is 99 ticks
        assertEquals(d.sum().toLong(), p.delaysMs.sumOf { it.toLong() })
        // A target above the source rate is not a reduction.
        val up = AnimPlanner.plan(req(320, 240, d, fps = 60.0, limits = PlanLimits(maxFrames = 1000, maxBytes = Long.MAX_VALUE)))
        assertFalse(PlanStep.RATE_LOWERED in up.steps)
        assertEquals(300, up.picks.size)
    }

    @Test
    fun `too many frames are thinned evenly and the length is kept`() {
        val d = steady(1000, 20) // 20 s of 50 fps
        val p = AnimPlanner.plan(req(100, 100, d, limits = PlanLimits(maxFrames = 100, maxBytes = Long.MAX_VALUE, maxDurationMs = 60_000)))
        check(p, 1000)
        assertEquals(100, p.picks.size)
        assertTrue(PlanStep.FRAMES_THINNED in p.steps)
        assertEquals(0, p.picks.first())
        assertEquals(20_000, p.delaysMs.sum())
        // Spread evenly: the gaps between picks differ by at most 1.
        val gaps = (1 until p.picks.size).map { p.picks[it] - p.picks[it - 1] }
        assertTrue("gaps ${gaps.distinct()}", gaps.max() - gaps.min() <= 1)
    }

    /** A slideshow of [photos] with a 4-frame fade, as the plan request a job makes for it. */
    private fun slideshow(photos: Int, holdMs: Int, limits: PlanLimits, loop: Boolean = false): Pair<AnimPlan, List<Int>> {
        val t = StillsTimeline.build(IntArray(photos) { holdMs }, StillTransition(4, 60, loop = loop))
        val holds = t.steps.indices.filter { t.steps[it].to < 0 }
        val keep = BooleanArray(t.steps.size) { t.steps[it].to < 0 }
        val plan = AnimPlanner.plan(PlanRequest(320, 240, t.delaysMs, limits = limits, keepFrames = keep))
        return plan to holds
    }

    @Test
    fun `thinning a fading slideshow keeps every photo's hold and drops in-betweens first`() {
        for (photos in listOf(8, 10, 12, 40)) {
            val cap = if (photos == 40) 120 else 24
            val (p, holds) = slideshow(photos, 1000, PlanLimits(maxFrames = cap, maxBytes = Long.MAX_VALUE, maxDurationMs = 600_000))
            check(p, 5 * photos)
            assertEquals("photos=$photos", cap, p.picks.size)
            assertTrue("photos=$photos holds ${holds} picks ${p.picks.toList()}", holds.all { it in p.picks.toList() })
            assertTrue(PlanStep.FRAMES_THINNED in p.steps)
            assertEquals("the length is kept", 1000 * photos + 4 * 60 * (photos - 1), p.delaysMs.sum())
        }
    }

    @Test
    fun `with fewer frames than photos only holds are left, spread over the show, both ends in`() {
        val (p, holds) = slideshow(30, 500, PlanLimits(maxFrames = 24, maxBytes = Long.MAX_VALUE, maxDurationMs = 600_000))
        check(p, 150)
        assertEquals(24, p.picks.size)
        assertTrue("only holds: ${p.picks.toList()}", p.picks.all { it in holds })
        assertEquals(holds.first(), p.picks.first())
        assertEquals(holds.last(), p.picks.last())
    }

    @Test
    fun `a size cap that thins further still drops in-betweens before a hold`() {
        val (p, holds) = slideshow(10, 1000, PlanLimits(maxLongEdge = 320, maxFrames = 500, maxBytes = 900_000, maxDurationMs = 600_000))
        check(p, 50)
        assertTrue("fits=${p.fitsBytes}, frames ${p.picks.size}", p.picks.size < 50)
        val kept = p.picks.toList()
        // A hold goes only when no in-between is left to go.
        assertTrue("holds ${holds} kept $kept", kept.size < holds.size || holds.all { it in kept })
    }

    @Test
    fun `no marked frames means the plain even spread`() {
        val d = steady(100, 100)
        val plain = AnimPlanner.plan(req(100, 100, d, limits = PlanLimits(maxFrames = 20, maxBytes = Long.MAX_VALUE)))
        val marked = AnimPlanner.plan(PlanRequest(100, 100, d, limits = PlanLimits(maxFrames = 20, maxBytes = Long.MAX_VALUE), keepFrames = BooleanArray(100)))
        assertEquals(plain.picks.toList(), marked.picks.toList())
    }

    @Test
    fun `a size cap is met by dropping frames first, then pixels`() {
        val d = steady(120, 100) // 12 s at 10 fps
        val cap = 1_500_000L
        val p = AnimPlanner.plan(req(1280, 720, d, limits = PlanLimits(maxLongEdge = 640, maxBytes = cap, maxFrames = 500)))
        check(p, 120)
        assertTrue("fits=${p.fitsBytes} est=${p.estimate.typicalBytes}", p.fitsBytes)
        assertTrue(p.estimate.typicalBytes <= cap)
        assertFalse(PlanStep.STILL_TOO_BIG in p.steps)
        // The order of the steps follows the order of giving things up.
        val order = p.steps
        assertTrue(order.indexOf(PlanStep.SHRUNK) < order.indexOf(PlanStep.FRAMES_THINNED) || PlanStep.SHRUNK_FOR_SIZE !in order)
    }

    @Test
    fun `the rate floor is kept while shrinking can still help`() {
        // 20 s, 5 fps floor: 100 frames minimum. A cap that 100 frames of a big picture cannot meet shrinks the picture, not the frames.
        val d = steady(200, 100)
        val lim = PlanLimits(maxLongEdge = 1000, maxBytes = 2_000_000, minFps = 5.0, minLongEdge = 100, maxDurationMs = 60_000)
        val p = AnimPlanner.plan(req(2000, 1000, d, limits = lim))
        check(p, 200)
        assertTrue(p.fitsBytes)
        assertTrue("frames=${p.picks.size}", p.picks.size >= 100)
        assertTrue(PlanStep.SHRUNK_FOR_SIZE in p.steps)
        assertTrue(maxOf(p.width, p.height) >= 100)
    }

    @Test
    fun `an impossible cap gives the smallest plan and says it does not fit`() {
        val p = AnimPlanner.plan(req(1920, 1080, steady(50, 100), limits = PlanLimits(maxBytes = 1_000)))
        check(p, 50)
        assertFalse(p.fitsBytes)
        assertTrue(PlanStep.STILL_TOO_BIG in p.steps)
        assertTrue(maxOf(p.width, p.height) <= 120 + 1)
        assertEquals(2, p.picks.size)
    }

    @Test
    fun `a single frame source is a single frame plan`() {
        val p = AnimPlanner.plan(req(500, 400, intArrayOf(1000), limits = PlanLimits(maxBytes = Long.MAX_VALUE)))
        assertEquals(1, p.picks.size)
        assertEquals(1000, p.delaysMs[0])
    }

    @Test
    fun `the playing length of a GIF plan is what the rounded delays add up to`() {
        val d = steady(60, 67)
        val p = AnimPlanner.plan(req(320, 240, d, limits = PlanLimits(maxBytes = Long.MAX_VALUE, maxFrames = 1000)))
        assertEquals(FrameTiming.gifDelaysCs(p.delaysMs).sum() * 10L, p.playMs)
        assertEquals(4020.0, p.playMs.toDouble(), 10.0)
        val apng = AnimPlanner.plan(req(320, 240, steady(10, 5), format = AnimFormat.APNG, limits = PlanLimits(maxBytes = Long.MAX_VALUE)))
        assertEquals(10 * ApngEncoder.MIN_DELAY_MS.toLong(), apng.playMs)
    }

    // ---- the estimate ----

    @Test
    fun `the estimate grows with frames, pixels and change, and its range brackets it`() {
        fun e(w: Int = 400, h: Int = 300, n: Int = 20, c: Double = 0.5, f: AnimFormat = AnimFormat.GIF, k: ContentKind = ContentKind.PHOTO) =
            AnimPlanner.estimate(f, k, w, h, n, c)
        val base = e()
        assertTrue(e(n = 40).typicalBytes > base.typicalBytes)
        assertTrue(e(w = 800).typicalBytes > base.typicalBytes)
        assertTrue(e(c = 0.9).typicalBytes > base.typicalBytes)
        assertTrue(e(f = AnimFormat.APNG).typicalBytes > base.typicalBytes)
        assertTrue(e(k = ContentKind.GRAPHICS).typicalBytes < base.typicalBytes)
        for (est in listOf(base, e(k = ContentKind.GRAPHICS), e(f = AnimFormat.APNG))) {
            assertTrue(est.lowBytes < est.typicalBytes && est.typicalBytes < est.highBytes)
        }
        // One frame: no deltas, so change does not matter.
        assertEquals(e(n = 1, c = 0.0).typicalBytes, e(n = 1, c = 1.0).typicalBytes)
    }

    @Test
    fun `extrapolating from a sample counts the first frame whole and the rest by average`() {
        // 3 frames took 1000 bytes of which the first was 600: later frames 200 each, so 10 frames = 600 + 9 * 200.
        assertEquals(2400L, AnimPlanner.extrapolate(1000, 3, 10, 600))
        assertEquals(1000L, AnimPlanner.extrapolate(1000, 3, 3, 600))
        try {
            AnimPlanner.extrapolate(500, 1, 10, 500)
            fail()
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `bad requests are refused`() {
        for (r in listOf<() -> Unit>(
            { AnimPlanner.plan(PlanRequest(0, 10, steady(2, 10))) },
            { AnimPlanner.plan(PlanRequest(10, 10, IntArray(0))) },
            { PlanLimits(maxFrames = 0) },
            { PlanLimits(minFps = 0.0) },
        )) {
            try {
                r()
                fail()
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `every step has a sentence`() {
        for (s in PlanStep.values()) assertTrue(AnimateCopy.planStep(s).endsWith("."))
        assertEquals(null, AnimateCopy.decodeProblem(DecodeProblem.NONE))
        for (p in DecodeProblem.values().filter { it != DecodeProblem.NONE }) assertTrue(AnimateCopy.decodeProblem(p)!!.endsWith("."))
        // As a note on a file that was saved, a cut-off long file is not "too large to open".
        assertEquals(AnimateCopy.ONLY_THE_START, AnimateCopy.decodeNote(DecodeProblem.TOO_LARGE))
        for (p in DecodeProblem.values().filter { it != DecodeProblem.TOO_LARGE }) assertEquals(AnimateCopy.decodeProblem(p), AnimateCopy.decodeNote(p))
    }
}
