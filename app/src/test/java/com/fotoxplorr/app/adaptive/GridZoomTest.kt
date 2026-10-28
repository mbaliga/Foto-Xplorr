package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * The zoom rungs' pure arithmetic: the pinch accumulator that steps one rung at a time instead of
 * thrashing on every frame, the button's stepping, and the stamp view's width-driven count.
 *
 * This is the one piece of the pinch-to-zoom feature that can be checked without a device, and the
 * piece most worth checking: a gesture that only ever looked right on the one pinch someone tried
 * by hand is how thrash ships unnoticed.
 */
class GridZoomTest {

    // ---- pinch accumulation ----

    @Test
    fun `a small pinch well under the threshold changes nothing and just charges the residual`() {
        val result = pinchStep(rung = 3, residual = 0f, scaleFactor = 1.02f)
        assertEquals(3, result.rung)
        assertTrue("expected a nonzero charge but got ${result.residual}", result.residual != 0f)
    }

    @Test
    fun `crossing the threshold steps exactly one rung and keeps the leftover`() {
        // ln(1.30) is past PINCH_STEP_THRESHOLD, so this steps once towards fewer columns and
        // keeps only the part of the motion the one step did not consume.
        val result = pinchStep(rung = 4, residual = 0f, scaleFactor = 1.30f)
        assertEquals(3, result.rung)
        assertEquals(-(ln(1.30f) - PINCH_STEP_THRESHOLD), result.residual, 1e-4f)
    }

    @Test
    fun `spreading fingers zooms in to fewer columns and pinching together zooms out`() {
        assertEquals(2, pinchStep(3, 0f, 1.5f).rung)
        assertEquals(4, pinchStep(3, 0f, 1f / 1.5f).rung)
    }

    @Test
    fun `pinching out from the sparsest column grid reaches the stamps`() {
        assertEquals(ZOOM_RUNG_STAMPS, pinchStep(ZOOM_RUNG_LAST_GRID, 0f, 1f / 1.5f).rung)
    }

    @Test
    fun `spreading back in from the stamps lands on the sparsest column grid first`() {
        val first = pinchStep(ZOOM_RUNG_STAMPS, 0f, 1.30f)
        assertEquals(ZOOM_RUNG_LAST_GRID, first.rung)
        val second = pinchStep(first.rung, first.residual, 1.30f)
        assertEquals(ZOOM_RUNG_LAST_GRID - 1, second.rung)
    }

    @Test
    fun `one very large pinch walks through every rung in a single call`() {
        assertEquals(ZOOM_RUNG_STAMPS, pinchStep(ZOOM_RUNG_MIN, 0f, 0.001f).rung)
        assertEquals(ZOOM_RUNG_MIN, pinchStep(ZOOM_RUNG_STAMPS, 0f, 1000f).rung)
    }

    @Test
    fun `the rungs do not step past either closed end`() {
        assertEquals(ZOOM_RUNG_MIN, pinchStep(ZOOM_RUNG_MIN, 0f, 3f).rung)
        assertEquals(ZOOM_RUNG_STAMPS, pinchStep(ZOOM_RUNG_STAMPS, 0f, 0.1f).rung)
    }

    @Test
    fun `residual does not charge up against an end so reversing needs a full threshold`() {
        val overPinched = pinchStep(ZOOM_RUNG_STAMPS, 0f, 0.01f)
        assertEquals(ZOOM_RUNG_STAMPS, overPinched.rung)
        assertEquals(0f, overPinched.residual, 1e-6f)

        val tinyReversal = pinchStep(overPinched.rung, overPinched.residual, 1.02f)
        assertEquals("a tiny reversal must not step back at once", ZOOM_RUNG_STAMPS, tinyReversal.rung)

        val overSpread = pinchStep(ZOOM_RUNG_MIN, 0f, 100f)
        assertEquals(0f, overSpread.residual, 1e-6f)
    }

    @Test
    fun `a grid without stamps stops at the sparsest column grid`() {
        val atTheEnd = pinchStep(ZOOM_RUNG_LAST_GRID, 0f, 0.1f, maxRung = ZOOM_RUNG_LAST_GRID)
        assertEquals(ZOOM_RUNG_LAST_GRID, atTheEnd.rung)
        assertEquals(0f, atTheEnd.residual, 1e-6f)
        // A rung left over from a grid that did have stamps is pulled back inside the range.
        assertEquals(ZOOM_RUNG_LAST_GRID, pinchStep(ZOOM_RUNG_STAMPS, 0f, 1f, maxRung = ZOOM_RUNG_LAST_GRID).rung)
    }

