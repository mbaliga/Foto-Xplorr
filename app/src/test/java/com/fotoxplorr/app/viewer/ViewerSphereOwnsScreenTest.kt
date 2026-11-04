package com.fotoxplorr.app.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While the 360 view is open the screen is owned: the slideshow does not advance under it (which
 * changed the photo and shut the view mid-look), and the filmstrip and chrome stand down.
 */
class ViewerSphereOwnsScreenTest {
    @Test
    fun `the sphere owns the screen even while browsing`() {
        assertTrue(ViewerChromePolicy.screenOwned(ViewerMode.Browsing, sphereOpen = true))
        assertFalse(ViewerChromePolicy.screenOwned(ViewerMode.Browsing, sphereOpen = false))
    }

    @Test
    fun `a running slideshow does not advance while the sphere is open`() {
        val owned = ViewerChromePolicy.screenOwned(ViewerMode.Browsing, sphereOpen = true)
        assertFalse(
            ViewerChromePolicy.slideshowMayAdvance(
                slideshowActive = true, total = 10, anyRoomVisible = false, videoPlaybackActive = false,
                modeActive = owned,
            ),
        )
        assertTrue(
            ViewerChromePolicy.slideshowMayAdvance(
                slideshowActive = true, total = 10, anyRoomVisible = false, videoPlaybackActive = false,
                modeActive = ViewerChromePolicy.screenOwned(ViewerMode.Browsing, sphereOpen = false),
            ),
        )
    }

    @Test
    fun `the filmstrip and chrome stand down while the sphere is open`() {
        val owned = ViewerChromePolicy.screenOwned(ViewerMode.Browsing, sphereOpen = true)
        assertFalse(ViewerChromePolicy.filmstripShown(true, false, true, 10, modeActive = owned))
        assertFalse(ViewerChromePolicy.chromeShown(true, false, modeActive = owned))
        assertTrue(ViewerChromePolicy.roomsWithdrawn(modeActive = owned, atHome = true))
    }
}
