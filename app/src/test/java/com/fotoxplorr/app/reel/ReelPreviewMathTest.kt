package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the preview draws is read from the same plan as the export: which pictures, how opaque, how far pushed. */
class ReelPreviewMathTest {
    private fun project(vararg transitions: ReelTransition) = ReelProject(
        items = transitions.mapIndexed { i, t -> StillItem("r${i + 1}", i + 1L, 3_000L, transition = t) },
    )

    private fun plan(p: ReelProject, plain: Boolean = false) = ReelPreviewMath.planFor(p, plain, null)

    @Test
    fun `cuts show one picture at a time and every moment is covered`() {
        val p = plan(project(ReelTransition.CUT, ReelTransition.CUT, ReelTransition.CUT))
        assertEquals(9_000_000L, p.totalUs)
        for (t in 0L until p.totalUs step 50_000L) {
            val pieces = ReelPreviewMath.piecesAt(p, t)
            assertEquals("at $t", 1, pieces.size)
            assertEquals(1f, pieces[0].alpha, 0f)
            assertEquals(0f, pieces[0].shiftX, 0f)
        }
        assertEquals(0, ReelPreviewMath.indexAt(p, 0L))
        assertEquals(1, ReelPreviewMath.indexAt(p, 3_000_000L))
        assertEquals(2, ReelPreviewMath.indexAt(p, 8_999_000L))
    }

    @Test
    fun `a cross-fade shows two pictures with the one on top fading in over the one below`() {
        val p = plan(project(ReelTransition.CROSS_FADE, ReelTransition.CUT))
        val join = p.joins.single()
        val mid = join.startUs + join.durationUs / 2
        val pieces = ReelPreviewMath.piecesAt(p, mid)
        assertEquals(2, pieces.size)
        // The one underneath is drawn first and is whole; the one on top carries the fade.
        assertEquals(1f, pieces[0].alpha, 0f)
        assertEquals(0.5f, pieces[1].alpha, 0.01f)
        assertEquals(setOf(0, 1), pieces.map { it.item.index }.toSet())
        // Before it starts there is one picture, and after it ends the other.
        assertEquals(listOf(0), ReelPreviewMath.piecesAt(p, join.startUs - 1_000L).map { it.item.index })
        assertEquals(listOf(1), ReelPreviewMath.piecesAt(p, join.startUs + join.durationUs + 1_000L).map { it.item.index })
    }

    @Test
    fun `a slide pushes both pictures a frame apart and they meet in the middle of the hand-over`() {
        val p = plan(project(ReelTransition.SLIDE, ReelTransition.CUT))
        val join = p.joins.single()
        val pieces = ReelPreviewMath.piecesAt(p, join.startUs + join.durationUs / 2)
        assertEquals(2, pieces.size)
        val out = pieces.first { it.item.index == 0 }
        val into = pieces.first { it.item.index == 1 }
        assertEquals(ReelLayers.FRAME_WIDTH_NDC, into.shiftX - out.shiftX, 1e-3f)
    }

    @Test
    fun `plain cuts draw the hand-overs as cuts and the reel is as long as its items added up`() {
        val p = project(ReelTransition.CROSS_FADE, ReelTransition.SLIDE, ReelTransition.CUT)
        val layered = plan(p)
        val flat = plan(p, plain = true)
        assertTrue(layered.isLayered)
        assertTrue(!flat.isLayered)
        assertEquals(9_000_000L, flat.totalUs)
        assertTrue(layered.totalUs < flat.totalUs)
        for (t in 0L until flat.totalUs step 100_000L) assertEquals(1, ReelPreviewMath.piecesAt(flat, t).size)
    }

    @Test
    fun `progress runs from 0 to 1 over an item`() {
        val p = plan(project(ReelTransition.CUT, ReelTransition.CUT))
        assertEquals(0f, ReelPreviewMath.piecesAt(p, 0L).single().progress, 0f)
        assertEquals(0.5f, ReelPreviewMath.piecesAt(p, 1_500_000L).single().progress, 0.001f)
        assertEquals(0.5f, ReelPreviewMath.piecesAt(p, 4_500_000L).single().progress, 0.001f)
    }

    @Test
    fun `the clock loops, and a jump lands at an item's start`() {
        val p = plan(project(ReelTransition.CUT, ReelTransition.CUT))
        assertEquals(1_000_000L, ReelPreviewMath.loop(p, p.totalUs + 1_000_000L))
        assertEquals(0L, ReelPreviewMath.loop(p, p.totalUs))
        assertEquals(0L, ReelPreviewMath.loop(p, -p.totalUs))
        assertEquals(3_000_000L, ReelPreviewMath.startOf(p, 1))
        assertEquals(0L, ReelPreviewMath.startOf(p, 7))
    }

    @Test
    fun `an empty reel draws nothing and loops at zero`() {
        val p = plan(ReelProject())
        assertEquals(0L, p.totalUs)
        assertTrue(ReelPreviewMath.piecesAt(p, 0L).isEmpty())
        assertEquals(0L, ReelPreviewMath.loop(p, 123L))
        assertEquals(-1, ReelPreviewMath.indexAt(p, 0L))
    }

    @Test
    fun `the preview takes the shape of the aspect chosen`() {
        assertEquals(16f / 9f, ReelPreviewMath.planFor(project(ReelTransition.CUT).copy(aspect = ReelAspect.WIDE), false, null).frame.ratio, 0.01f)
        assertEquals(9f / 16f, ReelPreviewMath.planFor(project(ReelTransition.CUT).copy(aspect = ReelAspect.TALL), false, null).frame.ratio, 0.01f)
        assertEquals(1f, ReelPreviewMath.planFor(project(ReelTransition.CUT).copy(aspect = ReelAspect.SQUARE), false, null).frame.ratio, 0.01f)
        assertEquals(4f / 3f, ReelPreviewMath.planFor(project(ReelTransition.CUT).copy(aspect = ReelAspect.ORIGINAL), false, 4f / 3f).frame.ratio, 0.02f)
    }

    @Test
    fun `animations off in the system means the preview starts paused`() {
        assertTrue(ReelPreviewMath.reduceMotion(0f))
        assertTrue(!ReelPreviewMath.reduceMotion(1f))
        assertTrue(!ReelPreviewMath.reduceMotion(0.5f))
        assertTrue(!ReelPreviewMath.reduceMotion(Float.NaN))
    }
}
