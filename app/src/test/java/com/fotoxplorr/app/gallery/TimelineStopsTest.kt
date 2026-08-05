package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
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
}
