package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull zone's reserve follows the transport that is actually drawn: where the second row is
 * folded behind its toggle, the zone drops by that row and the gap above it.
 */
class ViewerBottomReserveFoldedTest {

    @Test
    fun `a folded transport is the expanded band less the second row and its gap`() {
        assertEquals(96, ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP)
        // Padding 14 and one 36dp row.
        assertEquals(50, ViewerBottomReserve.VIDEO_TRANSPORT_FOLDED_BAND_DP)
        assertEquals(
            ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP - (TRANSPORT_ROW_DP + TRANSPORT_GAP_DP).toInt(),
            ViewerBottomReserve.VIDEO_TRANSPORT_FOLDED_BAND_DP,
        )
    }

    @Test
    fun `a folded transport alone reserves the folded band`() {
        assertEquals(
            ViewerBottomReserve.VIDEO_TRANSPORT_FOLDED_BAND_DP,
            ViewerBottomReserve.reserveDp(filmstripShown = false, videoTransportShown = true, secondRowCollapsed = true),
        )
    }

    @Test
    fun `a folded transport over the filmstrip stacks on the strip`() {
        assertEquals(
            FilmstripGeometry.REST_BAND_DP + ViewerBottomReserve.VIDEO_TRANSPORT_FOLDED_BAND_DP,
            ViewerBottomReserve.reserveDp(filmstripShown = true, videoTransportShown = true, secondRowCollapsed = true),
        )
    }

    @Test
    fun `folding changes nothing when no transport is shown`() {
        assertEquals(0, ViewerBottomReserve.reserveDp(false, false, secondRowCollapsed = true))
        assertEquals(
            FilmstripGeometry.REST_BAND_DP,
            ViewerBottomReserve.reserveDp(true, false, secondRowCollapsed = true),
        )
    }

    @Test
    fun `an unfolded transport keeps the expanded band`() {
        assertEquals(
            ViewerBottomReserve.VIDEO_TRANSPORT_BAND_DP,
            ViewerBottomReserve.reserveDp(false, true, secondRowCollapsed = false),
        )
    }

    @Test
    fun `the fold decision is the transport's own at a short and a tall window`() {
        // 360dp tall in landscape with the strip: inset 74 + 14 + 72 + 10 = 170 > 40% of 360.
        assertTrue(ViewerBottomReserve.secondRowCollapsed(filmstripShown = true, screenHeightDp = 360))
        assertEquals(
            shouldCollapseSecondRow(360, ViewerChromePolicy.videoBottomInsetDp(true).toFloat()),
            ViewerBottomReserve.secondRowCollapsed(filmstripShown = true, screenHeightDp = 360),
        )
        // A tall portrait phone has room for both rows.
        assertFalse(ViewerBottomReserve.secondRowCollapsed(filmstripShown = true, screenHeightDp = 800))
    }

    @Test
    fun `with no strip the transport never folds`() {
        // shouldCollapseSecondRow only folds a transport that stacks above the filmstrip.
        assertFalse(ViewerBottomReserve.secondRowCollapsed(filmstripShown = false, screenHeightDp = 300))
    }
}
