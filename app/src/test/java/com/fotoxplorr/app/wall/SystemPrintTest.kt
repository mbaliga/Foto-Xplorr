package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page maths of the system print path, which is pure. What is NOT proved: the PDF itself
 * (Robolectric has no PDF writer: `PdfDocument` reports "document is closed" there), that the
 * platform print dialog calls the adapter the way it expects, or what a printer does with the
 * page. All three are device checks.
 */
class SystemPrintTest {

    private val a4 = PrintPage.A4_WIDTH_PT to PrintPage.A4_HEIGHT_PT

    @Test
    fun `units agree with each other`() {
        assertEquals(72f, PrintPage.cmToPt(2.54), 0.001f)
        assertEquals(595.27f, PrintPage.cmToPt(21.0), 0.1f)
        assertEquals(72f, PrintPage.milsToPt(1000), 0.001f)
        assertEquals(2.54, PrintPage.ptToCm(72f), 0.0001)
        // A4 in the print framework's mils: 8268 x 11692.
        assertEquals(595.3f, PrintPage.milsToPt(8268), 0.1f)
    }

    @Test
    fun `a picture that fits the paper is placed at its real size, centred`() {
        val p = PrintPage.place(a4.first, a4.second, PrintSize(10.0, 15.0))
        assertFalse(p.shrunk)
        assertEquals(PrintPage.cmToPt(10.0), p.widthPt, 0.01f)
        assertEquals(PrintPage.cmToPt(15.0), p.heightPt, 0.01f)
        assertEquals((a4.first - p.widthPt) / 2, p.leftPt, 0.01f)
        assertEquals((a4.second - p.heightPt) / 2, p.topPt, 0.01f)
    }

    @Test
    fun `a picture bigger than the paper is shrunk to fit inside a margin, keeping its shape`() {
        val size = PrintSize(40.0, 50.0)
        val p = PrintPage.place(a4.first, a4.second, size)
        assertTrue(p.shrunk)
        assertTrue(p.leftPt >= PrintPage.FIT_MARGIN_PT - 0.01f && p.topPt >= PrintPage.FIT_MARGIN_PT - 0.01f)
        assertTrue(p.leftPt + p.widthPt <= a4.first - PrintPage.FIT_MARGIN_PT + 0.01f)
        assertTrue(p.topPt + p.heightPt <= a4.second - PrintPage.FIT_MARGIN_PT + 0.01f)
        assertEquals(size.aspect, (p.widthPt / p.heightPt).toDouble(), 0.001)
        // It is as big as it can be: one side touches the margin.
        val touchesWidth = p.leftPt - PrintPage.FIT_MARGIN_PT < 0.01f
        val touchesHeight = p.topPt - PrintPage.FIT_MARGIN_PT < 0.01f
        assertTrue(touchesWidth || touchesHeight)
    }

    @Test
    fun `the printed size on the page can be read back in cm`() {
        val p = PrintPage.place(a4.first, a4.second, PrintSize(10.0, 15.0))
        assertEquals(10.0, PrintPage.ptToCm(p.widthPt), 0.01)
        assertEquals(15.0, PrintPage.ptToCm(p.heightPt), 0.01)
    }

    @Test
    fun `a page too small for any margin still gets a positive size`() {
        val p = PrintPage.place(40f, 40f, PrintSize(30.0, 40.0))
        assertTrue(p.widthPt > 0f && p.heightPt > 0f)
    }

    @Test
    fun `landscape paper is used as it comes, not turned`() {
        val p = PrintPage.place(a4.second, a4.first, PrintSize(30.0, 40.0))
        assertTrue(p.shrunk)
        assertTrue(p.heightPt <= a4.first - 2 * PrintPage.FIT_MARGIN_PT + 0.01f)
        assertTrue(p.heightPt > p.widthPt)
    }
}
