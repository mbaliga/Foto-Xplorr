package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan validator: what blocks a render, which item is at fault, and that it never throws. */
class ReelPlanTest {

    private fun ok() = ReelProject(
        listOf(
            TitleItem("r1", "June", durationMs = 2_500L),
            StillItem("r2", 1L, 3_000L, ReelMotion.ZoomIn(NormPoint(0.2f, 0.8f))),
            ClipItem("r3", 2L, 500L, 3_500L, sourceDurationMs = 10_000L),
        ),
    )

    private fun kinds(p: ReelProject) = ReelPlan.validate(p).map { it.kind to it.itemIndex }

    @Test
    fun `a good plan has no issues and can render`() {
        assertEquals(emptyList<ReelIssue>(), ReelPlan.validate(ok()))
        assertTrue(ReelPlan.canRender(ok()))
    }

    @Test
    fun `an empty project cannot render`() {
        assertEquals(listOf(ReelIssueKind.NO_ITEMS to null), kinds(ReelProject()))
        assertFalse(ReelPlan.canRender(ReelProject()))
    }

    @Test
    fun `a repeated id is named at the second item`() {
        val p = ReelProject(listOf(StillItem("a", 1L), StillItem("a", 2L)))
        assertEquals(listOf(ReelIssueKind.DUPLICATE_ID to 1), kinds(p))
    }

    @Test
    fun `too short and too long items are named`() {
        val p = ReelProject(
            listOf(
                StillItem("r1", 1L, durationMs = 100L),
                StillItem("r2", 2L, durationMs = ReelLimits.ITEM_MAX_MS + 1),
            ),
        )
        assertEquals(listOf(ReelIssueKind.ITEM_TOO_SHORT to 0, ReelIssueKind.ITEM_TOO_LONG to 1), kinds(p))
    }

    @Test
    fun `a clip that ends before it starts, or runs past its video, is named`() {
        val back = ReelProject(listOf(ClipItem("r1", 1L, 3_000L, 1_000L)))
        assertTrue(ReelIssue(ReelIssueKind.CLIP_RANGE, 0) in ReelPlan.validate(back))
        val past = ReelProject(listOf(ClipItem("r1", 1L, 1_000L, 12_000L, sourceDurationMs = 10_000L)))
        assertEquals(listOf(ReelIssueKind.CLIP_PAST_END to 0), kinds(past))
        val unknownLength = ReelProject(listOf(ClipItem("r1", 1L, 1_000L, 12_000L, sourceDurationMs = 0L)))
        assertEquals(emptyList<ReelIssue>(), ReelPlan.validate(unknownLength))
    }

    @Test
    fun `a negative in point is a bad range`() {
        val p = ReelProject(listOf(ClipItem("r1", 1L, -5L, 2_000L)))
        assertEquals(listOf(ReelIssueKind.CLIP_RANGE to 0), kinds(p))
    }

    @Test
    fun `a motion outside the picture is named and blocks`() {
        val pan = ReelProject(listOf(StillItem("r1", 1L, motion = ReelMotion.Pan(NormRect(0f, 0f, 2f, 1f), NormRect.FULL))))
        assertEquals(listOf(ReelIssueKind.MOTION_RECT to 0), kinds(pan))
        val zoom = ReelProject(listOf(StillItem("r1", 1L, motion = ReelMotion.ZoomOut(NormPoint(1.5f, 0.5f)))))
        assertEquals(listOf(ReelIssueKind.MOTION_FOCUS to 0), kinds(zoom))
        val nan = ReelProject(listOf(StillItem("r1", 1L, motion = ReelMotion.ZoomIn(NormPoint(Float.NaN, 0.5f)))))
        assertFalse(ReelPlan.canRender(nan))
    }

    @Test
    fun `an empty title card is only a warning`() {
        val p = ReelProject(listOf(TitleItem("r1", "  "), StillItem("r2", 1L)))
        val issues = ReelPlan.validate(p)
        assertEquals(listOf(ReelIssue(ReelIssueKind.TITLE_EMPTY, 0)), issues)
        assertTrue(ReelPlan.canRender(issues))
    }

    @Test
    fun `a reel past the longest allowed is named for the whole plan`() {
        val items = (1..100).map { StillItem("r$it", it.toLong(), durationMs = 10_000L) }
        val p = ReelProject(items)
        assertTrue(ReelIssue(ReelIssueKind.TOO_LONG, null) in ReelPlan.validate(p))
    }

    @Test
    fun `more items than the limit is named`() {
        val items = (1..ReelLimits.MAX_ITEMS + 1).map { StillItem("r$it", it.toLong(), durationMs = 1_000L) }
        assertTrue(ReelIssue(ReelIssueKind.TOO_MANY_ITEMS) in ReelPlan.validate(ReelProject(items)))
    }

    @Test
    fun `a bad soundtrack blocks`() {
        val base = ok()
        assertTrue(ReelPlan.canRender(base.copy(soundtrack = ReelSoundtrack("content://docs/1", 1_000L, 2_000L))))
        for (bad in listOf(
            ReelSoundtrack(" "),
            ReelSoundtrack("content://docs/1", trimStartMs = -1L),
            ReelSoundtrack("content://docs/1", fadeOutMs = -1L),
            ReelSoundtrack("content://docs/1", fadeOutMs = ReelLimits.FADE_OUT_MAX_MS + 1),
        )) {
            assertEquals(listOf(ReelIssueKind.SOUNDTRACK to null), kinds(base.copy(soundtrack = bad)))
        }
    }

    @Test
    fun `every issue has a sentence`() {
        for (kind in ReelIssueKind.entries) {
            val text = ReelCopy.issue(ReelIssue(kind, 2))
            assertTrue(kind.name, text.isNotBlank() && text.endsWith("."))
        }
    }
}
