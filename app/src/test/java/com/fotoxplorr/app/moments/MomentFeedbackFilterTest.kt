package com.fotoxplorr.app.moments

import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a thumbs-down is worth.
 *
 * The behaviour under test is the one that decides whether the two thumbs in the key-moment menu
 * are a real control or decoration: a rejected moment must not come back on the next detection
 * pass. Pinned here rather than checked on a device because the failure mode is silent and slow —
 * a moment returning after a re-index is something you would only notice weeks later, on a video
 * you had already tidied up once.
 */
class MomentFeedbackFilterTest {

    private fun detected(vararg positions: Long) =
        positions.map { DetectedMoment(positionMs = it, confidence = 0.5f, label = "Scene change") }

    @Test
    fun `no rejections leaves every moment alone`() {
        val moments = detected(1_000L, 8_000L, 20_000L)
        assertEquals(moments, MomentFeedbackFilter.suppress(moments, emptyList()) { it.positionMs })
    }

    @Test
    fun `a moment at the exact rejected position is dropped`() {
        val kept = MomentFeedbackFilter.suppress(detected(1_000L, 8_000L), listOf(8_000L)) { it.positionMs }
        assertEquals(listOf(1_000L), kept.map { it.positionMs })
    }

    /**
     * The reason this is a window and not an equality check: re-detection is not guaranteed to
     * land on the same millisecond twice, so a rejection matched only exactly would silently stop
     * applying to the moment it was about the first time the sampling interval shifted.
     */
    @Test
    fun `a moment that drifted within the window is still recognised as the rejected one`() {
        val drifted = 8_000L + MomentFeedbackFilter.WINDOW_MS - 1
        val kept = MomentFeedbackFilter.suppress(detected(drifted), listOf(8_000L)) { it.positionMs }
        assertTrue("a $drifted ms moment should still match a rejection at 8000 ms", kept.isEmpty())
    }

    @Test
    fun `the window is inclusive at its edge`() {
        assertTrue(MomentFeedbackFilter.isRejected(8_000L + MomentFeedbackFilter.WINDOW_MS, listOf(8_000L)))
        assertTrue(MomentFeedbackFilter.isRejected(8_000L - MomentFeedbackFilter.WINDOW_MS, listOf(8_000L)))
    }

    /**
     * The window has to stay comfortably inside the detector's own minimum spacing between
     * reported moments, or rejecting one moment would take a genuinely different neighbouring
     * one down with it. At that spacing the nearest OTHER moment is always at least twice the
     * window away, which is what this checks.
     */
    @Test
    fun `a distinct neighbouring moment survives a rejection next door`() {
        val neighbour = 8_000L + MomentFeedbackFilter.WINDOW_MS * 2
        val kept = MomentFeedbackFilter.suppress(detected(8_000L, neighbour), listOf(8_000L)) { it.positionMs }
        assertEquals(listOf(neighbour), kept.map { it.positionMs })
    }

    @Test
    fun `rejections apply independently of each other`() {
        val kept = MomentFeedbackFilter
            .suppress(detected(1_000L, 8_000L, 20_000L), listOf(1_000L, 20_000L)) { it.positionMs }
        assertEquals(listOf(8_000L), kept.map { it.positionMs })
    }

    @Test
    fun `a moment outside every window is kept`() {
        assertFalse(MomentFeedbackFilter.isRejected(20_000L, listOf(1_000L, 8_000L)))
    }

    /**
     * The filter is generic so the indexer's [DetectedMoment]s and the store's [VideoMoment]s go
     * through one implementation — two copies of "the same moment" arithmetic is how the thing
     * shown and the thing stored end up disagreeing.
     */
    @Test
    fun `the same rule applies to stored moments`() {
        val stored = listOf(
            VideoMoment(MediaId(1L), 8_000L, MomentSource.AUTO),
            VideoMoment(MediaId(1L), 20_000L, MomentSource.AUTO),
        )
        val kept = MomentFeedbackFilter.suppress(stored, listOf(8_000L)) { it.positionMs }
        assertEquals(listOf(20_000L), kept.map { it.positionMs })
    }
}
