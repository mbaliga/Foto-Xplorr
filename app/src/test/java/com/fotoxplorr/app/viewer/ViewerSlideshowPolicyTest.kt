package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slideshow bar's rules (owner, #16), pinned without Compose: when the bar is drawn, when the
 * chrome may fold itself away during a slideshow, that a pause stops the countdown, and how far
 * the rows beneath the bar clear it.
 */
class ViewerSlideshowPolicyTest {

    // ── the countdown honours a pause ──────────────────────────────────────────────────────────

    private fun mayAdvance(
        active: Boolean = true,
        total: Int = 9,
        room: Boolean = false,
        video: Boolean = false,
        modeActive: Boolean = false,
        host: Boolean = true,
        paused: Boolean = false,
    ) = ViewerChromePolicy.slideshowMayAdvance(active, total, room, video, modeActive, host, paused)

    @Test
    fun `a paused slideshow never advances`() {
        assertTrue(mayAdvance())
        assertFalse(mayAdvance(paused = true))
    }

    @Test
    fun `the pause default leaves the existing rule alone`() {
        // The call shape from before the bar: no paused argument.
        assertTrue(ViewerChromePolicy.slideshowMayAdvance(true, 9, false, false, false))
        assertFalse(ViewerChromePolicy.slideshowMayAdvance(true, 9, false, true, false))
        assertFalse(ViewerChromePolicy.slideshowMayAdvance(true, 9, false, false, false, hostRunning = false))
    }

    @Test
    fun `every other stop still holds with the slideshow playing`() {
        assertFalse("inactive", mayAdvance(active = false))
        assertFalse("one photo", mayAdvance(total = 1))
        assertFalse("a room is open", mayAdvance(room = true))
        assertFalse("a video is playing", mayAdvance(video = true))
        assertFalse("a viewer mode owns the screen", mayAdvance(modeActive = true))
        assertFalse("the host is stopped", mayAdvance(host = false))
    }

    // ── when the bar is drawn ──────────────────────────────────────────────────────────────────

    @Test
    fun `the bar is shown with a slideshow the chrome and somewhere to go`() {
        assertTrue(ViewerChromePolicy.slideshowBarShown(slideshowActive = true, total = 9, chromeShown = true))
        assertFalse("no slideshow", ViewerChromePolicy.slideshowBarShown(false, 9, true))
        assertFalse("one photo has nowhere to go", ViewerChromePolicy.slideshowBarShown(true, 1, true))
        assertFalse("chrome down", ViewerChromePolicy.slideshowBarShown(true, 9, false))
    }

    // ── when the chrome may fold away ──────────────────────────────────────────────────────────

    private fun mayHide(
        active: Boolean = true,
        paused: Boolean = false,
        chrome: Boolean = true,
        video: Boolean = false,
        room: Boolean = false,
        modeActive: Boolean = false,
    ) = ViewerChromePolicy.slideshowChromeMayAutoHide(active, paused, chrome, video, room, modeActive)

    @Test
    fun `a running slideshow on a photo lets the chrome fold away`() {
        assertTrue(mayHide())
    }

    @Test
    fun `a pause a video a room a lift or no slideshow keeps the chrome as it is`() {
        assertFalse("paused: the bar is how it resumes", mayHide(paused = true))
        assertFalse("a video keeps its transport and the bar", mayHide(video = true))
        assertFalse("a room is open", mayHide(room = true))
        assertFalse("a viewer mode owns the screen", mayHide(modeActive = true))
        assertFalse("no slideshow", mayHide(active = false))
        assertFalse("already down", mayHide(chrome = false))
    }

    @Test
    fun `the chrome waits about three seconds`() {
        assertEquals(3_000L, ViewerChromePolicy.SLIDESHOW_CHROME_HIDE_MS)
    }

    // ── clearing the bar ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the chip row drops by the bar's height only while the bar is up`() {
        assertEquals(0f, ViewerChromePolicy.chipRowDropDp(false), 0f)
        assertEquals(SlideshowBarGeometry.BAR_HEIGHT_DP, ViewerChromePolicy.chipRowDropDp(true), 0f)
    }

    @Test
    fun `a video's resume chip clears whatever the host draws at the top`() {
        assertEquals("chrome down: nothing to clear", 0, ViewerChromePolicy.videoTopInsetDp(false, false))
        assertEquals("the chip row", ViewerChromePolicy.CHIP_ROW_DP, ViewerChromePolicy.videoTopInsetDp(true, false))
        val withBar = ViewerChromePolicy.videoTopInsetDp(true, true)
        assertTrue("the row and the bar, rounded up ($withBar)", withBar >= ViewerChromePolicy.CHIP_ROW_DP + 50.7891f)
        assertTrue("but not a whole dp more ($withBar)", withBar < ViewerChromePolicy.CHIP_ROW_DP + 52f)
    }

    @Test
    fun `the bar's targets fit inside its width and meet the 48dp minimum`() {
        val g = SlideshowBarGeometry
        assertTrue("targets are 48dp or more", g.TARGET_DP >= 48)
        val rowEnd = g.ROW_START_DP + 3 * g.TARGET_DP
        assertTrue("three targets end inside the bar ($rowEnd of ${g.BAR_WIDTH_DP})", rowEnd < g.BAR_WIDTH_DP)
        assertTrue("and the glyph fits its target", g.GLYPH_DP <= g.TARGET_DP)
    }
}
