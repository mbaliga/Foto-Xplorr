package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word rail's emphasis is its only position indicator -- no scrollbar, no highlight box, no
 * marker -- so the curve is the affordance, and it is the owner's (reference 2026-11-07): the word
 * on the focus line at 24sp in full white, every other word at 17sp in one grey.
 */
class DestinationWordRailTest {

    private val tolerance = 1e-4f

    @Test
    fun `on the focus line a word is the full focus look`() {
        val e = destinationRowEmphasis(0f)
        assertEquals(1f, e.scale, 0f)
        assertEquals(1f, e.alpha, 0f)
    }

    @Test
    fun `one row out a word is the reference's 17sp in the uniform grey`() {
        val e = destinationRowEmphasis(1f)
        assertEquals(17f / 24f, e.scale, tolerance)
        assertEquals(RAIL_REST_ALPHA, e.alpha, tolerance)
        assertEquals(17f, RAIL_FONT_SP * e.scale, tolerance)
        assertEquals(0.5f, RAIL_REST_ALPHA, tolerance)
    }

    @Test
    fun `every word beyond one row is the same, not a further fade`() {
        // The reference's other words are one grey, however far down the run they are.
        val rest = destinationRowEmphasis(1f)
        for (distance in listOf(1.5f, 2f, 3f, 4f, 8f, 40f)) {
            assertEquals("distance $distance", rest, destinationRowEmphasis(distance))
            assertEquals("distance -$distance", rest, destinationRowEmphasis(-distance))
        }
    }

    @Test
    fun `the emphasis is symmetric about the focus line`() {
        var d = 0f
        while (d <= 2f) {
            assertEquals(destinationRowEmphasis(d), destinationRowEmphasis(-d))
            d += 0.125f
        }
    }

    @Test
    fun `between the line and one row the emphasis is interpolated, not stepped`() {
        // Mid-drag a row is 0.4 rows out; quantising that would make the word jump on settle.
        val e = destinationRowEmphasis(0.4f)
        assertEquals(1f + (17f / 24f - 1f) * 0.4f, e.scale, tolerance)
        assertEquals(1f + (RAIL_REST_ALPHA - 1f) * 0.4f, e.alpha, tolerance)
        // And it falls monotonically across every eighth of a row, then holds.
        var previous = destinationRowEmphasis(0f)
        var step = 0.125f
        while (step <= 3f) {
            val next = destinationRowEmphasis(step)
            assertTrue("scale rose at $step", next.scale <= previous.scale + tolerance)
            assertTrue("alpha rose at $step", next.alpha <= previous.alpha + tolerance)
            if (step < 1f) {
                assertTrue("scale stepped at $step", next.scale < previous.scale)
                assertTrue("alpha stepped at $step", next.alpha < previous.alpha)
            }
            previous = next
            step += 0.125f
        }
    }

    @Test
    fun `a word never vanishes or grows past the focus size`() {
        var d = -5f
        while (d <= 5f) {
            val e = destinationRowEmphasis(d)
            assertTrue(e.alpha in RAIL_REST_ALPHA..1f)
            assertTrue(e.scale in (17f / 24f - tolerance)..1f)
            d += 0.25f
        }
    }

    // --- Which word is under the finger -------------------------------------------------------

    @Test
    fun `the focus index is the scroll offset in rows, rounded`() {
        val pitch = 160f
        assertEquals(0, railFocusIndex(0, pitch, 8))
        assertEquals(1, railFocusIndex(160, pitch, 8))
        assertEquals(4, railFocusIndex(4 * 160, pitch, 8))
        assertEquals(0, railFocusIndex(79, pitch, 8))
        assertEquals(2, railFocusIndex(2 * 160 + 79, pitch, 8))
    }

    @Test
    fun `exactly between two rows rounds up`() {
        // Half a pitch is the tie; Float.roundToInt rounds half up. The emphasis, the debounced
        // commit and the release snap all go through this one function, so whichever way the
        // tie falls they fall the same way -- pin it so a refactor cannot split them.
        assertEquals(1, railFocusIndex(80, 160f, 8))
        assertEquals(5, railFocusIndex(4 * 160 + 80, 160f, 8))
    }

    @Test
    fun `the focus index is clamped to the run`() {
        assertEquals(0, railFocusIndex(-500, 160f, 8))
        assertEquals(8, railFocusIndex(100_000, 160f, 8))
    }

    @Test
    fun `degenerate runs and pitches focus the first row`() {
        assertEquals(0, railFocusIndex(300, 160f, 0))
        assertEquals(0, railFocusIndex(300, 160f, -1))
        assertEquals(0, railFocusIndex(300, 0f, 8))
    }

    // --- What the debounce waits to commit ----------------------------------------------------

    @Test
    fun `a drag resting on a new word has that word pending`() {
        assertEquals(6, railPendingCommit(focusIndex = 6, selectedIndex = 4, dragging = true))
    }

    @Test
    fun `a drag back on the selected word has nothing pending`() {
        assertNull(railPendingCommit(focusIndex = 4, selectedIndex = 4, dragging = true))
    }

    @Test
    fun `a fling after the lift and the rail's own animations have nothing pending`() {
        // A fling commits once where it lands. Following a tap or settling on a detent passes through rows nobody chose; committing
        // one of them would undo the tap that caused the animation.
        assertNull(railPendingCommit(focusIndex = 2, selectedIndex = 4, dragging = false))
    }

    @Test
    fun `an unknown selection makes any scrubbed word a change`() {
        assertEquals(0, railPendingCommit(focusIndex = 0, selectedIndex = -1, dragging = true))
    }
}
