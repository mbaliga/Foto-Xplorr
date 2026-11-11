package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The contiguous-run grouper behind the stamp view's month headers: runs follow the order the sort
 * chose, a month that comes back later heads a second run, and no two headers ever share a key.
 */
class TimelineRunsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun asset(id: Long, date: String, hour: Int = 12, modifiedOnly: Boolean = false): MediaAsset {
        val millis = LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/images/media/$id",
            displayName = "IMG_$id.jpg",
            mimeType = "image/jpeg",
            bucketName = "Camera",
            bucketId = 100L,
            dateTakenMillis = if (modifiedOnly) 0L else millis,
            dateModifiedSeconds = millis / 1_000L,
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun months(assets: List<MediaAsset>) = timelineRuns(assets, TimelineGrouping.MONTH, utc, Locale.ENGLISH)

    @Test
    fun `photos in one month make one run headed by that month`() {
        val runs = months(listOf(asset(1, "2026-09-20"), asset(2, "2026-09-02")))
        assertEquals(1, runs.size)
        assertEquals("September 2026", runs[0].title)
        assertEquals(listOf(1L, 2L), runs[0].assets.map { it.id.value })
    }

    @Test
    fun `a new month starts a new run and the order is kept`() {
        val runs = months(
            listOf(asset(1, "2026-09-20"), asset(2, "2026-08-31"), asset(3, "2026-08-01"), asset(4, "2026-07-15")),
        )
        assertEquals(listOf("September 2026", "August 2026", "July 2026"), runs.map { it.title })
        assertEquals(listOf(1L, 2L, 3L, 4L), runs.flatMap { it.assets }.map { it.id.value })
    }

    @Test
    fun `oldest first is not re-sorted into newest first`() {
        val runs = months(listOf(asset(1, "2026-07-15"), asset(2, "2026-08-01"), asset(3, "2026-09-20")))
        assertEquals(listOf("July 2026", "August 2026", "September 2026"), runs.map { it.title })
    }

    @Test
    fun `a month that comes back later heads a second run with a different key`() {
        // NAME or SIZE order interleaves months; the grouper must not merge or reorder them.
        val runs = months(
            listOf(asset(1, "2026-09-20"), asset(2, "2026-08-01"), asset(3, "2026-09-02"), asset(4, "2026-09-03")),
        )
        assertEquals(listOf("September 2026", "August 2026", "September 2026"), runs.map { it.title })
        assertEquals(listOf(1, 1, 2), runs.map { it.assets.size })
        assertEquals(runs.size, runs.map { it.key }.toSet().size)
    }

    @Test
    fun `keys carry the run index and the month`() {
        val runs = months(listOf(asset(1, "2026-09-20"), asset(2, "2026-08-01"), asset(3, "2026-09-02")))
        assertEquals(listOf("run:0:2026-09", "run:1:2026-08", "run:2:2026-09"), runs.map { it.key })
    }

    @Test
    fun `a photo without a taken date sits where its modified time puts it`() {
        val runs = months(listOf(asset(1, "2026-09-20"), asset(2, "2026-09-05", modifiedOnly = true)))
        assertEquals(1, runs.size)
        val oldFile = months(listOf(asset(1, "2026-09-20"), asset(2, "2026-03-05", modifiedOnly = true)))
        assertEquals(listOf("September 2026", "March 2026"), oldFile.map { it.title })
    }

    @Test
    fun `the zone decides which month a late night photo is in`() {
        val lateNight = asset(1, "2026-09-30", hour = 23)
        val inUtc = timelineRuns(listOf(lateNight), TimelineGrouping.MONTH, utc, Locale.ENGLISH)
        val inTokyo = timelineRuns(listOf(lateNight), TimelineGrouping.MONTH, ZoneId.of("Asia/Tokyo"), Locale.ENGLISH)
        assertEquals("September 2026", inUtc[0].title)
        assertEquals("October 2026", inTokyo[0].title)
    }

    @Test
    fun `day runs cut on the day and keep the order`() {
        val runs = timelineRuns(
            listOf(asset(1, "2026-09-20"), asset(2, "2026-09-20", hour = 8), asset(3, "2026-09-19")),
            TimelineGrouping.DAY, utc, Locale.ENGLISH,
        )
        assertEquals(listOf(2, 1), runs.map { it.assets.size })
        assertEquals("run:0:2026-09-20", runs[0].key)
        assertEquals("Sunday, 20 September 2026", runs[0].title)
    }

