package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom ladder's pure arithmetic: the rung <-> level mapping, and the pinch accumulator that
 * steps it one rung at a time instead of thrashing on every frame of a gesture.
 *
 * This is the one piece of the whole pinch-to-zoom feature that can be checked without a device
 * -- and the piece most worth checking, since a gesture that only ever "looked right" on the one
 * pinch someone happened to try by hand is exactly how the thrash this exists to prevent would
 * ship unnoticed.
 */
class GalleryZoomLadderTest {

    private val ladder = ZoomLadder(minColumns = 2, maxColumns = 5)

    // ---- rung <-> level ----

    @Test
    fun `the ladder climbs from the densest grid through every column count to Calendar then Map`() {
        val levels = (0 until ladder.rungCount).map { ladder.levelAt(it) }
        assertEquals(
            listOf(
                GalleryZoomLevel.Grid(2),
                GalleryZoomLevel.Grid(3),
                GalleryZoomLevel.Grid(4),
                GalleryZoomLevel.Grid(5),
                GalleryZoomLevel.Calendar,
                GalleryZoomLevel.MapView,
            ),
            levels,
        )
    }

    @Test
    fun `rungOf and levelAt round-trip for every rung`() {
        for (rung in 0 until ladder.rungCount) {
            assertEquals(rung, ladder.rungOf(ladder.levelAt(rung)))
        }
    }

    @Test
    fun `a Grid level outside the configured range clamps rather than throwing`() {
        assertEquals(0, ladder.rungOf(GalleryZoomLevel.Grid(0)))
        assertEquals(ladder.rungCount - 3, ladder.rungOf(GalleryZoomLevel.Grid(999)))
    }

    @Test
    fun `levelAt clamps rungs outside the ladder to its two closed ends`() {
        assertEquals(GalleryZoomLevel.Grid(2), ladder.levelAt(-5))
        assertEquals(GalleryZoomLevel.MapView, ladder.levelAt(999))
    }

    // ---- pinch accumulation: the threshold/step logic itself ----

    @Test
    fun `a small pinch well under the threshold changes nothing and just charges the residual`() {
        val start = GalleryZoomLevel.Grid(3)
        val result = ladder.step(start, residual = 0f, scaleFactor = 1.02f)
        assertEquals(start, result.level)
        assertTrue("expected a nonzero residual charge, got ${result.residual}", result.residual != 0f)
    }

    @Test
    fun `crossing the threshold steps exactly one rung and keeps the leftover, not the whole delta`() {
        // Grid(4) is not at either end of this ladder (2..5), so the leftover residual reflects
        // only the threshold arithmetic -- not the separate end-of-ladder clamp, which has its
        // own dedicated test below.
        // ln(1.30) > PINCH_STEP_THRESHOLD, so this must step once towards "zoom in" (fewer
        // columns) and retain only the part of the motion the one step didn't consume.
        val result = ladder.step(GalleryZoomLevel.Grid(4), residual = 0f, scaleFactor = 1.30f)
        assertEquals(GalleryZoomLevel.Grid(3), result.level)
        val expectedResidual = -(kotlin.math.ln(1.30f) - PINCH_STEP_THRESHOLD)
        assertEquals(expectedResidual, result.residual, 1e-4f)
    }

    @Test
    fun `spreading fingers apart zooms in -- fewer columns`() {
        val result = ladder.step(GalleryZoomLevel.Grid(4), residual = 0f, scaleFactor = 1.5f)
        assertTrue(result.level is GalleryZoomLevel.Grid)
        assertTrue((result.level as GalleryZoomLevel.Grid).columns < 4)
    }

    @Test
    fun `pinching fingers together zooms out -- more columns`() {
        val result = ladder.step(GalleryZoomLevel.Grid(2), residual = 0f, scaleFactor = 1f / 1.5f)
        assertTrue(result.level is GalleryZoomLevel.Grid)
        assertTrue((result.level as GalleryZoomLevel.Grid).columns > 2)
    }

    @Test
    fun `zooming out past the sparsest grid reaches Calendar, and further out reaches Map`() {
        // One very large pinch-together should walk straight through both, in a single call --
        // exactly what a fast real-world gesture across several frames would accumulate to.
        val hugePinchOut = ladder.step(
            GalleryZoomLevel.Grid(ladder.maxColumns),
            residual = 0f,
            scaleFactor = 0.01f,
        )
        assertEquals(GalleryZoomLevel.MapView, hugePinchOut.level)
    }

    @Test
    fun `zooming back in from Map reverses through Calendar before reaching the grid again`() {
        val oneStepIn = ladder.step(GalleryZoomLevel.MapView, residual = 0f, scaleFactor = 1.30f)
        assertEquals(GalleryZoomLevel.Calendar, oneStepIn.level)

        val twoStepsIn = ladder.step(oneStepIn.level, oneStepIn.residual, scaleFactor = 1.30f)
        assertEquals(GalleryZoomLevel.Grid(ladder.maxColumns), twoStepsIn.level)
    }

    @Test
    fun `the ladder does not step past either closed end`() {
        val pastDense = ladder.step(GalleryZoomLevel.Grid(ladder.minColumns), residual = 0f, scaleFactor = 3f)
        assertEquals(GalleryZoomLevel.Grid(ladder.minColumns), pastDense.level)

        val pastSparse = ladder.step(GalleryZoomLevel.MapView, residual = 0f, scaleFactor = 0.1f)
        assertEquals(GalleryZoomLevel.MapView, pastSparse.level)
    }

    @Test
    fun `residual does not charge up past a closed end, so reversing needs a full threshold, not a sliver`() {
        // Regression for the exact thrash this file's KDoc describes: without the end-clamp, a
        // small pinch back from a end that had been over-pinched would jump several rungs at
        // once because the earlier, wasted motion was still "banked" in the residual.
        val overPinched = ladder.step(GalleryZoomLevel.MapView, residual = 0f, scaleFactor = 0.01f)
        assertEquals(GalleryZoomLevel.MapView, overPinched.level)
        assertEquals(0f, overPinched.residual, 1e-6f)

        val tinyReversal = ladder.step(overPinched.level, overPinched.residual, scaleFactor = 1.02f)
        assertEquals("a tiny reversal must not immediately step back", GalleryZoomLevel.MapView, tinyReversal.level)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive scale factor is rejected -- it cannot come from a real gesture`() {
        ladder.step(GalleryZoomLevel.Grid(3), residual = 0f, scaleFactor = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an invalid column range is rejected at construction`() {
        ZoomLadder(minColumns = 5, maxColumns = 2)
    }
}
