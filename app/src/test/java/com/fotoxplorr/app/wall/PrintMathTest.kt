package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintMathTest {

    @Test
    fun `pixels for a size follow the dpi and round to the nearest pixel`() {
        assertEquals(1754, PrintMath.pixelsForCm(29.7, 150))
        assertEquals(1240, PrintMath.pixelsForCm(21.0, 150))
        assertEquals(2953, PrintMath.pixelsForCm(50.0, 150))
        assertEquals(2400, PrintMath.pixelsForInches(8.0, 300))
    }

    @Test
    fun `effective dpi of a 12 megapixel photo at its own size is about 150`() {
        assertEquals(150.0, PrintMath.effectiveDpi(4000, 3000, 67.7, 50.8), 0.2)
    }

    @Test
    fun `a frame of another shape crops the photo and the dpi is set by the tighter axis`() {
        // 4:3 landscape photo into a 5:7 portrait frame 50 cm tall: the height is the tight axis
        // (3000 px over 50 cm), the width is cropped to 5/7 of 50 cm = 35.7 cm out of 4000 px.
        assertEquals(2.54 * 3000 / 50.0, PrintMath.effectiveDpi(4000, 3000, 35.714, 50.0), 0.01)
        val crop = PrintMath.coverCrop(4000, 3000, 5.0, 7.0)
        assertEquals((5.0 / 7.0) / (4.0 / 3.0), crop.keptWidth, 1e-9)
        assertEquals(1.0, crop.keptHeight, 0.0)
        assertTrue(crop.isCropped)
    }

    @Test
    fun `a 3 by 2 photo into a square keeps two thirds of its width`() {
        val crop = PrintMath.coverCrop(3000, 2000, 30.0, 30.0)
        assertEquals(2.0 / 3.0, crop.keptWidth, 1e-9)
        assertEquals(1.0, crop.keptHeight, 0.0)
    }

    @Test
    fun `a frame of the photo's own shape crops nothing`() {
        assertFalse(PrintMath.coverCrop(4000, 3000, 40.0, 30.0).isCropped)
    }

    @Test
    fun `quality bands meet at 300, 200 and 150, and exactly 150 is fine`() {
        assertEquals(PrintQuality.EXCELLENT, PrintQuality.of(300.0))
        assertEquals(PrintQuality.GOOD, PrintQuality.of(299.9))
        assertEquals(PrintQuality.GOOD, PrintQuality.of(200.0))
        assertEquals(PrintQuality.FINE, PrintQuality.of(199.9))
        assertEquals(PrintQuality.FINE, PrintQuality.of(150.0))
        assertEquals(PrintQuality.SOFT, PrintQuality.of(149.9))
        assertEquals(PrintQuality.SOFT, PrintQuality.of(0.0))
    }

    @Test
    fun `the largest print at 150 dpi of a 4000 by 3000 photo is 67 by 50 cm`() {
        val (w, h) = PrintMath.largestCm(4000, 3000)
        assertEquals(67.73, w, 0.01)
        assertEquals(50.8, h, 0.01)
        assertEquals(67, PrintMath.floorCm(w))
        assertEquals(50, PrintMath.floorCm(h))
    }

    @Test
    fun `the largest print of another shape is limited by the tighter axis`() {
        // 4000 x 3000 into a 2:3 portrait: height is limited by 3000 px (50.8 cm), width = 33.9 cm.
        val (w, h) = PrintMath.largestCm(4000, 3000, 2.0 / 3.0)
        assertEquals(50.8, h, 0.01)
        assertEquals(33.87, w, 0.01)
        // Whatever size comes back, it really does reach the dpi it was asked for.
        assertEquals(150.0, PrintMath.effectiveDpi(4000, 3000, w, h), 0.01)
    }

    @Test
    fun `floorCm drops the fraction but forgives float noise`() {
        assertEquals(50, PrintMath.floorCm(50.79))
        assertEquals(51, PrintMath.floorCm(50.999999999))
    }

    @Test
    fun `nonsense sizes give zeros, not a division by zero`() {
        assertEquals(0.0, PrintMath.effectiveDpi(0, 3000, 30.0, 40.0), 0.0)
        assertEquals(0.0, PrintMath.effectiveDpi(4000, 3000, 0.0, 40.0), 0.0)
        assertEquals(0.0 to 0.0, PrintMath.largestCm(0, 0))
        assertFalse(PrintMath.coverCrop(0, 0, 10.0, 10.0).isCropped)
    }
}