    @Test
    fun `no grouping is one group holding the list itself`() {
        val assets = listOf(asset(1, "2026-09-20"), asset(2, "2026-08-01"))
        val runs = timelineRuns(assets, TimelineGrouping.NONE, utc, Locale.ENGLISH)
        assertEquals(1, runs.size)
        assertSame(assets, runs[0].assets)
    }

    @Test
    fun `an empty list has no runs`() {
        assertTrue(months(emptyList()).isEmpty())
    }

    @Test
    fun `every photo lands in exactly one run`() {
        val assets = SyntheticCatalogue.assets(2_000)
        val runs = timelineRuns(assets, TimelineGrouping.MONTH, utc, Locale.ENGLISH)
        assertEquals(assets.map { it.id }, runs.flatMap { it.assets }.map { it.id })
        assertEquals(runs.size, runs.map { it.key }.toSet().size)
    }

    // ---- when the headers are drawn ----

    @Test
    fun `only the stamp rung in a date order has headers`() {
        assertTrue(stampHeadersShown(7, GallerySort.NEWEST, masonry = false))
        assertTrue(stampHeadersShown(7, GallerySort.OLDEST, masonry = false))
        assertFalse(stampHeadersShown(7, GallerySort.NAME, masonry = false))
        assertFalse(stampHeadersShown(7, GallerySort.SIZE, masonry = false))
        assertFalse(stampHeadersShown(7, GallerySort.NEWEST, masonry = true))
        for (rung in 1..6) assertFalse(stampHeadersShown(rung, GallerySort.NEWEST, masonry = false))
    }

    @Test
    fun `a headed layout counts a header per run and a plain one is the list as it came`() {
        val assets = listOf(asset(1, "2026-09-20"), asset(2, "2026-08-01"), asset(3, "2026-08-02"))
        val headed = zoomLayoutFor(assets, headers = true, zoneId = utc, locale = Locale.ENGLISH)
        assertTrue(headed.hasHeaders)
        assertEquals(2, headed.groups.size)
        assertEquals(assets, headed.renderedAssets)

        val plain = zoomLayoutFor(assets, headers = false, zoneId = utc, locale = Locale.ENGLISH)
        assertFalse(plain.hasHeaders)
        assertSame(assets, plain.renderedAssets)
    }

    @Test
    fun `every scrubber stop opens a run so a stop can target the header above it`() {
        val assets = sortAssets(SyntheticCatalogue.assets(2_000), GallerySort.NEWEST)
        val runStarts = months(assets).runningFold(0) { start, run -> start + run.assets.size }.toSet()
        val stops = timelineStops(assets, utc, Locale.ENGLISH)
        assertTrue(stops.isNotEmpty())
        stops.forEach { assertTrue("stop at ${it.itemIndex} is not a run start", it.itemIndex in runStarts) }
    }

    @Test
    fun `the column rungs' layout follows the grouping and the stamps' stays months`() {
        val assets = listOf(asset(1, "2026-09-20"), asset(2, "2026-09-02"), asset(3, "2026-08-31"))
        val days = zoomLayoutFor(
            assets, headers = true, grouping = TimelineGrouping.DAY, zoneId = utc, locale = Locale.ENGLISH,
        )
        assertEquals(3, days.groups.size)
        assertEquals(TimelineGrouping.DAY, days.grouping)

        val months = zoomLayoutFor(assets, headers = true, zoneId = utc, locale = Locale.ENGLISH)
        assertEquals(listOf("September 2026", "August 2026"), months.groups.map { it.title })
        assertEquals(TimelineGrouping.MONTH, months.grouping)
    }

    @Test
    fun `no grouping is a plain layout even when headers were asked for`() {
        val assets = listOf(asset(1, "2026-09-20"), asset(2, "2026-08-31"))
        val layout = zoomLayoutFor(
            assets, headers = true, grouping = TimelineGrouping.NONE, zoneId = utc, locale = Locale.ENGLISH,
        )
        assertFalse(layout.hasHeaders)
        assertEquals(assets, layout.renderedAssets)
    }
}
