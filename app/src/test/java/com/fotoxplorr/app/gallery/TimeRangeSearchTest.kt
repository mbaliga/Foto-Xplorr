package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The time range composes with a view's projection and its search as a plain conjunction, and
 * lives outside the projection: the destinations the goldens pin never see it.
 */
class TimeRangeSearchTest {

    private val assets = SyntheticCatalogue.assets(2_000)
    private val state = SyntheticCatalogue.state(assets)
    // The search's date phrases read in the default zone; pinned so the month edges are the same
    // wherever this runs.
    private val zone = ZoneOffset.UTC
    private val originalZone = TimeZone.getDefault()

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    // The catalogue runs back from 1 Aug 2026 in two-hour steps, so these months hold hundreds.
    private val range = TimeRange.ofMonths(YearMonth(2026, 4), YearMonth(2026, 6), zone) // May - Jul

    @Test
    fun `a range applied to a destination equals filtering it by hand`() {
        val base = destinationAssets(HyleDestination.PHOTOS, state)
        val ranged = base.inTimeRange(range)
        assertTrue("the range should keep some and drop some", ranged.isNotEmpty() && ranged.size < base.size)
        assertEquals(base.filter { it.timelineMillis >= range.fromMillis && it.timelineMillis < range.untilMillis }, ranged)
    }

    @Test
    fun `a range and a search intersect`() {
        val searched = destinationAssets(HyleDestination.PHOTOS, state, "IMG_0")
        val combined = searched.inTimeRange(range)
        val rangeOnly = destinationAssets(HyleDestination.PHOTOS, state).inTimeRange(range)
        assertTrue(combined.isNotEmpty())
        assertEquals(searched.filter { it in rangeOnly }, combined)
    }

    @Test
    fun `a range intersects a search that carries its own date phrase rather than overriding it`() {
        val june = destinationAssets(HyleDestination.PHOTOS, state, "june 2026")
        val narrowed = june.inTimeRange(TimeRange.ofMonths(YearMonth(2026, 5), YearMonth(2026, 7), zone)) // Jun - Aug
        assertTrue("the phrase should match some photos", june.isNotEmpty())
        // June is inside Jun - Aug, so nothing the phrase found is lost...
        assertEquals(june.map { it.id }, narrowed.map { it.id })
        // ...and a range that excludes June leaves nothing of it, whatever the phrase says.
        val july = june.inTimeRange(TimeRange.ofMonths(YearMonth(2026, 6), YearMonth(2026, 7), zone))
        assertTrue(july.isEmpty())
    }

    @Test
    fun `no range leaves every destination exactly as the projection made it`() {
        HyleDestination.entries.forEach { destination ->
            val base = destinationAssets(destination, state)
            assertTrue(destination.name, base === base.inTimeRange(null))
        }
    }
}
