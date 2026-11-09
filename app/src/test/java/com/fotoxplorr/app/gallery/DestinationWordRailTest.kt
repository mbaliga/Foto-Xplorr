package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word rail's emphasis is its only position indicator -- no scrollbar, no highlight box, no
 * marker -- so the curve is the affordance, and it is the owner's (2026-11-07 reference, then
 * 2026-11-08 *"shorter list"*): the word on the focus line at 24sp at full ink, 17sp at one row,
 * and the words fading out with distance so only about five show.
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
    fun `one row out a word is 17sp at half strength`() {
        val e = destinationRowEmphasis(1f)
        assertEquals(17f / 24f, e.scale, tolerance)
        assertEquals(0.5f, e.alpha, tolerance)
        assertEquals(17f, RAIL_FONT_SP * e.scale, tolerance)
    }

    @Test
    fun `two rows out a word is a faint 0_2`() {
        assertEquals(0.2f, destinationRowEmphasis(2f).alpha, tolerance)
        assertEquals(17f / 24f, destinationRowEmphasis(2f).scale, tolerance)
    }

    @Test
    fun `from two and a half rows a word is gone`() {
        for (distance in listOf(2.5f, 2.6f, 3f, 4f, 8f, 40f)) {
            assertEquals("distance $distance", 0f, destinationRowEmphasis(distance).alpha, 0f)
            assertEquals("distance -$distance", 0f, destinationRowEmphasis(-distance).alpha, 0f)
        }
    }

    @Test
    fun `beyond one row the size holds at 17sp`() {
        for (distance in listOf(1.5f, 2f, 3f, 8f, -2f)) {
            assertEquals(17f / 24f, destinationRowEmphasis(distance).scale, tolerance)
        }
    }

    @Test
    fun `the emphasis is symmetric about the focus line`() {
        var d = 0f
        while (d <= 3f) {
            assertEquals(destinationRowEmphasis(d), destinationRowEmphasis(-d))
            d += 0.125f
        }
    }

    @Test
    fun `the curve is continuous and falls monotonically, with no jump at any mark`() {
        // Sampled finely, a step of 1/64 row may move the alpha by at most the steepest slope
        // (0.5 per row) over that step: a jump at 1, 2 or 2.5 rows would exceed it.
        val step = 1f / 64f
        val maxStep = 0.5f * step + tolerance
        var previous = destinationRowEmphasis(0f)
        var d = step
        while (d <= 3f) {
            val next = destinationRowEmphasis(d)
            assertTrue("alpha rose at $d", next.alpha <= previous.alpha + tolerance)
            assertTrue("scale rose at $d", next.scale <= previous.scale + tolerance)
            assertTrue("alpha jumped at $d", previous.alpha - next.alpha <= maxStep)
            previous = next
            d += step
        }
    }

    @Test
    fun `between the line and one row the emphasis is interpolated, not stepped`() {
        val e = destinationRowEmphasis(0.4f)
        assertEquals(1f + (17f / 24f - 1f) * 0.4f, e.scale, tolerance)
        assertEquals(1f + (RAIL_ALPHA_ONE_ROW - 1f) * 0.4f, e.alpha, tolerance)
    }

    @Test
    fun `a word never grows past the focus size or gains strength with distance`() {
        var d = -5f
        while (d <= 5f) {
            val e = destinationRowEmphasis(d)
            assertTrue(e.alpha in 0f..1f)
            assertTrue(e.scale in (17f / 24f - tolerance)..1f)
            d += 0.25f
        }
    }

    // --- Which rows a finger may pick --------------------------------------------------------

    @Test
    fun `a row that has faded out cannot be tapped by accident`() {
        assertTrue(railRowTappable(0f))
        assertTrue(railRowTappable(1f))
        assertTrue(railRowTappable(-2f))
        assertTrue(railRowTappable(2.7f))
        assertFalse(railRowTappable(2.75f))
        assertFalse(railRowTappable(-3f))
        assertFalse(railRowTappable(8f))
    }

    @Test
    fun `the tap guard starts after the fade is complete`() {
        assertTrue(RAIL_TAP_IGNORE_ROWS > RAIL_FADE_OUT_ROWS)
    }

    // --- The status bars with the light room ------------------------------------------------

    @Test
    fun `the bars go dark once the light room is mostly open and not before`() {
        assertFalse(leftRoomWantsDarkBars(0f))
        assertFalse(leftRoomWantsDarkBars(0.5f))
        assertTrue(leftRoomWantsDarkBars(0.51f))
        assertTrue(leftRoomWantsDarkBars(1f))
        // The right room (negative progress) never asks for them.
        assertFalse(leftRoomWantsDarkBars(-1f))
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

    @Test
    fun `the light room's ground is the floor's colour from end to end so no seam shows`() {
        // The shell paints a flat RAIL_LIGHT_FLOOR around the card; a panel that ended on another colour
        // showed as a seam at its edge (owner, 2026-11-09).
        val light = RailPalette.Light
        assertEquals(RAIL_LIGHT_FLOOR, light.groundStart)
        assertEquals(RAIL_LIGHT_FLOOR, light.groundEnd)
    }
}
