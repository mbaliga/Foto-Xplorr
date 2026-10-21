package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word rail's fade is its only position indicator — no scrollbar, no highlight box — so the
 * curve is the affordance, and it is the owner's: the reference's greys, not a formula that
 * happens to pass near them.
 */
class DestinationWordRailTest {

    private val tolerance = 1e-4f

    @Test
    fun `the whole-row stops are the reference greys`() {
        assertEquals(1f, destinationRowAlpha(0), tolerance)
        assertEquals(0x9A / 255f, destinationRowAlpha(1), tolerance)
        assertEquals(0x70 / 255f, destinationRowAlpha(2), tolerance)
        assertEquals(0x4A / 255f, destinationRowAlpha(3), tolerance)
        assertEquals(0x2E / 255f, destinationRowAlpha(4), tolerance)
    }

    @Test
    fun `the fade is symmetric about the selected row`() {
        for (distance in 0..10) {
            assertEquals(destinationRowAlpha(distance), destinationRowAlpha(-distance), 0f)
        }
    }

    @Test
    fun `four rows out and beyond sit on the floor`() {
        // A word that fades to nothing has left the rail; the ends of the run must stay present.
        for (distance in 4..40) {
            assertEquals("distance $distance", 0x2E / 255f, destinationRowAlpha(distance), tolerance)
        }
    }

    @Test
    fun `alpha falls strictly until the floor and never rises`() {
        for (distance in 1..4) {
            assertTrue(destinationRowAlpha(distance) < destinationRowAlpha(distance - 1))
        }
        for (distance in 1..40) {
            assertTrue(destinationRowAlpha(distance) <= destinationRowAlpha(distance - 1))
        }
    }

    @Test
    fun `the fractional reading agrees with the stops at every whole row`() {
        for (distance in -8..8) {
            assertEquals(
                "row $distance",
                destinationRowAlpha(distance),
                destinationRowFade(distance.toFloat()),
                tolerance,
            )
        }
    }

    @Test
    fun `between rows the fade sits between its neighbours`() {
        // Mid-drag a row is 1.4 rows out; quantising that would make the fade step on settle.
        val a = destinationRowFade(1.4f)
        assertTrue(a < destinationRowAlpha(1) && a > destinationRowAlpha(2))
        assertEquals(
            destinationRowAlpha(1) + (destinationRowAlpha(2) - destinationRowAlpha(1)) * 0.4f,
            a,
            tolerance,
        )
        // And it is monotonic across every quarter row out to the floor.
        var previous = destinationRowFade(0f)
        var d = 0.25f
        while (d <= 6f) {
            val next = destinationRowFade(d)
            assertTrue("fade rose at $d", next <= previous + tolerance)
            previous = next
            d += 0.25f
        }
    }

    @Test
    fun `the marker stretch peaks mid-flight and is gone at both ends`() {
        assertEquals(0f, markerStretch(2f, 2f, 7f), 0f)
        assertEquals(0f, markerStretch(7f, 2f, 7f), 0f)
        assertEquals(1f, markerStretch(4.5f, 2f, 7f), tolerance)
        // Travelling upwards is the same journey the other way.
        assertEquals(1f, markerStretch(4.5f, 7f, 2f), tolerance)
        assertEquals(markerStretch(3f, 2f, 7f), markerStretch(6f, 7f, 2f), tolerance)
    }

    @Test
    fun `no journey means no stretch`() {
        assertEquals(0f, markerStretch(3f, 3f, 3f), 0f)
        // Overshoot from an interrupted animation clamps rather than going negative.
        assertEquals(0f, markerStretch(9f, 2f, 7f), 0f)
        assertEquals(0f, markerStretch(0f, 2f, 7f), 0f)
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
        // Half a pitch is the tie; Float.roundToInt rounds half up. The marker, the debounced
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
