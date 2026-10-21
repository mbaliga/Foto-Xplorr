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
}
