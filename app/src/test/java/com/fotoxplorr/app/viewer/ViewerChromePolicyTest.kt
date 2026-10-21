package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewerChromePolicy], the viewer's "what is drawn, and when" rules, pinned without Compose.
 */
class ViewerChromePolicyTest {

    // ── the filmstrip is roll-level, never asset-level (#28) ───────────────────────────────────

    @Test
    fun `the filmstrip is shown whenever chrome is up, the preference is on and there is a roll`() {
        assertTrue(
            ViewerChromePolicy.filmstripShown(
                chromeVisible = true, inPictureInPicture = false, showFilmstripPreference = true, rollSize = 9,
            ),
        )
    }

    @Test
    fun `flipping any single input hides the filmstrip`() {
        fun shown(
            chrome: Boolean = true,
            pip: Boolean = false,
            preference: Boolean = true,
            roll: Int = 9,
            lifting: Boolean = false,
        ) = ViewerChromePolicy.filmstripShown(chrome, pip, preference, roll, lifting)

        assertTrue(shown())
        assertFalse("immersive means no strip", shown(chrome = false))
        assertFalse("never inside a PiP window", shown(pip = true))
        assertFalse("the settings switch", shown(preference = false))
        assertFalse("one photo has nothing to scrub", shown(roll = 1))
        assertFalse("an empty roll", shown(roll = 0))
        assertFalse("the lift editor owns the screen", shown(lifting = true))
    }

    /**
     * The regression pin for the owner's report. [ViewerChromePolicy.filmstripShown] takes no
     * asset and no `isVideo`, so its answer for a video is by construction its answer for a
     * photo; this test states that in terms of the scenario ("scrub onto a video") so a future
     * change that adds the gate back has to change a test whose name says why it is wrong.
     */
    @Test
    fun `scrubbing onto a video does not remove the strip from under the finger`() {
        // Before the scrub the current photo is a still; after it, a video. The policy sees only
        // the chrome state and the roll, none of which changed.
        val beforeScrub = ViewerChromePolicy.filmstripShown(true, false, true, rollSize = 9)
        val afterScrubOntoVideo = ViewerChromePolicy.filmstripShown(true, false, true, rollSize = 9)
        assertTrue(beforeScrub)
        assertEquals(beforeScrub, afterScrubOntoVideo)
    }

    @Test
    fun `the position chip follows the same chrome rule and also gives way to the lift editor`() {
        assertTrue(ViewerChromePolicy.chromeShown(chromeVisible = true, inPictureInPicture = false))
        assertFalse(ViewerChromePolicy.chromeShown(chromeVisible = false, inPictureInPicture = false))
        assertFalse(ViewerChromePolicy.chromeShown(chromeVisible = true, inPictureInPicture = true))
        assertFalse(ViewerChromePolicy.chromeShown(chromeVisible = true, inPictureInPicture = false, lifting = true))
    }

    // ── the video transport clears the strip ───────────────────────────────────────────────────

    @Test
    fun `the video transport sits exactly one resting band above the bottom edge when the strip is up`() {
        assertEquals(FilmstripGeometry.REST_BAND_DP, ViewerChromePolicy.videoBottomInsetDp(filmstripShown = true))
        assertEquals(0, ViewerChromePolicy.videoBottomInsetDp(filmstripShown = false))
    }

    @Test
    fun `the inset is the resting band, not the bulged one`() {
        // The transport dims and pauses while the strip bulges; reserving the bulged height would
        // leave a 42 dp gap above a resting strip for the sake of a moment it is not at rest.
        assertTrue(ViewerChromePolicy.videoBottomInsetDp(true) < FilmstripGeometry.BULGE_BAND_DP)
    }

    // ── chrome the first time a video opens (#1) ───────────────────────────────────────────────

    @Test
    fun `a video opens with the chrome up and a photo opens immersive`() {
        assertTrue(ViewerChromePolicy.initialChromeVisible(isVideo = true))
        assertFalse(ViewerChromePolicy.initialChromeVisible(isVideo = false))
    }

    @Test
    fun `only the first video of a session brings the chrome up`() {
        // Opened on a photo, then paged onto the first video: reveal.
        assertTrue(ViewerChromePolicy.revealChromeForVideo(isVideo = true, alreadyRevealed = false))
        // Hid the chrome and paged to the next clip: respect that.
        assertFalse(ViewerChromePolicy.revealChromeForVideo(isVideo = true, alreadyRevealed = true))
        // A photo never reveals it.
        assertFalse(ViewerChromePolicy.revealChromeForVideo(isVideo = false, alreadyRevealed = false))
        assertFalse(ViewerChromePolicy.revealChromeForVideo(isVideo = false, alreadyRevealed = true))
    }

    // ── the slideshow waits for a video ────────────────────────────────────────────────────────

    private fun advances(
        active: Boolean = true,
        total: Int = 9,
        room: Boolean = false,
        video: Boolean = false,
        lifting: Boolean = false,
    ) = ViewerChromePolicy.slideshowMayAdvance(active, total, room, video, lifting)

    @Test
    fun `the slideshow advances only when nothing is holding it`() {
        assertTrue(advances())
        assertFalse("not started", advances(active = false))
        assertFalse("one photo", advances(total = 1))
        assertFalse("a room is open", advances(room = true))
        assertFalse("a video is playing", advances(video = true))
        assertFalse("a selection is being made", advances(lifting = true))
    }

    @Test
    fun `a finished or paused video lets the countdown start again`() {
        // VideoPlayer reports playback inactive on pause and on end; the screen's effect is keyed
        // on that value, so the false -> countdown restart is the whole mechanism.
        assertFalse(advances(video = true))
        assertTrue(advances(video = false))
    }
}
