package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import dev.aarso.cellshell.ScrubberEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline's layout rules (owner #25), as plain functions: where the strip is, when it is
 * there, how much room the grid leaves for it, and which end of the list the grid opens at. They
 * are the parts that are easy to get subtly wrong on a screen nobody can test by eye here.
 */
class TimelineLayoutTest {

    private fun shown(
        on: Boolean = true,
        sort: GallerySort = GallerySort.NEWEST,
        masonry: Boolean = false,
        map: Boolean = false,
        pane: Boolean = false,
        stops: Boolean = true,
    ) = stripShouldShow(on, sort, masonry, map, pane, stops)

    @Test
    fun `the strip shows for a date order on the plain grid`() {
        assertTrue(shown())
        assertTrue(shown(sort = GallerySort.OLDEST))
    }

    @Test
    fun `the strip is hidden when the timeline is off`() {
        assertFalse(shown(on = false))
    }

    @Test
    fun `the strip is hidden for name and size order`() {
        assertFalse(shown(sort = GallerySort.NAME))
        assertFalse(shown(sort = GallerySort.SIZE))
    }

    @Test
    fun `the strip is hidden in masonry, on the map, over a pane and with nothing to scrub`() {
        assertFalse(shown(masonry = true))
        assertFalse(shown(map = true))
        assertFalse(shown(pane = true))
        assertFalse(shown(stops = false))
    }

    @Test
    fun `the reserve is the strip's thickness on its own side only`() {
        assertEquals(TimelineReserve(start = 44), timelineReserve(true, TimelinePlacement.LEFT))
        assertEquals(TimelineReserve(end = 44), timelineReserve(true, TimelinePlacement.RIGHT))
        assertEquals(TimelineReserve(top = 44), timelineReserve(true, TimelinePlacement.TOP))
        assertEquals(TimelineReserve(bottom = 44), timelineReserve(true, TimelinePlacement.BOTTOM))
    }

    @Test
    fun `no strip means no reserve on any side`() {
        for (placement in TimelinePlacement.entries) {
            assertTrue(timelineReserve(false, placement).isEmpty)
        }
        for (placement in TimelinePlacement.entries) {
            assertFalse(timelineReserve(true, placement).isEmpty)
        }
    }

    @Test
    fun `only a bottom strip lifts the floating controls`() {
        assertEquals(44, floatingChromeLift(true, TimelinePlacement.BOTTOM))
        assertEquals(0, floatingChromeLift(false, TimelinePlacement.BOTTOM))
        for (placement in listOf(TimelinePlacement.LEFT, TimelinePlacement.RIGHT, TimelinePlacement.TOP)) {
            assertEquals(0, floatingChromeLift(true, placement))
        }
    }

    @Test
    fun `only a top strip drops the route bar and not under the search field`() {
        assertEquals(44, routeBarDrop(true, TimelinePlacement.TOP, searchVisible = false))
        assertEquals(0, routeBarDrop(true, TimelinePlacement.TOP, searchVisible = true))
        assertEquals(0, routeBarDrop(false, TimelinePlacement.TOP, searchVisible = false))
        assertEquals(0, routeBarDrop(true, TimelinePlacement.BOTTOM, searchVisible = false))
    }

    @Test
    fun `a selection keeps a side strip and hides one along the top or bottom`() {
        assertTrue(stripDrawnWhileSelecting(TimelinePlacement.LEFT))
        assertTrue(stripDrawnWhileSelecting(TimelinePlacement.RIGHT))
        assertFalse(stripDrawnWhileSelecting(TimelinePlacement.TOP))
        assertFalse(stripDrawnWhileSelecting(TimelinePlacement.BOTTOM))
    }

    @Test
    fun `each placement is the library edge of the same name`() {
        assertEquals(ScrubberEdge.LEFT, TimelinePlacement.LEFT.edge())
        assertEquals(ScrubberEdge.RIGHT, TimelinePlacement.RIGHT.edge())
        assertEquals(ScrubberEdge.TOP, TimelinePlacement.TOP.edge())
        assertEquals(ScrubberEdge.BOTTOM, TimelinePlacement.BOTTOM.edge())
    }

    // ---- which end the grid opens at ----

    @Test
    fun `the latest photo is at the end only for oldest first`() {
        assertTrue(latestAtEnd(GallerySort.OLDEST))
        assertFalse(latestAtEnd(GallerySort.NEWEST))
        assertFalse(latestAtEnd(GallerySort.NAME))
        assertFalse(latestAtEnd(GallerySort.SIZE))
    }

