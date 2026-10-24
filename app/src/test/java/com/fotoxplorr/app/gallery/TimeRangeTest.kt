package com.fotoxplorr.app.gallery

import androidx.compose.runtime.saveable.SaverScope
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The time range: month snapping, half-open membership, the field it judges on, the slider's
 * domain, its chip label, and surviving process death. All pure; the range is a lens over a
 * projection, so these never touch Compose.
 */
class TimeRangeTest {

    private val utc = ZoneOffset.UTC
    private val us = Locale.US

    private fun millis(year: Int, month: Int, day: Int = 1, hour: Int = 0, zone: ZoneId = utc): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun asset(
        id: Long,
        takenMillis: Long,
        modifiedSeconds: Long = 0L,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/$id",
        displayName = "item-$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = takenMillis,
        dateModifiedSeconds = modifiedSeconds,
        width = 100,
        height = 100,
        sizeBytes = 1_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    // zero-based months, as the calendar's YearMonth has them
    private fun ym(year: Int, month1: Int) = YearMonth(year, month1 - 1)

    // ---- ofMonths ----

    @Test
    fun `a range snaps to the start of its first month and the start of the month after its last`() {
        val range = TimeRange.ofMonths(ym(2023, 3), ym(2024, 9), utc)
        assertEquals(millis(2023, 3), range.fromMillis)
        assertEquals(millis(2024, 10), range.untilMillis)
    }

    @Test
    fun `a range across the end of a year rolls into January`() {
        val range = TimeRange.ofMonths(ym(2023, 11), ym(2023, 12), utc)
        assertEquals(millis(2023, 11), range.fromMillis)
        assertEquals(millis(2024, 1), range.untilMillis)
    }

    @Test
    fun `a range follows the zone's own month boundaries across daylight saving`() {
        val newYork = ZoneId.of("America/New_York")
        // March 2024 starts in standard time (UTC-5) and ends in daylight time, so the month after
        // starts at UTC-4: the two edges are 31 days less an hour apart, not exactly 31 days.
        val range = TimeRange.ofMonths(ym(2024, 3), ym(2024, 3), newYork)
        assertEquals(millis(2024, 3, 1, 0, newYork), range.fromMillis)
        assertEquals(millis(2024, 4, 1, 0, newYork), range.untilMillis)
        assertEquals(31L * 24L * 3_600_000L - 3_600_000L, range.untilMillis - range.fromMillis)
    }

    @Test
    fun `handles given the wrong way round are swapped rather than refused`() {
        assertEquals(
            TimeRange.ofMonths(ym(2023, 3), ym(2024, 9), utc),
            TimeRange.ofMonths(ym(2024, 9), ym(2023, 3), utc),
        )
    }

    // ---- membership ----

    @Test
    fun `a range is half open`() {
        val range = TimeRange.ofMonths(ym(2024, 5), ym(2024, 5), utc)
        assertTrue(millis(2024, 5) in range)
        assertTrue(millis(2024, 5, 31, 23) in range)
        assertFalse("the first instant of June is out", millis(2024, 6) in range)
        assertFalse("the last instant before May is out", millis(2024, 5) - 1 in range)
    }

    @Test
    fun `no range returns the very same list`() {
        val assets = listOf(asset(1, millis(2024, 5)), asset(2, millis(2023, 1)))
        assertSame(assets, assets.inTimeRange(null))
    }

    @Test
    fun `a range keeps what falls inside it and keeps the order`() {
        val inside1 = asset(1, millis(2024, 5, 20))
        val outside = asset(2, millis(2023, 1))
        val inside2 = asset(3, millis(2024, 5, 2))
        val kept = listOf(inside1, outside, inside2)
            .inTimeRange(TimeRange.ofMonths(ym(2024, 5), ym(2024, 5), utc))
        assertEquals(listOf(inside1, inside2), kept)
    }

    @Test
    fun `an item with no date taken is judged on its modified time like the headers do`() {
        // A screenshot: nothing in dateTaken, so the timeline puts it where it was last modified.
        val screenshot = asset(7, takenMillis = 0L, modifiedSeconds = millis(2024, 5, 10) / 1000L)
        val undated = asset(8, takenMillis = 0L, modifiedSeconds = 0L)
        val range = TimeRange.ofMonths(ym(2024, 5), ym(2024, 5), utc)
        assertEquals(listOf(screenshot), listOf(screenshot, undated).inTimeRange(range))
    }

    // ---- the slider's domain ----

    @Test
    fun `nothing dated has no extent`() {
        assertNull(monthExtent(emptyList(), utc))
        assertNull(monthExtent(listOf(asset(1, 0L, 0L)), utc))
    }

    @Test
    fun `one month is its own first and last`() {
        val only = monthExtent(listOf(asset(1, millis(2024, 5, 3)), asset(2, millis(2024, 5, 30))), utc)
        assertEquals(ym(2024, 5) to ym(2024, 5), only)
    }

    @Test
    fun `the extent runs from the oldest month to the newest in any order`() {
        val extent = monthExtent(
            listOf(
                asset(1, millis(2024, 5)),
                asset(2, millis(2021, 11, 30, 23)),
                asset(3, millis(2026, 2)),
                asset(4, 0L, modifiedSeconds = 0L),
            ),
            utc,
        )
        assertEquals(ym(2021, 11) to ym(2026, 2), extent)
    }

    // ---- the chip's label ----

    @Test
    fun `a span reads as first month to last month with both years`() {
        assertEquals(
            "Mar 2023 – Sep 2024",
            TimeRange.ofMonths(ym(2023, 3), ym(2024, 9), utc).label(utc, us),
        )
    }

    @Test
    fun `one month reads as that month`() {
        assertEquals("Mar 2023", TimeRange.ofMonths(ym(2023, 3), ym(2023, 3), utc).label(utc, us))
    }

    @Test
    fun `a whole year reads as the year and whole years as a span of years`() {
        assertEquals("2024", TimeRange.ofMonths(ym(2024, 1), ym(2024, 12), utc).label(utc, us))
        assertEquals("2023 – 2024", TimeRange.ofMonths(ym(2023, 1), ym(2024, 12), utc).label(utc, us))
    }

    // ---- month indices ----

    @Test
    fun `month indices round trip and carry over the year`() {
        for (year in listOf(1999, 2000, 2024)) {
            for (month in 0..11) {
                val original = YearMonth(year, month)
                assertEquals(original, yearMonthOfIndex(original.index))
            }
        }
        assertEquals(ym(2025, 1), ym(2024, 12).plusMonths(1))
        assertEquals(ym(2023, 12), ym(2024, 1).plusMonths(-1))
    }

    // ---- surviving process death ----

    private fun saved(range: TimeRange?): Any? =
        with(TimeRangeSaver) { SaverScope { true }.save(range) }

    @Test
    fun `a range round trips through the saver`() {
        val range = TimeRange.ofMonths(ym(2023, 3), ym(2024, 9), utc)
        assertEquals(range, TimeRangeSaver.restore(saved(range)!!))
    }

    @Test
    fun `no range saves nothing and restores as no range`() {
        // listSaver saves nothing for an empty list, so an unset range leaves no trace in the saved
        // state and rememberSaveable falls back to its initial value, which is no range.
        assertNull(saved(null))
        assertNull(TimeRangeSaver.restore(emptyList<Long>()))
    }

    @Test
    fun `saved state that is not a forward pair restores as no range and never throws`() {
        assertNull(TimeRangeSaver.restore(listOf(5L, 5L)))
        assertNull(TimeRangeSaver.restore(listOf(9L, 1L)))
        assertNull(TimeRangeSaver.restore(listOf(1L)))
        assertNotNull(TimeRangeSaver.restore(listOf(1L, 2L)))
    }
}
