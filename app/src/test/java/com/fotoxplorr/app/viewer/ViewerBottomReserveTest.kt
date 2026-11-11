package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The viewer's bottom pull-zone reserve: lifted over whatever scrubs sideways along the bottom edge. */
class ViewerBottomReserveTest {

    @Test
    fun `a photo with no strip reserves nothing`() {
        assertEquals(0, ViewerBottomReserve.reserveDp(filmstripShown = false, videoTransportShown = false))
    }

    @Test
    fun `the filmstrip reserves exactly its resting band`() {
        assertEquals(
            FilmstripGeometry.REST_BAND_DP,
            ViewerBottomReserve.reserveDp(filmstripShown = true, videoTransportShown = false),
        )
        // And the same number the transport is lifted by, so the two cannot drift.
        assertEquals(
            ViewerChromePolicy.videoBottomInsetDp(true),
            ViewerBottomReserve.reserveDp(filmstripShown = true, videoTransportShown = false),
        )
    }

    @Test
    fun `a video's transport alone reserves its band`() {
        assertEquals(
            ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP,
            ViewerBottomReserve.reserveDp(filmstripShown = false, videoTransportShown = true),
        )
        // Padding 14, two 36dp rows and a 10dp gap.
        assertEquals(96, ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP)
    }

    @Test
    fun `a video over the filmstrip reserves both, as they stack`() {
        assertEquals(
            FilmstripGeometry.REST_BAND_DP + ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP,
            ViewerBottomReserve.reserveDp(filmstripShown = true, videoTransportShown = true),
        )
    }
}
