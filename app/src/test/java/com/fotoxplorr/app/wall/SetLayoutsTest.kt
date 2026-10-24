package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetLayoutsTest {
    private val base = FrameSize("30 x 40 cm", 30f, 40f)

    /** The gap between the nearest edges of two borders, negative when they overlap. */
    private fun gap(a: PlacedBox, b: PlacedBox, border: Float): Float {
        val gx = kotlin.math.abs(a.dxCm - b.dxCm) - (a.wCm + b.wCm) / 2 - 2 * border
        val gy = kotlin.math.abs(a.dyCm - b.dyCm) - (a.hCm + b.hCm) / 2 - 2 * border
        return maxOf(gx, gy)
    }

    @Test
    fun `no two pictures overlap in any layout, with or without a thick border`() {
        for (border in listOf(0f, 3f, 10f)) {
            for (kind in SetKind.entries) {
                for (n in 1..kind.capacity) {
                    val boxes = SetLayouts.boxes(kind, n, base, border)
                    for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                        assertTrue("$kind $n border $border: ${gap(boxes[i], boxes[j], border)}", gap(boxes[i], boxes[j], border) >= SetLayouts.GAP_CM - 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `a layout holds as many boxes as pictures, up to its capacity`() {
        assertEquals(1, SetLayouts.boxes(SetKind.SINGLE, 4, base, 0f).size)
        assertEquals(2, SetLayouts.boxes(SetKind.PAIR, 2, base, 0f).size)
        assertEquals(1, SetLayouts.boxes(SetKind.PAIR, 0, base, 0f).size)
        assertEquals(3, SetLayouts.boxes(SetKind.TRIO, 3, base, 0f).size)
        assertEquals(6, SetLayouts.boxes(SetKind.GRID, 9, base, 0f).size)
    }

    @Test
    fun `a trio has a large middle picture and two smaller flanking it`() {
        val (mid, left, right) = SetLayouts.boxes(SetKind.TRIO, 3, base, 0f)
        assertEquals(30f, mid.wCm, 0f)
        assertEquals(21f, left.wCm, 1e-4f)
        assertEquals(-right.dxCm, left.dxCm, 1e-4f)
        assertTrue(left.dxCm < 0f)
    }

    @Test
    fun `a set is centred on its middle`() {
        for (kind in SetKind.entries) {
            val boxes = SetLayouts.boxes(kind, kind.capacity, base, 2f)
            val left = boxes.minOf { it.dxCm - it.wCm / 2 }
            val right = boxes.maxOf { it.dxCm + it.wCm / 2 }
            val top = boxes.minOf { it.dyCm - it.hCm / 2 }
            val bottom = boxes.maxOf { it.dyCm + it.hCm / 2 }
            assertEquals("$kind horizontal", 0f, left + right, 0.01f)
            assertEquals("$kind vertical", 0f, top + bottom, 0.01f)
        }
    }

    @Test
    fun `a grid of five puts three above two, the short row centred`() {
        val boxes = SetLayouts.boxes(SetKind.GRID, 5, base, 0f)
        assertEquals(3, boxes.count { it.dyCm == boxes[0].dyCm })
        val lastRow = boxes.takeLast(2)
        assertEquals(0f, lastRow[0].dxCm + lastRow[1].dxCm, 1e-4f)
    }

    @Test
    fun `the extent of a pair is two widths, a gap and the borders`() {
        val (w, h) = SetLayouts.extent(SetLayouts.boxes(SetKind.PAIR, 2, base, 2f), 2f)
        assertEquals(2 * 30f + SetLayouts.GAP_CM + 4 * 2f, w, 0.01f)
        assertEquals(40f + 4f, h, 0.01f)
    }

    @Test
    fun `the biggest size that fits shrinks as the wall does, and never goes below the smallest`() {
        val roomy = SetLayouts.biggestThatFits(SetKind.PAIR, 2, 400f, 250f, 3f)
        val tight = SetLayouts.biggestThatFits(SetKind.PAIR, 2, 100f, 60f, 3f)
        assertTrue(roomy.wCm * roomy.hCm > tight.wCm * tight.hCm)
        assertEquals(FramePresets.all.minBy { it.wCm * it.hCm }, SetLayouts.biggestThatFits(SetKind.GRID, 6, 10f, 10f, 3f))
    }

    @Test
    fun `what it picks really fits the share of the wall`() {
        val size = SetLayouts.biggestThatFits(SetKind.TRIO, 3, 300f, 200f, 3f)
        val (w, h) = SetLayouts.extent(SetLayouts.boxes(SetKind.TRIO, 3, size, 3f), 3f)
        assertTrue(w <= 300f * SetLayouts.WALL_SHARE_W)
        assertTrue(h <= 200f * SetLayouts.WALL_SHARE_H)
    }

    @Test
    fun `only a poster may go taller than a set`() {
        val poster = SetLayouts.biggestThatFits(SetKind.POSTER, 1, 500f, 400f, 3f)
        val single = SetLayouts.biggestThatFits(SetKind.SINGLE, 1, 500f, 400f, 3f)
        assertTrue(poster.hCm > 90f)
        assertTrue(single.hCm <= SetLayouts.SET_MAX_HEIGHT_CM)
    }

    @Test
    fun `a cell is the preset as it is, turned, or square on its long side`() {
        val preset = FrameSize("A4", 21f, 29.7f)
        assertEquals(preset, SetLayouts.cell(preset, SetOrientation.PORTRAIT))
        assertEquals(29.7f to 21f, SetLayouts.cell(preset, SetOrientation.LANDSCAPE).let { it.wCm to it.hCm })
        assertEquals(29.7f to 29.7f, SetLayouts.cell(preset, SetOrientation.MIXED).let { it.wCm to it.hCm })
    }

    @Test
    fun `the orientation of a set is all upright, all lying, or mixed`() {
        assertEquals(SetOrientation.PORTRAIT, SetOrientation.of(listOf(false, false)))
        assertEquals(SetOrientation.PORTRAIT, SetOrientation.of(emptyList()))
        assertEquals(SetOrientation.LANDSCAPE, SetOrientation.of(listOf(true, true)))
        assertEquals(SetOrientation.MIXED, SetOrientation.of(listOf(true, false)))
    }

    @Test
    fun `what it picks for lying pictures really fits the share of the wall`() {
        for (orientation in SetOrientation.entries) {
            val size = SetLayouts.biggestThatFits(SetKind.GRID, 6, 300f, 200f, 3f, orientation)
            val (w, h) = SetLayouts.extent(SetLayouts.boxes(SetKind.GRID, 6, SetLayouts.cell(size, orientation), 3f), 3f)
            assertTrue("$orientation $w", w <= 300f * SetLayouts.WALL_SHARE_W)
            assertTrue("$orientation $h", h <= 200f * SetLayouts.WALL_SHARE_H)
        }
    }
}