    @Test
    fun `the anchor is the first item, or the last when the latest is at the end`() {
        assertEquals(0, anchorIndex(itemCount = 500, latestAtEnd = false))
        assertEquals(499, anchorIndex(itemCount = 500, latestAtEnd = true))
    }

    @Test
    fun `an empty grid anchors at zero either way`() {
        assertEquals(0, anchorIndex(0, latestAtEnd = true))
        assertEquals(0, anchorIndex(0, latestAtEnd = false))
    }

    @Test
    fun `the anchor waits for photos and for the scan when the end matters`() {
        assertFalse(anchorReady(hasPhotos = false, scanning = false))
        assertFalse(anchorReady(hasPhotos = true, scanning = true))
        assertTrue(anchorReady(hasPhotos = true, scanning = false))
    }

    @Test
    fun `the anchor token changes with the destination, route, sort and request`() {
        val base = anchorToken("PHOTOS", "Root", GallerySort.NEWEST, 0)
        assertEquals(base, anchorToken("PHOTOS", "Root", GallerySort.NEWEST, 0))
        assertTrue(base != anchorToken("VIDEOS", "Root", GallerySort.NEWEST, 0))
        assertTrue(base != anchorToken("PHOTOS", "Tag(tag=a)", GallerySort.NEWEST, 0))
        assertTrue(base != anchorToken("PHOTOS", "Root", GallerySort.OLDEST, 0))
        assertTrue(base != anchorToken("PHOTOS", "Root", GallerySort.NEWEST, 1))
    }

    // ---- the words ----

    @Test
    fun `the date orders say where the latest photo sits on a vertical grid`() {
        assertEquals("Latest at top", timelineSortLabel(GallerySort.NEWEST))
        assertEquals("Latest at bottom", timelineSortLabel(GallerySort.OLDEST))
    }

    @Test
    fun `the date orders say left and right on a horizontal grid`() {
        assertEquals("Latest at left", timelineSortLabel(GallerySort.NEWEST, horizontal = true))
        assertEquals("Latest at right", timelineSortLabel(GallerySort.OLDEST, horizontal = true))
    }

    @Test
    fun `name and size keep their plain words on either axis`() {
        for (horizontal in listOf(false, true)) {
            assertEquals("By name", timelineSortLabel(GallerySort.NAME, horizontal))
            assertEquals("Largest first", timelineSortLabel(GallerySort.SIZE, horizontal))
        }
    }

    // ---- the headers ----

    @Test
    fun `the column rungs have headers only while the timeline is on in a date order`() {
        fun headers(
            rung: Int = 3,
            sort: GallerySort = GallerySort.NEWEST,
            masonry: Boolean = false,
            on: Boolean = true,
            grouping: TimelineGrouping = TimelineGrouping.MONTH,
        ) = headersShown(rung, sort, masonry, on, grouping)

        assertTrue(headers())
        assertTrue(headers(sort = GallerySort.OLDEST, grouping = TimelineGrouping.DAY))
        assertFalse(headers(on = false))
        assertFalse(headers(grouping = TimelineGrouping.NONE))
        assertFalse(headers(sort = GallerySort.NAME))
        assertFalse(headers(sort = GallerySort.SIZE))
        assertFalse(headers(masonry = true))
    }

    @Test
    fun `the stamp rung has headers whatever the timeline says, flat for name and size`() {
        assertTrue(headersShown(ZOOM_RUNG_STAMPS, GallerySort.NEWEST, false, false, TimelineGrouping.NONE))
        assertTrue(headersShown(ZOOM_RUNG_STAMPS, GallerySort.OLDEST, false, false, TimelineGrouping.DAY))
        assertFalse(headersShown(ZOOM_RUNG_STAMPS, GallerySort.NAME, false, true, TimelineGrouping.MONTH))
    }

    @Test
    fun `the stamps always group by month and the columns follow the setting`() {
        assertEquals(TimelineGrouping.MONTH, headerGrouping(ZOOM_RUNG_STAMPS, TimelineGrouping.DAY))
        assertEquals(TimelineGrouping.DAY, headerGrouping(3, TimelineGrouping.DAY))
        assertEquals(TimelineGrouping.MONTH, headerGrouping(3, TimelineGrouping.MONTH))
    }
}
