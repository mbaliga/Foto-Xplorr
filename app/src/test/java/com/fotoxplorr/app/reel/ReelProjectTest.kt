package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reel as a recipe: how long it is, where each item sits, and the edits that return a new project. */
class ReelProjectTest {

    private fun still(id: String, ms: Long = 3_000L, next: ReelTransition = ReelTransition.CUT) =
        StillItem(id = id, mediaId = id.filter(Char::isDigit).toLongOrNull() ?: 1L, durationMs = ms, transition = next)

    @Test
    fun `an empty project is zero long and has no timeline`() {
        val p = ReelProject()
        assertEquals(0L, p.totalDurationMs)
        assertTrue(p.timeline().isEmpty())
        assertEquals(-1, p.indexAt(0))
    }

    @Test
    fun `cuts add up exactly`() {
        val p = ReelProject(listOf(still("r1"), still("r2", 2_500L), still("r3", 3_500L)))
        assertEquals(9_000L, p.totalDurationMs)
    }

    @Test
    fun `a cross-fade lies over the next item and shortens the reel`() {
        val p = ReelProject(listOf(still("r1", next = ReelTransition.CROSS_FADE), still("r2")))
        assertEquals(500L, p.overlapAfterMs(0))
        assertEquals(5_500L, p.totalDurationMs)
    }

    @Test
    fun `a slide overlaps like a fade`() {
        val p = ReelProject(listOf(still("r1", next = ReelTransition.SLIDE), still("r2")))
        assertEquals(5_500L, p.totalDurationMs)
    }

    @Test
    fun `an overlap is never more than half of either neighbour`() {
        val p = ReelProject(listOf(still("r1", 3_000L, ReelTransition.CROSS_FADE), still("r2", 600L)))
        assertEquals(300L, p.overlapAfterMs(0))
        assertEquals(3_300L, p.totalDurationMs)
    }

    @Test
    fun `the last item's transition and out of range indexes overlap nothing`() {
        val p = ReelProject(listOf(still("r1", next = ReelTransition.CROSS_FADE)))
        assertEquals(0L, p.overlapAfterMs(0))
        assertEquals(0L, p.overlapAfterMs(-1))
        assertEquals(0L, p.overlapAfterMs(5))
        assertEquals(3_000L, p.totalDurationMs)
    }

    @Test
    fun `a clip lasts from in to out`() {
        val clip = ClipItem("r1", 5L, inMs = 1_000L, outMs = 3_500L, sourceDurationMs = 9_000L)
        assertEquals(2_500L, clip.durationMs)
        assertEquals(0L, ClipItem("r2", 5L, inMs = 3_000L, outMs = 1_000L).durationMs)
    }

    @Test
    fun `the timeline starts each item where the last one's overlap begins`() {
        val p = ReelProject(
            listOf(
                still("r1", 3_000L, ReelTransition.CROSS_FADE),
                still("r2", 3_000L, ReelTransition.CUT),
                still("r3", 2_000L),
            ),
        )
        val slots = p.timeline()
        assertEquals(listOf(0L, 2_500L, 5_500L), slots.map { it.startMs })
        assertEquals(listOf(3_000L, 5_500L, 7_500L), slots.map { it.endMs })
        assertEquals(p.totalDurationMs, slots.last().endMs)
    }

    @Test
    fun `indexAt finds the item on screen and the later one during an overlap`() {
        val p = ReelProject(listOf(still("r1", 3_000L, ReelTransition.CROSS_FADE), still("r2", 3_000L)))
        assertEquals(0, p.indexAt(0))
        assertEquals(0, p.indexAt(2_499))
        assertEquals(1, p.indexAt(2_500))
        assertEquals(1, p.indexAt(5_499))
        assertEquals(-1, p.indexAt(5_500))
        assertEquals(-1, p.indexAt(-1))
    }

    @Test
    fun `freshId is one past the highest r number and skips odd ids`() {
        val p = ReelProject(listOf(still("r4"), still("keep"), still("r2")))
        assertEquals("r5", p.freshId())
        assertEquals("r1", ReelProject().freshId())
    }

    @Test
    fun `insert append remove replace move and duplicate return new projects`() {
        val base = ReelProject(listOf(still("r1"), still("r2"), still("r3")))
        assertEquals(listOf("r1", "r9", "r2", "r3"), base.inserted(1, still("r9")).items.map { it.id })
        assertEquals(listOf("r1", "r2", "r3", "r9"), base.appended(still("r9")).items.map { it.id })
        assertEquals(listOf("r1", "r2", "r3", "r9"), base.inserted(99, still("r9")).items.map { it.id })
        assertEquals(listOf("r2", "r3"), base.removed(0).items.map { it.id })
        assertEquals(listOf("r1", "r9", "r3"), base.replaced(1, still("r9")).items.map { it.id })
        assertEquals(listOf("r2", "r3", "r1"), base.moved(0, 2).items.map { it.id })
        assertEquals(listOf("r3", "r1", "r2"), base.moved(2, 0).items.map { it.id })
        assertEquals(listOf("r1", "r4", "r2", "r3"), base.duplicated(0).items.map { it.id })
        assertEquals(3, base.items.size)
    }

    @Test
    fun `an out of range edit changes nothing`() {
        val base = ReelProject(listOf(still("r1"), still("r2")))
        assertSame(base, base.removed(5))
        assertSame(base, base.removed(-1))
        assertSame(base, base.replaced(2, still("r9")))
        assertSame(base, base.moved(0, 2))
        assertSame(base, base.moved(1, 1))
        assertSame(base, base.duplicated(7))
    }

    @Test
    fun `a duplicated item copies the picture under a new id`() {
        val base = ReelProject(listOf(StillItem("r1", 42L, motion = ReelMotion.ZoomIn())))
        val dup = base.duplicated(0).items[1] as StillItem
        assertEquals("r2", dup.id)
        assertEquals(42L, dup.mediaId)
        assertEquals(ReelMotion.ZoomIn(), dup.motion)
    }

    @Test
    fun `withTransitions sets every hand-over`() {
        val p = ReelProject(listOf(still("r1"), still("r2"))).withTransitions(ReelTransition.SLIDE)
        assertTrue(p.items.all { it.transition == ReelTransition.SLIDE })
    }
}
