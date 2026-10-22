package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewerMode]: the one value that says whether lifting a subject or selecting text owns the
 * viewer. The two share every predicate that hides the chrome, withdraws the rooms, holds the
 * slideshow and hides the zoom minimap, and they exclude each other by construction.
 */
class ViewerModeTest {

    @Test
    fun `only browsing leaves the screen to the viewer`() {
        assertFalse(ViewerMode.Browsing.ownsScreen)
        assertTrue(ViewerMode.Lifting.ownsScreen)
        assertTrue(ViewerMode.SelectingText.ownsScreen)
    }

    @Test
    fun `each mode answers only for itself`() {
        assertTrue(ViewerMode.Lifting.isLifting)
        assertFalse(ViewerMode.Lifting.isSelectingText)
        assertTrue(ViewerMode.SelectingText.isSelectingText)
        assertFalse(ViewerMode.SelectingText.isLifting)
        assertFalse(ViewerMode.Browsing.isLifting)
        assertFalse(ViewerMode.Browsing.isSelectingText)
    }

    @Test
    fun `opening one mode clears the other`() {
        assertEquals(ViewerMode.Lifting, ViewerMode.open(ViewerMode.SelectingText, ViewerMode.Lifting))
        assertEquals(ViewerMode.SelectingText, ViewerMode.open(ViewerMode.Lifting, ViewerMode.SelectingText))
        assertEquals(ViewerMode.SelectingText, ViewerMode.open(ViewerMode.Browsing, ViewerMode.SelectingText))
    }

    @Test
    fun `opening the mode that is already open changes nothing`() {
        assertSame(ViewerMode.Lifting, ViewerMode.open(ViewerMode.Lifting, ViewerMode.Lifting))
        assertSame(ViewerMode.SelectingText, ViewerMode.open(ViewerMode.SelectingText, ViewerMode.SelectingText))
    }

    @Test
    fun `leaving any mode lands on browsing and leaving browsing stays there`() {
        assertEquals(ViewerMode.Browsing, ViewerMode.leave(ViewerMode.Lifting))
        assertEquals(ViewerMode.Browsing, ViewerMode.leave(ViewerMode.SelectingText))
        assertEquals(ViewerMode.Browsing, ViewerMode.leave(ViewerMode.Browsing))
    }

    @Test
    fun `either mode feeds the chrome, filmstrip, slideshow and room predicates alike`() {
        for (mode in listOf(ViewerMode.Lifting, ViewerMode.SelectingText)) {
            val active = mode.ownsScreen
            assertFalse(ViewerChromePolicy.chromeShown(chromeVisible = true, inPictureInPicture = false, modeActive = active))
            assertFalse(ViewerChromePolicy.filmstripShown(true, false, true, rollSize = 9, modeActive = active))
            assertFalse(ViewerChromePolicy.slideshowMayAdvance(true, 9, false, false, modeActive = active))
            assertFalse(
                ViewerChromePolicy.slideshowChromeMayAutoHide(true, false, true, false, false, modeActive = active),
            )
            assertTrue(ViewerChromePolicy.roomsWithdrawn(modeActive = active, atHome = true))
            assertFalse(ViewerChromePolicy.oneFingerGesturesAreViewers(active))
        }
        val browsing = ViewerMode.Browsing.ownsScreen
        assertTrue(ViewerChromePolicy.chromeShown(chromeVisible = true, inPictureInPicture = false, modeActive = browsing))
        assertTrue(ViewerChromePolicy.oneFingerGesturesAreViewers(browsing))
    }

    @Test
    fun `the backdrop behind a see-through photo stays under text selection and goes only for a lift`() {
        // The lift editor paints its own stage; text selection draws on the photo as it is.
        assertFalse(ViewerChromePolicy.transparencyBackdropShown(false, true, lifting = ViewerMode.Lifting.isLifting))
        assertTrue(ViewerChromePolicy.transparencyBackdropShown(false, true, lifting = ViewerMode.SelectingText.isLifting))
    }
}
