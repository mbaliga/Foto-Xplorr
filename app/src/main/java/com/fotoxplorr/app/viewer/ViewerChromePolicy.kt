package com.fotoxplorr.app.viewer

/**
 * The viewer's "what is drawn, and when" rules, as pure functions.
 *
 * [ViewerScreen] used to hold these as inline boolean expressions in the middle of a thousand-line
 * composable, which is how `!asset.isVideo && chromeVisible && ...` came to gate the filmstrip on
 * the type of the photo and nobody could see the decision without reading the layout around it.
 * Each rule lives here instead, named for the decision it makes, so a JVM test can pin it
 * (`ViewerChromePolicyTest`) and the screen reads as a list of decisions rather than a pile of
 * conditions.
 *
 * Nothing in this object touches Compose, Android or a clock.
 */
internal object ViewerChromePolicy {

    /**
     * Whether the chrome drawn over the photo (the position chip with its Close button) is up.
     *
     * Hidden in picture-in-picture, where the whole window is a small video and a Close button in
     * its corner would be a control for a window nobody can reach, and hidden while the lift
     * editor owns the screen, which brings toolbars of its own.
     */
    fun chromeShown(chromeVisible: Boolean, inPictureInPicture: Boolean, lifting: Boolean = false): Boolean =
        chromeVisible && !inPictureInPicture && !lifting

    /**
     * Whether the filmstrip is drawn.
     *
     * **Deliberately not a function of the current asset.** The strip is roll-level navigation,
     * and it DRIVES the viewer live: the moment a different thumbnail is nearest the centre the
     * host swaps the current asset. If the strip were gated on the type of that asset, scrubbing
     * onto a video would remove the strip from under the finger and cancel the drag -- which is
     * exactly what the owner reported (*"When I scrub through the filmstrip and stop on a video,
     * the video player progress bar etc. replaces the filmstrip so scrubbing through is no longer
     * possible"*). There is no `isVideo` parameter here so that nobody can reintroduce the gate
     * without changing this signature, and `ViewerChromePolicyTest` pins that shape.
     */
    fun filmstripShown(
        chromeVisible: Boolean,
        inPictureInPicture: Boolean,
        showFilmstripPreference: Boolean,
        rollSize: Int,
        lifting: Boolean = false,
    ): Boolean = chromeShown(chromeVisible, inPictureInPicture, lifting) && showFilmstripPreference && rollSize > 1

    /**
     * How far above the bottom edge the video transport must sit so it clears the filmstrip: the
     * strip's resting band when it is shown, nothing when it is not. Derived from the strip's own
     * constant so the two cannot drift apart. The bulge (the band growing while a finger is on it)
     * is NOT added: the transport dims and pauses while the strip is being scrubbed instead, so
     * the taller band is allowed to cover it.
     */
    fun videoBottomInsetDp(filmstripShown: Boolean): Int =
        if (filmstripShown) FilmstripGeometry.REST_BAND_DP else 0

    // ── chrome the first time a video opens (owner decision) ──────────────────────────────────

    /**
     * Whether the chrome is already up when the viewer first composes for an asset. Up for a
     * video, down for a photo: immersive stays the default for stills (owner, 2026-08-14:
     * "Immersive! Immersive! Immersive!"), but a video opened into a bare black screen with no
     * visible way to start, pause or seek it is what read as "Fotoz is not playing videos"
     * (owner, #1). Showing the transport the first time a video opens is the owner's decision;
     * after that it is theirs to hide with a tap.
     */
    fun initialChromeVisible(isVideo: Boolean): Boolean = revealChromeForVideo(isVideo, alreadyRevealed = false)

    /**
     * Whether landing on this asset should bring the chrome up: a video, and only the FIRST one
     * this viewer session shows. [alreadyRevealed] is remembered by the screen for its lifetime, so
     * a person who hides the chrome and pages to the next clip is not shown it again against their
     * wishes, while one who opened on a photo still meets the transport the first time a video
     * turns up.
     */
    fun revealChromeForVideo(isVideo: Boolean, alreadyRevealed: Boolean): Boolean = isVideo && !alreadyRevealed

    // ── the slideshow waits for a video ───────────────────────────────────────────────────────

    /**
     * Whether the slideshow's countdown to the next photo should be running.
     *
     * Stopped while any room is open (the person is reading something), while the lift editor owns
     * the screen (an advance would throw a half-made selection away), and while a video is playing
     * or about to (an advance after the interval would cut it off mid-scene). When the video ends,
     * or is paused, the countdown starts afresh, so a clip plays out and then gets its normal
     * interval before the slideshow moves on.
     */
    fun slideshowMayAdvance(
        slideshowActive: Boolean,
        total: Int,
        anyRoomVisible: Boolean,
        videoPlaybackActive: Boolean,
        lifting: Boolean,
    ): Boolean = slideshowActive && total > 1 && !anyRoomVisible && !videoPlaybackActive && !lifting
}
