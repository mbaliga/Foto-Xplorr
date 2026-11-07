package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JustifiedLayoutTest {
    private fun build(aspects: List<Float>, w: Float = 1000f, target: Float = 200f, gap: Float = 4f) =
        JustifiedLayout.build(aspects.size, { aspects[it] }, w, target, gap)

    @Test
    fun `an empty library has no rows`() {
        val l = build(emptyList())
        assertEquals(0, l.rows)
        assertEquals(0f, l.totalHeight, 0f)
        assertEquals(-1, l.rowAtOffset(10f))
        assertEquals(-1, l.rowOf(0))
    }

    @Test
    fun `every full row spans the width exactly`() {
        val l = build(List(40) { if (it % 3 == 0) 0.75f else 1.5f })
        for (r in 0 until l.rows) {
            if (!l.isJustified(r)) continue
            val items = l.rowItems(r)
            val sum = items.sumOf { l.itemWidth(it).toDouble() } + 4.0 * (items.count() - 1)
            assertEquals("row $r", 1000.0, sum, 0.01)
        }
    }

    @Test
    fun `rows stay near the target height`() {
        val l = build(List(200) { 0.6f + (it % 7) * 0.2f })
        for (r in 0 until l.rows - 1) {
            assertTrue("row $r h=${l.rowHeight(r)}", l.rowHeight(r) in 120f..300f)
        }
    }

    @Test
    fun `every item is in exactly one row, in order`() {
        val l = build(List(57) { 1.2f })
        var next = 0
        for (r in 0 until l.rows) {
            for (i in l.rowItems(r)) { assertEquals(next, i); next++ }
        }
        assertEquals(57, next)
    }

    @Test
    fun `a short last row keeps the target height instead of ballooning`() {
        val l = build(List(6) { 1.5f })
        val last = l.rows - 1
        if (!l.isJustified(last)) assertEquals(200f, l.rowHeight(last), 0.01f)
        assertTrue(l.rowHeight(last) <= 200f / JustifiedLayout.LAST_ROW_STRETCH_FROM + 0.01f)
    }

    @Test
    fun `a lone item narrower than the width is not stretched past its row`() {
        val l = build(listOf(1.5f))
        assertEquals(1, l.rows)
        assertFalse(l.isJustified(0))
        assertEquals(200f, l.rowHeight(0), 0.01f)
    }

    @Test
    fun `a panorama alone fills its row`() {
        val l = build(listOf(5f, 1.5f, 1.5f))
        assertEquals(1, l.rowItems(0).count())
        assertTrue(l.isJustified(0))
        assertEquals(1000f / 5f, l.rowHeight(0), 0.01f)
    }

    @Test
    fun `bad aspects fall back instead of breaking a row`() {
        val l = build(listOf(Float.NaN, 0f, -1f, 1000f, 1.5f))
        assertEquals(5, l.itemCount)
        for (r in 0 until l.rows) assertTrue(l.rowHeight(r) > 0f && l.rowHeight(r).isFinite())
        assertEquals(JustifiedLayout.FALLBACK_ASPECT, JustifiedLayout.normalizedAspect(Float.NaN), 0f)
        assertEquals(JustifiedLayout.MAX_ASPECT, JustifiedLayout.normalizedAspect(1000f), 0f)
    }

    @Test
    fun `rowOf and rowAtOffset agree with the row tops`() {
        val l = build(List(120) { 1f + (it % 5) * 0.3f })
        for (r in 0 until l.rows) {
            assertEquals(r, l.rowAtOffset(l.rowTop(r) + 0.1f))
            for (i in l.rowItems(r)) assertEquals(r, l.rowOf(i))
        }
        assertEquals(l.rows - 1, l.rowAtOffset(1e9f))
        assertEquals(0, l.rowAtOffset(-5f))
        assertEquals(-1, l.rowOf(120))
    }

    @Test
    fun `total height is the rows plus the gaps between them`() {
        val l = build(List(30) { 1.5f })
        val expected = (0 until l.rows).sumOf { l.rowHeight(it).toDouble() } + 4.0 * (l.rows - 1)
        assertEquals(expected, l.totalHeight.toDouble(), 0.05)
    }

    @Test
    fun `item x offsets follow widths and gaps`() {
        val l = build(List(10) { 1.5f })
        val row = l.rowItems(0).toList()
        assertEquals(0f, l.itemX(row[0]), 0f)
        assertEquals(l.itemWidth(row[0]) + 4f, l.itemX(row[1]), 0.01f)
    }

    @Test
    fun `degenerate sizes give an empty layout`() {
        assertEquals(0, JustifiedLayout.build(10, { 1f }, 0f, 200f, 4f).rows)
        assertEquals(0, JustifiedLayout.build(10, { 1f }, 500f, 0f, 4f).rows)
    }

    @Test
    fun `half a million items lay out in one cheap pass`() {
        val t = System.nanoTime()
        val l = JustifiedLayout.build(500_000, { 0.7f + (it % 11) * 0.12f }, 1080f, 240f, 4f)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue(l.rows > 10_000)
        assertTrue("took $ms ms", ms < 3000)
        assertEquals(499_999, l.rowItems(l.rows - 1).last)
    }
}
