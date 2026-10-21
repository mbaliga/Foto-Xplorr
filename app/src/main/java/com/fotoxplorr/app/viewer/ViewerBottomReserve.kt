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
     * two button rows and the gap between them. This is the expanded height on purpose: the second
     * row folds behind a toggle in a short window, but a person can unfold it, and a zone that is
     * a little high on a folded transport is better than one that starts inside an unfolded one.
     * (A key-moment bar or a status line above the rows can add to it for a moment; not counted.)
     */
    const val VIDEO_TRANSPORT_BAND_DP: Int =
        (TRANSPORT_PADDING_DP + 2 * TRANSPORT_ROW_DP + TRANSPORT_GAP_DP).toInt()

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
     */
    fun reserveDp(filmstripShown: Boolean, videoTransportShown: Boolean): Int {
        val filmstrip = if (filmstripShown) FilmstripGeometry.REST_BAND_DP else 0
        val transport = if (videoTransportShown) VIDEO_TRANSPORT_BAND_DP else 0
        return filmstrip + transport
    }
}
