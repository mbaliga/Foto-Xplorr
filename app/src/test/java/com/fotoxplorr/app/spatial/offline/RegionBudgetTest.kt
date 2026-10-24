package com.fotoxplorr.app.spatial.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionBudgetTest {

    private fun box(west: Double, south: Double, east: Double, north: Double): GeoBox =
        GeoBox.clamped(west, south, east, north)!!

    // About 7 km by 7 km.
    private val smallCity = box(2.30, 48.83, 2.38, 48.89)

    // Roughly Greater London.
    private val metro = box(-0.51, 51.28, 0.33, 51.69)

    // Roughly Switzerland.
    private val country = box(5.96, 45.82, 10.49, 47.81)

    // ---- the caps ----

    @Test
    fun `our caps sit below the published terms`() {
        assertTrue(OfflineMapLimits.MAX_TILES < OfflineMapLimits.TERMS_MAX_TILES)
        assertTrue(OfflineMapLimits.MAX_BYTES < OfflineMapLimits.TERMS_MAX_BYTES)
        assertEquals(2_500L, OfflineMapLimits.MAX_TILES)
        assertEquals(45_000_000L, OfflineMapLimits.MAX_BYTES)
        assertEquals(4, OfflineMapLimits.TERMS_MAX_PARALLEL_REQUESTS)
        assertEquals(6, OfflineMapLimits.MAX_REGIONS)
        assertEquals(300_000_000L, OfflineMapLimits.MAX_TOTAL_BYTES)
    }

    // ---- the estimate ----

    @Test
    fun `an estimate is the tile count plus the fixed overhead`() {
        val estimate = RegionBudget.estimate(smallCity, 0, 14)
        assertEquals(TileMath.tileCount(smallCity, 0, 14), estimate.tileCount)
        assertTrue(estimate.bytes > RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES)
    }

    @Test
    fun `the estimate never goes down as the top zoom or the area grows`() {
        var previous = 0L
        for (maxZoom in 0..14) {
            val bytes = RegionBudget.estimate(smallCity, 0, maxZoom).bytes
            assertTrue(bytes > previous)
            previous = bytes
        }
        assertTrue(RegionBudget.estimate(metro, 0, 12).bytes > RegionBudget.estimate(smallCity, 0, 12).bytes)
    }

    @Test
    fun `a zoom above the table uses the last known tile size`() {
        assertEquals(RegionBudget.estimatedBytesPerTile(14), RegionBudget.estimatedBytesPerTile(15))
        val beyond = RegionBudget.estimate(smallCity, 15, 15)
        assertEquals(
            RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES + beyond.tileCount * RegionBudget.estimatedBytesPerTile(15),
            beyond.bytes,
        )
    }

    // ---- fitting a top zoom ----

    @Test
    fun `a small city fits at full detail`() {
        assertEquals(OfflineMapLimits.MAX_ZOOM, RegionBudget.fitMaxZoom(smallCity))
        val verdict = RegionBudget.plan(smallCity)
        assertTrue(verdict.toString(), verdict is BudgetVerdict.Fits)
        verdict as BudgetVerdict.Fits
        assertEquals(14, verdict.estimate.maxZoom)
        assertTrue(verdict.estimate.tileCount <= OfflineMapLimits.MAX_TILES)
        assertTrue(verdict.estimate.bytes <= OfflineMapLimits.MAX_BYTES)
    }

    @Test
    fun `a metro area is offered with reduced detail that fits the caps`() {
        val verdict = RegionBudget.plan(metro)
        assertTrue(verdict.toString(), verdict is BudgetVerdict.Reduced)
        verdict as BudgetVerdict.Reduced
        assertTrue(verdict.estimate.maxZoom in OfflineMapLimits.MIN_USEFUL_MAX_ZOOM until OfflineMapLimits.MAX_ZOOM)
        assertTrue(verdict.estimate.tileCount <= OfflineMapLimits.MAX_TILES)
        assertTrue(verdict.estimate.bytes <= OfflineMapLimits.MAX_BYTES)
    }

    @Test
    fun `a small country is too large and says it is the bytes`() {
        // About 2,490 tiles to zoom 12: just inside the tile cap, far outside the byte cap.
        val verdict = RegionBudget.plan(country)
        assertTrue(verdict.toString(), verdict is BudgetVerdict.TooLarge)
        verdict as BudgetVerdict.TooLarge
        assertEquals(TooLargeReason.TOO_MANY_BYTES, verdict.reason)
        assertTrue(verdict.floorEstimate.bytes > OfflineMapLimits.MAX_BYTES)
        assertEquals(OfflineMapLimits.MIN_USEFUL_MAX_ZOOM, verdict.floorEstimate.maxZoom)
    }

    @Test
    fun `a big country is too large and says it is the tile count`() {
        val france = box(-5.0, 41.3, 9.6, 51.1)
        val verdict = RegionBudget.plan(france)
        assertTrue(verdict.toString(), verdict is BudgetVerdict.TooLarge)
        verdict as BudgetVerdict.TooLarge
        assertEquals(TooLargeReason.TOO_MANY_TILES, verdict.reason)
        assertTrue(verdict.floorEstimate.tileCount > OfflineMapLimits.MAX_TILES)
    }

    @Test
    fun `the whole world is too large`() {
        val verdict = RegionBudget.plan(box(-180.0, -85.0, 180.0, 85.0))
        assertTrue(verdict is BudgetVerdict.TooLarge)
        assertNull(RegionBudget.fitMaxZoom(box(-180.0, -85.0, 180.0, 85.0), maxTiles = 0))
    }

    @Test
    fun `a zero area box is not a region`() {
        assertEquals(BudgetVerdict.NoArea, RegionBudget.plan(box(5.0, 5.0, 5.0, 6.0)))
        assertEquals(BudgetVerdict.NoArea, RegionBudget.plan(box(5.0, 5.0, 6.0, 5.0)))
    }

    @Test
    fun `a tiny box fits at full detail`() {
        val verdict = RegionBudget.plan(box(2.2945, 48.8584, 2.2946, 48.8585))
        assertTrue(verdict is BudgetVerdict.Fits)
    }

    @Test
    fun `a box across the antimeridian is planned like any other`() {
        val verdict = RegionBudget.plan(box(179.95, -0.05, -179.95, 0.05))
        assertTrue(verdict.toString(), verdict is BudgetVerdict.Fits)
    }

    @Test
    fun `the plan never exceeds the top zoom of the source`() {
        val tiny = box(10.0, 10.0, 10.001, 10.001)
        assertEquals(OfflineMapLimits.MAX_ZOOM, RegionBudget.fitMaxZoom(tiny, ceiling = 20))
    }

    // ---- exactly at the cap ----

    @Test
    fun `a budget exactly at the tile and byte figures still fits`() {
        val atTen = RegionBudget.estimate(metro, 0, 10)
        assertEquals(
            10,
            RegionBudget.fitMaxZoom(metro, 0, 14, maxTiles = atTen.tileCount, maxBytes = atTen.bytes),
        )
    }

    @Test
    fun `one tile over the cap drops a zoom level`() {
        val atTen = RegionBudget.estimate(metro, 0, 10)
        assertEquals(
            9,
            RegionBudget.fitMaxZoom(metro, 0, 14, maxTiles = atTen.tileCount - 1, maxBytes = atTen.bytes),
        )
    }

    @Test
    fun `one byte over the cap drops a zoom level`() {
        val atTen = RegionBudget.estimate(metro, 0, 10)
        assertEquals(
            9,
            RegionBudget.fitMaxZoom(metro, 0, 14, maxTiles = atTen.tileCount, maxBytes = atTen.bytes - 1),
        )
    }

    @Test
    fun `an overhead bigger than the byte cap fits nothing`() {
        assertNull(RegionBudget.fitMaxZoom(smallCity, 0, 14, maxBytes = RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES))
    }

    @Test
    fun `a byte-bound area reports bytes not tiles`() {
        val verdict = RegionBudget.plan(smallCity, maxTiles = 1_000_000L, maxBytes = RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES + 1)
        assertTrue(verdict.toString(), verdict is BudgetVerdict.TooLarge)
        assertEquals(TooLargeReason.TOO_MANY_BYTES, (verdict as BudgetVerdict.TooLarge).reason)
    }
}