    @Test
    fun `a finger resting on a boundary does not flicker`() {
        // Step out once, then wobble a few percent either way: the rung must hold.
        var step = pinchStep(3, 0f, 0.78f)
        assertEquals(4, step.rung)
        repeat(10) {
            step = pinchStep(step.rung, step.residual, 1.03f)
            step = pinchStep(step.rung, step.residual, 1f / 1.03f)
        }
        assertEquals(4, step.rung)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive scale factor is rejected`() {
        pinchStep(3, 0f, 0f)
    }

    // ---- the button and the keys ----

    @Test
    fun `the density button wraps from the stamps back to one column`() {
        assertEquals(2, wrapRung(1))
        assertEquals(ZOOM_RUNG_STAMPS, wrapRung(ZOOM_RUNG_LAST_GRID))
        assertEquals(ZOOM_RUNG_MIN, wrapRung(ZOOM_RUNG_STAMPS))
    }

    @Test
    fun `the density button on a grid without stamps wraps after the sparsest column grid`() {
        assertEquals(ZOOM_RUNG_MIN, wrapRung(ZOOM_RUNG_LAST_GRID, maxRung = ZOOM_RUNG_LAST_GRID))
        assertEquals(ZOOM_RUNG_MIN, wrapRung(ZOOM_RUNG_STAMPS, maxRung = ZOOM_RUNG_LAST_GRID))
    }

    @Test
    fun `stepRung clamps at both ends rather than wrapping`() {
        assertEquals(ZOOM_RUNG_MIN, stepRung(ZOOM_RUNG_MIN, -1))
        assertEquals(ZOOM_RUNG_STAMPS, stepRung(ZOOM_RUNG_STAMPS, 1))
        assertEquals(4, stepRung(3, 1))
        assertEquals(ZOOM_RUNG_LAST_GRID, stepRung(ZOOM_RUNG_LAST_GRID, 1, maxRung = ZOOM_RUNG_LAST_GRID))
    }

    // ---- columns, stamps and labels ----

    @Test
    fun `rungs one to six are their own column count and the stamps take the width-driven count`() {
        for (rung in 1..6) assertEquals(rung, columnsAcross(rung, stampAcross = 11))
        assertEquals(11, columnsAcross(ZOOM_RUNG_STAMPS, stampAcross = 11))
    }

    @Test
    fun `the stamp count follows the width and stays between eight and twenty four`() {
        // (360 + 4) / (36 + 4) = 9.1
        assertEquals(9, stampAcross(360f))
        assertEquals(8, stampAcross(200f))
        assertEquals(24, stampAcross(1600f))
        assertEquals(8, stampAcross(0f))
        assertEquals(8, stampAcross(Float.NaN))
    }

    @Test
    fun `a wider window never gets fewer stamps across`() {
        var previous = 0
        for (width in 100..1800 step 50) {
            val count = stampAcross(width.toFloat())
            assertTrue("at $width dp", count >= previous)
            previous = count
        }
    }

    @Test
    fun `the photo picker never shows the stamps`() {
        assertEquals(6, pickerColumnsFor(ZOOM_RUNG_STAMPS))
        assertEquals(3, pickerColumnsFor(3))
        assertEquals(1, pickerColumnsFor(0))
    }

    @Test
    fun `a stored column count becomes the same rung except that seven becomes six`() {
        for (columns in 2..6) assertEquals(columns, zoomRungFromLegacyColumns(columns))
        assertEquals(6, zoomRungFromLegacyColumns(7))
        assertEquals(1, zoomRungFromLegacyColumns(-4))
    }

    @Test
    fun `labels say the columns and name the stamps`() {
        assertEquals("1 column", zoomRungLabel(1))
        assertEquals("3 columns", zoomRungLabel(3))
        assertEquals("6 columns", zoomRungLabel(6))
        assertEquals("Stamps", zoomRungLabel(ZOOM_RUNG_STAMPS))
        assertEquals("3", zoomRungValue(3))
        assertEquals("Stamps", zoomRungValue(ZOOM_RUNG_STAMPS))
        assertFalse(isStampRung(ZOOM_RUNG_LAST_GRID))
        assertTrue(isStampRung(ZOOM_RUNG_STAMPS))
    }
}
