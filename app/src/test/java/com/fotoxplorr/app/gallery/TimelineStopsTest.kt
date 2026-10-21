package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * The edge scrubber's stops.
 *
 * These are the labels a finger lands on while sweeping 21,000 photos, and the indices the grid
 * jumps to when it does. Both have to be right together: a label placed at an index that is not
 * where that month starts makes the whole strip a lie, and it is the kind of lie nobody reports
 * as a bug — the scrubber just feels vaguely untrustworthy and people stop using it.
 */
class TimelineStopsTest {

    private val utc = ZoneOffset.UTC
    private val english = Locale.UK

    private fun asset(
        id: Long,
        year: Int,
        month: Int,
        day: Int = 1,
        takenMillis: Long? = null,
        modifiedSeconds: Long? = null,
    ): MediaAsset {
        val millis = LocalDateTime.of(year, month, day, 12, 0).toInstant(utc).toEpochMilli()
        return MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/$id",
            displayName = "item-$id.jpg",
            mimeType = "image/jpeg",
            bucketName = "Camera",
            dateTakenMillis = takenMillis ?: millis,
            dateModifiedSeconds = modifiedSeconds ?: (millis / 1000L),
            width = 100,
            height = 100,
            sizeBytes = 1_000L,
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun stops(assets: List<MediaAsset>) = timelineStops(assets, utc, english)

    @Test
    fun `an empty library has no stops`() {
        // The scrubber refuses to draw for an empty list; handing it stops for one would be a
        // live-looking affordance over nothing.
        assertTrue(stops(emptyList()).isEmpty())
    }

    @Test
    fun `each month change starts exactly one stop, at its first item`() {
        val assets = listOf(
            asset(1, 2024, 3), asset(2, 2024, 3), asset(3, 2024, 3),
            asset(4, 2024, 2),
            asset(5, 2024, 1), asset(6, 2024, 1),
        )
        val result = stops(assets)
        assertEquals(listOf(0, 3, 4), result.map { it.itemIndex })
        assertEquals(listOf("Mar", "Feb", "Jan"), result.map { it.label })
    }

    @Test
    fun `a run of one month is one stop however long it is`() {
        // The property the strip depends on: stops mark boundaries, so a month with 400 photos
        // occupies 400 items of travel and exactly one label — which is what makes position on
        // the strip mean "how far through the library", not "how far through the calendar".
        val assets = (1..400).map { asset(it.toLong(), 2024, 5, day = 1 + it % 28) }
        assertEquals(1, stops(assets).size)
        assertEquals(0, stops(assets).first().itemIndex)
    }

    @Test
    fun `the year gets its own stop when it changes`() {
        // "Mar 2024" is too wide for a 44dp strip and would ellipsize into "Mar…". So a year
        // boundary is labelled with the year alone, and the months around it stay months.
        val assets = listOf(
            asset(1, 2024, 2),
            asset(2, 2024, 1),
            asset(3, 2023, 12),
            asset(4, 2023, 11),
        )
        assertEquals(listOf("Feb", "Jan", "2023", "Nov"), stops(assets).map { it.label })
    }

    @Test
    fun `the first stop is a month, not a year`() {
        // Nothing preceded it, so there is no year change to announce — labelling the top of
        // the strip "2024" would waste the one stop the eye lands on first.
        assertEquals("Jul", stops(listOf(asset(1, 2024, 7))).single().label)
    }

    @Test
    fun `an oldest-first library reads forwards`() {
        val assets = listOf(asset(1, 2023, 11), asset(2, 2023, 12), asset(3, 2024, 1))
        assertEquals(listOf("Nov", "Dec", "2024"), stops(assets).map { it.label })
    }

    @Test
    fun `a missing capture date falls back to the file's modification time`() {
        // MediaStore leaves dateTaken at 0 for screenshots, downloads and anything copied from
        // a computer — a large share of a real library. Without the fallback every one of them
        // would land on January 1970 and collapse the timeline into a single giant stop.
        val modified = LocalDateTime.of(2022, 9, 4, 8, 0).toInstant(utc).toEpochMilli() / 1000L
        val assets = listOf(asset(1, 2024, 5), asset(2, 1970, 1, takenMillis = 0L, modifiedSeconds = modified))
        assertEquals(listOf("May", "2022"), stops(assets).map { it.label })
    }

    @Test
    fun `stops are strictly ascending by index`() {
        // The scrubber scans stops from the end to find the one an index belongs to, which is
        // only meaningful for an ascending list.
        val assets = (0..60).map { asset(it.toLong(), 2024 - it / 12, 1 + it % 12) }
        val indices = stops(assets).map { it.itemIndex }
        assertEquals(indices.sorted(), indices)
        assertEquals(indices.distinct(), indices)
    }

    @Test
    fun `the month readout names the month in full`() {
        assertEquals("March 2024", monthLabel(asset(1, 2024, 3), utc, english))
    }

    // ---- the strip and the grid's month headers must agree on where a month starts ----

    // Dated photos across four months, plus two with no capture date whose files were modified in
    // months the dated ones also cover: the sort puts the undated ones together at one end, so a
    // month there heads a second stretch.
    private val mixed = listOf(
        asset(1, 2024, 3, 20),
        asset(2, 2024, 3, 2),
        asset(3, 2024, 2, 11),
        asset(4, 2023, 12, 25),
        asset(5, 1970, 1, takenMillis = 0L, modifiedSeconds = seconds(2024, 3, 15)),
        asset(6, 1970, 1, takenMillis = 0L, modifiedSeconds = seconds(2022, 8, 4)),
    )

    private fun seconds(year: Int, month: Int, day: Int) =
        LocalDateTime.of(year, month, day, 12, 0).toInstant(utc).toEpochMilli() / 1000L

    private fun runStarts(runs: List<TimelineGroup>): List<Int> =
        runs.runningFold(0) { start, run -> start + run.assets.size }.dropLast(1)

    @Test
    fun `every stop opens a month header and every month header has a stop`() {
        for (sort in listOf(GallerySort.NEWEST, GallerySort.OLDEST)) {
            val ordered = sortAssets(mixed, sort)
            val runs = timelineRuns(ordered, TimelineGrouping.MONTH, utc, english)
            assertEquals(sort.name, runStarts(runs), stops(ordered).map { it.itemIndex })
        }
    }

    @Test
    fun `a stop names the month of the header it opens, undated photos included`() {
        for (sort in listOf(GallerySort.NEWEST, GallerySort.OLDEST)) {
            val ordered = sortAssets(mixed, sort)
            val runs = timelineRuns(ordered, TimelineGrouping.MONTH, utc, english)
            runStarts(runs).forEachIndexed { index, start ->
                assertEquals(
                    "$sort run $index",
                    monthLabel(ordered[start], utc, english),
                    runs[index].title,
                )
            }
        }
        // The undated photo that sorts last is a 2024 or 2022 header, never a 1970 one.
        val newest = timelineRuns(sortAssets(mixed, GallerySort.NEWEST), TimelineGrouping.MONTH, utc, english)
        assertTrue(newest.none { it.title.contains("1970") })
    }

    @Test
    fun `a stop opens a header with day grouping too, because a new month is a new day`() {
        val ordered = sortAssets(mixed, GallerySort.NEWEST)
        val days = timelineRuns(ordered, TimelineGrouping.DAY, utc, english)
        val starts = runStarts(days).toSet()
        assertTrue(stops(ordered).all { it.itemIndex in starts })
    }

    @Test
    fun `the scrubber lands on the header row above the stop's first photo`() {
        // GalleryScreen's own remapping: the stop's asset index through GridIndexMap, minus the
        // header row that sits just above it.
        val ordered = sortAssets(mixed, GallerySort.OLDEST)
        val runs = timelineRuns(ordered, TimelineGrouping.MONTH, utc, english)
        val map = com.fotoxplorr.core.organize.GridIndexMap(
            groupSizes = runs.map { it.assets.size },
            hasHeaders = true,
            trailingFooter = true,
        )
        // Header k is preceded by the k earlier headers and all their photos.
        val headerRows = runStarts(runs).mapIndexed { k, start -> start + k }
        val landed = stops(ordered).map { map.gridIndexOf(it.itemIndex) - 1 }
        assertEquals(headerRows, landed)
    }
}
