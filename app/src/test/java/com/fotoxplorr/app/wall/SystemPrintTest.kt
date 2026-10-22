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

    @Test
    fun `a picture exactly the size of the paper is shrunk inside the margin, not printed edge to edge`() {
        // A4 on A4: most printers cut 3 to 5 mm at the edge, so the picture backs off the edge.
        val p = PrintPage.place(a4.first, a4.second, PrintSize(21.0, 29.7))
        assertTrue(p.shrunk)
        assertTrue(p.leftPt >= PrintPage.FIT_MARGIN_PT - 0.01f && p.topPt >= PrintPage.FIT_MARGIN_PT - 0.01f)
        assertTrue(p.leftPt + p.widthPt <= a4.first - PrintPage.FIT_MARGIN_PT + 0.01f)
        assertTrue(p.topPt + p.heightPt <= a4.second - PrintPage.FIT_MARGIN_PT + 0.01f)
    }

    @Test
    fun `a picture that just fits inside the margin keeps its real size, one hair bigger shrinks`() {
        val innerW = a4.first - 2 * PrintPage.FIT_MARGIN_PT
        val innerH = a4.second - 2 * PrintPage.FIT_MARGIN_PT
        val fits = PrintPage.place(a4.first, a4.second, PrintSize(PrintPage.ptToCm(innerW - 0.1f), PrintPage.ptToCm(innerH - 0.1f)))
        assertFalse(fits.shrunk)
        assertEquals(PrintPage.FIT_MARGIN_PT + 0.05f, fits.leftPt, 0.05f)
        val over = PrintPage.place(a4.first, a4.second, PrintSize(PrintPage.ptToCm(innerW + 0.5f), PrintPage.ptToCm(innerH - 0.1f)))
        assertTrue(over.shrunk)
    }

    @Test
    fun `a 10 x 15 print on borderless 4x6 paper keeps its real size`() {
        // 4 x 6 in is 288 x 432 pt; a borderless sheet reports no margin of its own.
        val p = PrintPage.place(288f, 432f, PrintSize(10.0, 15.0), PageMargins.NONE)
        assertFalse(p.shrunk)
        assertEquals(PrintPage.cmToPt(10.0), p.widthPt, 0.01f)
        assertEquals(PrintPage.cmToPt(15.0), p.heightPt, 0.01f)
    }

    @Test
    fun `a margin the printer reports shrinks a print that reaches into it, and no further`() {
        // 0.12 in (8.64 pt) on each side of 4 x 6 paper: 10 x 15 cm no longer fits.
        val m = PageMargins.ofMils(120, 120, 120, 120)
        assertEquals(8.64f, m.leftPt, 0.001f)
        val p = PrintPage.place(288f, 432f, PrintSize(10.0, 15.0), m)
        assertTrue(p.shrunk)
        assertTrue(p.leftPt >= 8.64f - 0.01f && p.topPt >= 8.64f - 0.01f)
        assertTrue(p.leftPt + p.widthPt <= 288f - 8.64f + 0.01f)
        assertTrue(p.topPt + p.heightPt <= 432f - 8.64f + 0.01f)
        // 9 x 13 cm does fit inside it.
        assertFalse(PrintPage.place(288f, 432f, PrintSize(9.0, 13.0), m).shrunk)
    }

    @Test
    fun `margins differ per side and are kept per side`() {
        val m = PageMargins.ofMils(0, 500, 0, 0)
        val p = PrintPage.place(a4.first, a4.second, PrintSize(21.0, 29.7), m)
        assertTrue(p.shrunk)
        assertTrue(p.topPt >= 36f - 0.01f)
        assertTrue(p.leftPt >= -0.01f)
        assertTrue(p.leftPt + p.widthPt <= a4.first + 0.01f)
    }

    @Test
    fun `without a reported margin the default one is 1 cm on every side`() {
        val d = PageMargins.DEFAULT
        assertEquals(PrintPage.FIT_MARGIN_PT, d.leftPt, 0f)
        assertEquals(PrintPage.FIT_MARGIN_PT, d.bottomPt, 0f)
        assertTrue(PrintPage.place(a4.first, a4.second, PrintSize(21.0, 29.7)).shrunk)
    }
}
