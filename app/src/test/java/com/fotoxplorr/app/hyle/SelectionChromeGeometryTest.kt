package com.fotoxplorr.app.hyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the multi-select chrome's outlines to the owner's reference image (2026-11-18, a 440 x 956
 * dp phone at 1 px = 1 dp). The sample rows are the reference's own measured edges (sub-pixel,
 * at each pixel row's centre) on rows where the photograph behind gives a clean edge; every one
 * must land within 1 dp. A change to the fitted numbers that drifts off the reference fails here.
 */
class SelectionChromeGeometryTest {

    private fun near(what: String, expected: Float, actual: Float, tol: Float = 1f) =
        assertTrue("$what: expected $expected ±$tol, was $actual", kotlin.math.abs(expected - actual) <= tol)

    @Test
    fun `top tab follows the reference rows`() {
        // row -> (left, right), from reference-outlines.json
        val rows = mapOf(
            0 to (25.13f to 210.55f), 2 to (28.43f to 206.74f), 4 to (31.03f to 204.16f),
            8 to (33.77f to 201.0f), 12 to (35.41f to 199.0f), 16 to (37.09f to 198.0f),
            19 to (38.12f to 196.76f),
        )
        for ((y, lr) in rows) {
            near("tab left, row $y", lr.first, SelectionTab.leftAt(y + 0.5f))
            near("tab right, row $y", lr.second, SelectionTab.rightAt(y + 0.5f))
        }
        // the right flank and corner, clean over a light photograph all the way down
        val right = mapOf(24 to 193.93f, 30 to 192.52f, 36 to 189.84f, 40 to 188.57f, 44 to 185.88f, 47 to 182.85f, 49 to 178.9f)
        for ((y, x) in right) near("tab right, row $y", x, SelectionTab.rightAt(y + 0.5f))
    }

    @Test
    fun `top tab key numbers`() {
        assertEquals(50.76f, SelectionTab.height, 0.01f)
        near("leaves the top edge at", 20.5f, SelectionTab.minX, 0.5f)
        near("returns to the top edge at", 214.9f, SelectionTab.maxX, 0.5f)
        // flat bottom from about 62.5 to 172.7 (the corner circles' centres)
        near("bottom-left corner meets the bottom", 62.5f, SelectionTab.leftAt(SelectionTab.height), 0.8f)
        near("bottom-right corner meets the bottom", 172.7f, SelectionTab.rightAt(SelectionTab.height), 0.8f)
    }

    @Test
    fun `trash tab follows the reference rows`() {
        // screen row -> (left, right); the tab's y counts up from the bottom edge at 956
        val rows = mapOf(
            912 to (331.39f to 381.35f), 916 to (326.58f to 385.47f), 920 to (324.52f to 387.52f),
            930 to (320.36f to 391.62f), 940 to (316.25f to 395.68f), 950 to (311.51f to 400.26f),
            953 to (308.53f to 402.44f), 955 to (305.65f to 404.5f),
        )
        for ((row, lr) in rows) {
            val y = 956f - (row + 0.5f)
            near("trash left, row $row", lr.first, SelectionTrashTab.leftAt(y))
            near("trash right, row $row", lr.second, SelectionTrashTab.rightAt(y))
        }
        assertEquals(46.2f, SelectionTrashTab.height, 0.01f)
        near("trash footprint", 106.7f, SelectionTrashTab.width, 0.5f)
        near("trash right foot from the right edge", 31.8f, SELECTION_DESIGN_SCREEN_W - SelectionTrashTab.maxX, 0.5f)
    }

    @Test
    fun `the count bar keeps a constant gap from the tab's flank`() {
        // The bar's straight left end is at x = 198 on the reference; from row 17 down to 31 its
        // edge is the tab's flank grown by the gap, and the reference reads those rows cleanly.
        val grown = SelectionTab.grown(SELECTION_TAB_GAP)
        val rows = mapOf(17 to 203.49f, 20 to 202.11f, 24 to 200.36f, 26 to 200.02f, 29 to 199.02f)
        for ((y, x) in rows) near("bar cut, row $y", x, grown.rightAt(y + 0.5f))
        assertEquals(5.8f, SELECTION_TAB_GAP, 0.01f)
    }
}
