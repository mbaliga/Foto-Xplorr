package com.fotoxplorr.app.basemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyAndLabelsTest {
    @Test fun `a straight run collapses to its ends but a corner survives`() {
        val line = IntArray(2 * 11) { if (it % 2 == 0) (it / 2) * 100 else 5000 }
        assertEquals(4, Simplify.simplify(line, 50.0).size)
        val bent = intArrayOf(0, 0, 1000, 0, 2000, 2000)
        assertEquals(6, Simplify.simplify(bent, 50.0).size)
    }

    @Test fun `fine tolerance keeps everything and ends are always kept`() {
        val pts = intArrayOf(0, 0, 100, 3, 200, -2, 300, 1)
        assertEquals(pts.toList(), Simplify.simplify(pts, 0.1).toList())
        val s = Simplify.simplify(pts, 10_000.0)
        assertEquals(listOf(0, 0, 300, 1), s.toList())
    }

    @Test fun `tolerance halves with each detail bucket`() {
        assertEquals(Simplify.toleranceUnits(0) / 2, Simplify.toleranceUnits(1), 1e-9)
        assertTrue(Simplify.toleranceUnits(6) < 1.0)
    }

    @Test fun `buckets follow the world's size`() {
        assertEquals(0, SimplifiedBasemap.bucketFor(500.0))
        assertEquals(0, SimplifiedBasemap.bucketFor(1000.0))
        assertEquals(1, SimplifiedBasemap.bucketFor(2000.0))
        assertEquals(SimplifiedBasemap.MAX_BUCKET, SimplifiedBasemap.bucketFor(1e9))
    }

    @Test fun `labels are kept by rank and a lower rank never displaces a higher one`() {
        val c = listOf(
            LabelCandidate(rank = 5, x = 100f, y = 100f, width = 60f, height = 14f),
            LabelCandidate(rank = 0, x = 110f, y = 105f, width = 60f, height = 14f),
            LabelCandidate(rank = 2, x = 300f, y = 300f, width = 60f, height = 14f),
        )
        val placed = LabelPlacer.place(c, 400f, 400f)
        assertEquals(listOf(1, 2), placed.map { it.index })
    }

    @Test fun `labels that would leave the viewport are dropped and the count is capped`() {
        val c = listOf(LabelCandidate(0, 390f, 100f, 60f, 14f), LabelCandidate(1, 10f, 2f, 60f, 14f))
        assertTrue(LabelPlacer.place(c, 400f, 400f).none { it.index == 0 })
        val many = (0 until 100).map { LabelCandidate(it, it * 100f, 50f, 10f, 10f) }
        assertEquals(5, LabelPlacer.place(many, 10_000f, 100f, maxLabels = 5).size)
    }

    @Test fun `more cities are allowed as the map zooms in`() {
        val a = LabelPlacer.maxRankForLevel(0.0, 1000)
        val b = LabelPlacer.maxRankForLevel(4.0, 1000)
        assertTrue(a < b)
        assertEquals(1000, LabelPlacer.maxRankForLevel(8.0, 1000))
        assertEquals(5, LabelPlacer.maxRankForLevel(0.0, 5))
    }
}
