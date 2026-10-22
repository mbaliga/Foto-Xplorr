package com.fotoxplorr.app.spatial.offline

import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.GeoMetadata
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionSuggestionsTest {

    private fun at(lat: Double, lon: Double, times: Int = 1) = List(times) { GeoPoint(lat, lon) }

    private val paris = at(48.8584, 2.2945, 5) + at(48.8606, 2.3376, 3) + at(48.8530, 2.3499, 2)
    private val tokyo = at(35.6586, 139.7454, 3)

    @Test
    fun `no photos suggest nothing`() {
        assertEquals(emptyList<SuggestedRegion>(), RegionSuggestions.suggest(emptyList()))
    }

    @Test
    fun `two cities become two regions with the busiest first`() {
        val regions = RegionSuggestions.suggest(tokyo + paris)
        assertEquals(2, regions.size)
        assertEquals(10, regions[0].photoCount)
        assertEquals(3, regions[1].photoCount)
        assertTrue(regions[0].box.contains(48.8584, 2.2945))
        assertTrue(regions[1].box.contains(35.6586, 139.7454))
        regions.forEach { assertTrue(it.verdict.toString(), it.verdict is BudgetVerdict.Fits) }
    }

    @Test
    fun `photos spread over one city in neighbouring cells make one region`() {
        val spread = at(48.80, 2.20) + at(48.86, 2.35) + at(48.92, 2.45) + at(48.88, 2.30)
        val regions = RegionSuggestions.suggest(spread)
        assertEquals(1, regions.size)
        assertEquals(4, regions.single().photoCount)
    }

    @Test
    fun `places far apart are never merged`() {
        val regions = RegionSuggestions.suggest(at(48.86, 2.35) + at(51.50, -0.12) + at(41.90, 12.50))
        assertEquals(3, regions.size)
    }

    @Test
    fun `a single photo still gets a real area`() {
        val region = RegionSuggestions.suggest(at(48.8584, 2.2945)).single()
        assertFalse(region.box.isZeroArea)
        assertTrue(region.box.widthDegrees >= RegionSuggestions.MIN_SPAN_DEGREES - 1e-9)
        assertTrue(region.box.heightDegrees >= RegionSuggestions.MIN_SPAN_DEGREES - 1e-9)
        assertTrue(region.box.contains(48.8584, 2.2945))
    }

    @Test
    fun `many photos at one spot are one region with the right count`() {
        val region = RegionSuggestions.suggest(at(12.9716, 77.5946, 100)).single()
        assertEquals(100, region.photoCount)
    }

    @Test
    fun `the number of regions is capped and the busiest are kept`() {
        val many = (0 until 10).flatMap { at(10.0 + it * 3.0, 20.0, times = it + 1) }
        val regions = RegionSuggestions.suggest(many, maxRegions = 3)
        assertEquals(3, regions.size)
        assertEquals(listOf(10, 9, 8), regions.map { it.photoCount })
    }

    @Test
    fun `a cap of zero or less suggests nothing`() {
        assertEquals(0, RegionSuggestions.suggest(paris, maxRegions = 0).size)
        assertEquals(0, RegionSuggestions.suggest(paris, maxRegions = -4).size)
    }

    @Test
    fun `places with too few photos can be left out`() {
        val regions = RegionSuggestions.suggest(paris + tokyo, minPhotos = 5)
        assertEquals(1, regions.size)
        assertEquals(10, regions.single().photoCount)
    }

    @Test
    fun `photos on either side of the date line are one region`() {
        val regions = RegionSuggestions.suggest(at(-16.80, 179.95, 2) + at(-16.82, -179.95, 3))
        assertEquals(1, regions.size)
        val box = regions.single().box
        assertTrue(box.crossesAntimeridian)
        assertEquals(5, regions.single().photoCount)
        assertTrue(box.contains(-16.80, 179.95))
        assertTrue(box.contains(-16.82, -179.95))
        assertTrue(box.widthDegrees < 1.0)
    }

    @Test
    fun `a long chain of photos is split into regions that each fit at full detail`() {
        // A road trip: one photo every 0.2 degrees for about 6 degrees of longitude.
        val trip = (0 until 30).flatMap { at(47.0, 5.0 + it * 0.2) }
        val regions = RegionSuggestions.suggest(trip, maxRegions = 50)
        assertTrue("expected the trip to be split, got ${regions.size}", regions.size >= 2)
        regions.forEach { assertTrue(it.verdict.toString(), it.verdict is BudgetVerdict.Fits) }
        // Every photo lands in at least one region.
        trip.forEach { p -> assertTrue("$p", regions.any { it.box.contains(p.latitude, p.longitude) }) }
    }

    @Test
    fun `bad coordinates and photos beyond the map are ignored`() {
        val junk = listOf(
            GeoPoint(Double.NaN, 5.0), GeoPoint(5.0, Double.POSITIVE_INFINITY),
            GeoPoint(95.0, 10.0), GeoPoint(10.0, 400.0),
            GeoPoint(89.9, 10.0), // beyond what Web Mercator can draw
        )
        assertEquals(emptyList<SuggestedRegion>(), RegionSuggestions.suggest(junk))
        assertEquals(1, RegionSuggestions.suggest(junk + at(48.86, 2.35)).size)
    }

    @Test
    fun `the order of the input does not change the answer`() {
        val points = tokyo + paris + at(51.5, -0.12, 4)
        assertEquals(RegionSuggestions.suggest(points), RegionSuggestions.suggest(points.reversed()))
        assertEquals(RegionSuggestions.suggest(points), RegionSuggestions.suggest(points.shuffled(java.util.Random(7))))
    }

    @Test
    fun `a region is padded so its photos are not on the edge`() {
        val region = RegionSuggestions.suggest(at(48.80, 2.20) + at(48.92, 2.45)).single()
        assertTrue(region.box.west < 2.20 && region.box.east > 2.45)
        assertTrue(region.box.south < 48.80 && region.box.north > 48.92)
    }

    @Test
    fun `the placeholder name is the rounded centre`() {
        val box = GeoBox.clamped(2.2, 48.8, 2.4, 49.0)!!
        assertEquals("48.9 N, 2.3 E", RegionSuggestions.placeholderName(box))
        assertEquals("33.9 S, 151.2 E", RegionSuggestions.placeholderName(GeoBox.clamped(151.1, -34.0, 151.3, -33.8)!!))
        assertEquals("0.0 N, 0.0 E", RegionSuggestions.placeholderName(GeoBox.clamped(-0.01, -0.01, 0.01, 0.01)!!))
        assertEquals("10.0 N, 175.0 W", RegionSuggestions.placeholderName(GeoBox.clamped(-176.0, 9.0, -174.0, 11.0)!!))
    }

    // ---- the geo index adapter ----

    private fun index(vararg located: Triple<Long, Double, Double>) = GeoIndexState(
        metadataById = located.associate { (id, lat, lon) ->
            MediaId(id) to GeoMetadata(MediaId(id), lat, lon, altitudeMeters = null, captureDirectionDegrees = null)
        },
    )

    @Test
    fun `only photos the caller says are browsable become points`() {
        val state = index(Triple(1L, 48.0, 2.0), Triple(2L, 35.0, 139.0), Triple(3L, 51.0, 0.0))
        val points = state.browsablePoints(listOf(MediaId(1), MediaId(3)))
        assertEquals(setOf(GeoPoint(48.0, 2.0), GeoPoint(51.0, 0.0)), points.toSet())
    }

    @Test
    fun `an empty browsable list leaks nothing`() {
        val state = index(Triple(1L, 48.0, 2.0))
        assertEquals(emptyList<GeoPoint>(), state.browsablePoints(emptyList()))
    }

    @Test
    fun `a browsable photo with no stored location adds no point`() {
        val state = index(Triple(1L, 48.0, 2.0))
        assertEquals(1, state.browsablePoints(listOf(MediaId(1), MediaId(99))).size)
    }
}
