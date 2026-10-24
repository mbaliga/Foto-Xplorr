package com.fotoxplorr.app.viewer

/**
 * How much of the viewer's bottom edge is already taken by something that scrubs HORIZONTALLY, so
 * the spatial shell's bottom pull zone (the strip that opens the actions room) starts above it.
 *
 * Without this the zone sits on the filmstrip or the video transport, and a drag that starts
 * mostly upward on either of them opens the actions room instead of moving the strip or the seek
 * bar. The shell adds the navigation bar itself, so these are the heights above it.
 */
object ViewerBottomReserve {

    /**
     * The video transport's band above the navigation bar and above any filmstrip: its padding, the
     * two button rows and the gap between them. This is the EXPANDED height; where the second row
     * is folded behind its toggle ([shouldCollapseSecondRow], short windows) the band is
     * [VIDEO_TRANSPORT_FOLDED_BAND_DP] instead, so the zone sits on the transport that is really
     * drawn rather than floating above it over the picture.
     * (A key-moment bar or a status line above the rows can add to it for a moment; not counted.)
     */
    const val VIDEO_TRANSPORT_BAND_DP: Int =
        (TRANSPORT_PADDING_DP + 2 * TRANSPORT_ROW_DP + TRANSPORT_GAP_DP).toInt()

    /**
     * The transport's band with its second row folded: the expanded band less that row and the gap
     * above it. A person who unfolds the row in a short window gets a zone that starts inside it;
     * that row holds buttons, not a scrubber, so it is the lesser evil next to a permanently
     * floating zone, and the seek bar in the first row stays clear.
     */
    const val VIDEO_TRANSPORT_FOLDED_BAND_DP: Int =
        VIDEO_TRANSPORT_BAND_DP - (TRANSPORT_ROW_DP + TRANSPORT_GAP_DP).toInt()

    /**
     * Whether the transport folds its second row on this window: the very decision the transport
     * makes (it calls the same function with the same strip inset), so the two cannot disagree.
     *
     * @param screenHeightDp the window's height, as the transport reads it.
     */
    fun secondRowCollapsed(filmstripShown: Boolean, screenHeightDp: Int): Boolean =
        shouldCollapseSecondRow(screenHeightDp, ViewerChromePolicy.videoBottomInsetDp(filmstripShown).toFloat())

    /**
     * The reserve, in dp, for the viewer's current chrome.
     *
     * The filmstrip contributes its resting band when it is shown (its bulge, while a finger is on
     * it, only grows over the zone and the finger is already on the strip). A video's transport
     * stacks above the strip ([ViewerChromePolicy.videoBottomInsetDp]), so when both are up the two
     * add, and when there is no strip the transport alone is the reserve. A photo with no strip, or
     * chrome that is hidden, reserves nothing and the zone keeps its place at the screen edge.
     *
     * @param filmstripShown [ViewerChromePolicy.filmstripShown].
     * @param videoTransportShown a video is up and its chrome is showing
     *   ([ViewerChromePolicy.chromeShown]).
     * @param secondRowCollapsed the transport has folded its second row ([secondRowCollapsed]);
     *   only matters while the transport is shown.
     */
    fun reserveDp(filmstripShown: Boolean, videoTransportShown: Boolean, secondRowCollapsed: Boolean = false): Int {
        val filmstrip = if (filmstripShown) FilmstripGeometry.REST_BAND_DP else 0
        val transport = when {
            !videoTransportShown -> 0
            secondRowCollapsed -> VIDEO_TRANSPORT_FOLDED_BAND_DP
            else -> VIDEO_TRANSPORT_BAND_DP
        }
        return filmstrip + transport
    }
}
