package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The left room's card column: where each card stands for a wheel position, and what peeks. */
class LeftRoomPagerTest {
    private val h = 1000f
    private val pitch = 830f

    @Test
    fun `the pitch is a card plus the gap`() {
        assertEquals(pitch, pagerPitchPx(h), 0.01f)
    }

    @Test
    fun `at rest the live card is centred and its neighbours peek top and bottom`() {
        assertEquals(0f, pagerLiveOffsetPx(2, 2f, 5, h), 0.01f)
        assertEquals(listOf(1, 3), pagerGhostIndices(2f, 2, 5, h))
        assertEquals(-pitch, pagerOffsetPx(1, 2f, 5, h), 0.01f)
        assertEquals(pitch, pagerOffsetPx(3, 2f, 5, h), 0.01f)
    }

    @Test
    fun `the column moves in step with the wheel`() {
        // Half a row down the wheel: the live card is half a pitch up, the next half a pitch down.
        assertEquals(-pitch / 2, pagerLiveOffsetPx(2, 2.5f, 5, h), 0.01f)
        assertEquals(pitch / 2, pagerOffsetPx(3, 2.5f, 5, h), 0.01f)
    }

    @Test
    fun `the first and last destinations have no ghost past the end`() {
        assertEquals(listOf(1), pagerGhostIndices(0f, 0, 5, h))
        assertEquals(listOf(3), pagerGhostIndices(4f, 4, 5, h))
        assertEquals(emptyList<Int>(), pagerGhostIndices(0f, 0, 1, h))
    }

    @Test
    fun `a position past the ends is held at the end`() {
        assertEquals(0f, pagerClamp(-0.4f, 5), 0f)
        assertEquals(4f, pagerClamp(4.7f, 5), 0f)
        assertEquals(0f, pagerClamp(Float.NaN, 5), 0f)
        assertEquals(pagerOffsetPx(0, 0f, 5, h), pagerOffsetPx(0, -0.4f, 5, h), 0f)
    }

    @Test
    fun `a card wholly outside the window is not drawn`() {
        // The window shows the live card's neighbours only; a card two rows out is far off screen.
        assertFalse(2 + 2 in pagerGhostIndices(2f, 2, 9, h))
        assertTrue(pagerGhostIndices(2f, 2, 9, h).all { it == 1 || it == 3 })
    }

    @Test
    fun `a ghost sliding in appears at the edge of the window`() {
        // Mid-turn from row 2 to 3 the card after next enters from below.
        val mid = pagerGhostIndices(2.6f, 2, 9, h)
        assertEquals(listOf(3), mid)
        assertEquals(listOf(4), pagerGhostIndices(3.4f, 3, 9, h))
    }

    @Test
    fun `committing the destination mid turn moves nothing on screen`() {
        val p = 1.6f
        for ((old, new) in listOf(1 to 2, 2 to 1)) {
            val liveBefore = pagerLiveOffsetPx(old, p, 5, h)
            val ghostNewBefore = pagerOffsetPx(new, p, 5, h)
            // After the commit the live card IS the new index and the old one becomes a ghost.
            val liveAfter = pagerLiveOffsetPx(new, p, 5, h)
            val ghostOldAfter = pagerOffsetPx(old, p, 5, h)
            assertEquals("the new live card stands where its ghost stood", ghostNewBefore, liveAfter, 0.001f)
            assertEquals("the old card's ghost stands where the live card stood", liveBefore, ghostOldAfter, 0.001f)
            assertTrue(old in pagerGhostIndices(p, new, 5, h) || kotlin.math.abs(liveBefore) >= h)
        }
    }

    @Test
    fun `no cover for protected or experimental`() {
        assertFalse(coverEligible(HyleDestination.PROTECTED))
        assertFalse(coverEligible(HyleDestination.PLACES))
        assertTrue(coverEligible(HyleDestination.PHOTOS))
        assertTrue(coverEligible(HyleDestination.FAVOURITES))
    }

    @Test
    fun `a card with no cover gets a stable tint`() {
        assertEquals(ghostTintFor("PHOTOS"), ghostTintFor("PHOTOS"))
        assertNotEquals(ghostTintFor("PHOTOS"), ghostTintFor("VIDEOS"))
    }
}
