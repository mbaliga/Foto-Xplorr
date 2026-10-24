package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePresetsTest {

    @Test
    fun `the list holds the eight metric and four inch sizes, metric first`() {
        val labels = FramePresets.all.map { it.label }
        assertEquals(12, labels.size)
        assertEquals(listOf("A4", "A3", "A2"), labels.take(3))
        assertEquals("61 x 91 cm", labels[7])
        assertEquals(listOf("8 x 10 in", "11 x 14 in", "16 x 20 in", "24 x 36 in"), labels.takeLast(4))
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `every preset is portrait and positive`() {
        FramePresets.all.forEach { assertTrue(it.label, it.wCm in FramePresets.MIN_SIDE_CM..it.hCm) }
    }

    @Test
    fun `the inch sizes are the inches times 2,54`() {
        val eight = FramePresets.all.first { it.label == "8 x 10 in" }
        assertEquals(8 * 2.54f, eight.wCm, 0.01f)
        assertEquals(10 * 2.54f, eight.hCm, 0.01f)
        assertEquals("8 x 10 in", FramePresets.sizeLabel(eight.wCm, eight.hCm, inches = true))
        assertEquals("20.3 x 25.4 cm", FramePresets.sizeLabel(eight.wCm, eight.hCm, inches = false))
    }

    @Test
    fun `sizes print without a trailing zero`() {
        assertEquals("30", FramePresets.number(30f))
        assertEquals("29.7", FramePresets.number(29.7f))
        assertEquals("30", FramePresets.number(29.98f))
    }

    @Test
    fun `a size within six percent snaps to the preset, in the orientation it was given`() {
        assertEquals(30f to 40f, FramePresets.snap(30.8f, 39.2f))
        assertEquals(40f to 30f, FramePresets.snap(40.9f, 29.6f))
    }

    @Test
    fun `a size between presets does not snap`() {
        assertNull(FramePresets.snap(35f, 45f))
    }

    @Test
    fun `a size near two presets takes the closer`() {
        // 20 x 30 and A4 (21 x 29.7) are close; 20.1 x 30 is nearer 20 x 30.
        assertEquals(20f to 30f, FramePresets.snap(20.1f, 30f))
    }

    @Test
    fun `scaling keeps the shape and stays inside the limits`() {
        val (w, h) = FramePresets.scaled(20f, 30f, 2f)
        assertEquals(40f, w, 1e-4f)
        assertEquals(60f, h, 1e-4f)
        val (sw, sh) = FramePresets.scaled(20f, 30f, 0.001f)
        assertEquals(FramePresets.MIN_SIDE_CM, sw, 1e-3f)
        assertEquals(FramePresets.MIN_SIDE_CM * 1.5f, sh, 1e-3f)
        val (bw, bh) = FramePresets.scaled(20f, 30f, 1000f)
        assertEquals(FramePresets.MAX_SIDE_CM, bh, 1e-2f)
        assertEquals(FramePresets.MAX_SIDE_CM * 2 / 3f, bw, 1e-2f)
    }

    @Test
    fun `a point is inside a turned frame only where the frame is`() {
        // A 40 x 10 box turned 90 degrees stands 10 wide and 40 tall.
        assertTrue(FramePresets.hits(0f, 0f, 40f, 10f, 90f, 0f, 18f))
        assertFalse(FramePresets.hits(0f, 0f, 40f, 10f, 90f, 18f, 0f))
        assertTrue(FramePresets.hits(100f, 50f, 40f, 10f, 0f, 118f, 50f))
        assertFalse(FramePresets.hits(100f, 50f, 40f, 10f, 0f, 100f, 60f))
    }

    @Test
    fun `every style has a key that finds it again`() {
        FrameStyle.entries.forEach { assertEquals(it, FrameStyle.fromKey(it.key)) }
        assertNull(FrameStyle.fromKey("gold"))
        assertEquals(0f, FrameStyle.NONE.moldingCm, 0f)
        assertNotNull(FrameStyle.WOOD)
    }
}
