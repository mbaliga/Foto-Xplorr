package com.fotoxplorr.app.hyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slipped-sheet corners and the insets that keep room content clear of them and the strip. */
class SheetGeometryTest {

    @Test
    fun `the corner is square at rest and eases in with the pull`() {
        assertEquals(0f, SheetGeometry.sheetCornerDp(0f), 0f)
        assertEquals(10f, SheetGeometry.sheetCornerDp(0.5f), 1e-4f)
        assertEquals(SheetGeometry.FULL_CORNER_DP, SheetGeometry.sheetCornerDp(1f), 0f)
        assertEquals(SheetGeometry.FULL_CORNER_DP, SheetGeometry.sheetCornerDp(3f), 0f)
        assertEquals(0f, SheetGeometry.sheetCornerDp(-1f), 0f)
    }

    @Test
    fun `the corner matches the shells own radius`() {
        assertEquals(dev.aarso.cellshell.SpatialMotion.PARK_CORNER_DP, SheetGeometry.FULL_CORNER_DP, 0f)
    }

    @Test
    fun `no corner needs no clearance`() {
        assertEquals(0f, SheetGeometry.cornerClearanceDp(0f, 0f), 0f)
        assertEquals(0f, SheetGeometry.cornerClearanceDp(20f, 20f), 0f)
        assertEquals(0f, SheetGeometry.cornerClearanceDp(20f, 40f), 0f)
    }

    @Test
    fun `the clearance shrinks as the row sits lower`() {
        val atTop = SheetGeometry.cornerClearanceDp(20f, 0f)
        val mid = SheetGeometry.cornerClearanceDp(20f, 10f)
        val low = SheetGeometry.cornerClearanceDp(20f, 19f)
        assertTrue(atTop > mid && mid > low)
        // At the very top the circle bites its whole radius.
        assertEquals(20f + SheetGeometry.CORNER_MARGIN_DP, atTop, 1e-3f)
    }

    @Test
    fun `insets keep the base gutter and add the strip on its own side`() {
        val none = SheetGeometry.contentInsets(0f, 0f, baseDp = 14f)
        assertEquals(14f, none.startDp, 0f)
        assertEquals(14f, none.endDp, 0f)

        val right = SheetGeometry.contentInsets(0f, 0f, 14f, gutterEndDp = 44f)
        assertEquals(14f, right.startDp, 0f)
        assertEquals(58f, right.endDp, 0f)

        val left = SheetGeometry.contentInsets(0f, 0f, 14f, gutterStartDp = 44f)
        assertEquals(58f, left.startDp, 0f)
        assertEquals(14f, left.endDp, 0f)
    }

    @Test
    fun `a row at the corner is pushed in past the base gutter`() {
        val at = SheetGeometry.contentInsets(cornerDp = 20f, depthDp = 2f, baseDp = 14f)
        assertTrue(at.startDp > 14f)
        assertEquals(at.startDp, at.endDp, 0f)
        val below = SheetGeometry.contentInsets(cornerDp = 20f, depthDp = 30f, baseDp = 14f)
        assertEquals(14f, below.startDp, 0f)
    }

    @Test
    fun `the sheet under the shade is square collapsed and rounds as the shade opens`() {
        assertEquals(0f, SheetGeometry.shadeSheetCornerDp(0f), 0f)
        assertEquals(0f, SheetGeometry.shadeSheetCornerDp(COLLAPSED_HEIGHT.toFloat()), 0f)
        val half = SheetGeometry.shadeSheetCornerDp(COLLAPSED_HEIGHT + NOTIFICATION_ROW / 2f)
        assertTrue(half > 0f && half < SheetGeometry.FULL_CORNER_DP)
        assertEquals(SheetGeometry.FULL_CORNER_DP, SheetGeometry.shadeSheetCornerDp(EXPANDED_HERO.toFloat()), 0f)
    }
}
