package com.fotoxplorr.app.viewer.deepzoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePyramidTest {

    @Test
    fun `sample is the coarsest level that is still sharp`() {
        assertEquals(1, TilePyramid.sampleFor(1f))
        assertEquals(1, TilePyramid.sampleFor(3f)) // magnified past 1:1: no finer level exists
        assertEquals(2, TilePyramid.sampleFor(0.5f))
        assertEquals(2, TilePyramid.sampleFor(0.4f)) // 1/4 < 0.4 so a 4x sample would blur
        assertEquals(4, TilePyramid.sampleFor(0.25f))
        assertEquals(TilePyramid.MAX_SAMPLE, TilePyramid.sampleFor(0.0001f))
    }

    @Test
    fun `nonsense magnification falls to the coarsest level`() {
        assertEquals(TilePyramid.MAX_SAMPLE, TilePyramid.sampleFor(0f))
        assertEquals(TilePyramid.MAX_SAMPLE, TilePyramid.sampleFor(Float.NaN))
        assertEquals(8, TilePyramid.sampleFor(-1f, maxSample = 8))
    }

    @Test
    fun `tile rects tile the image exactly with ragged edges`() {
        val w = 1300
        val h = 700
        val keys = TilePyramid.tilesCovering(w, h, 0f, 0f, 1f, 1f, sample = 1)
        assertEquals(3 * 2, keys.size)
        val area = keys.sumOf { k -> TilePyramid.rectOf(k, w, h).let { it.width.toLong() * it.height } }
        assertEquals(w.toLong() * h, area)
        val last = TilePyramid.rectOf(TileKey(1, 2, 1), w, h)
        assertEquals(SourceRect(1024, 512, 1300, 700), last)
    }

    @Test
    fun `a coarser level spans proportionally more source`() {
        assertEquals(2048, TilePyramid.span(4))
        val r = TilePyramid.rectOf(TileKey(4, 1, 0), 10_000, 8_000)
        assertEquals(SourceRect(2048, 0, 4096, 2048), r)
    }

    @Test
    fun `only tiles touching the window are returned, centre first`() {
        // 10000 x 10000, a window of the middle ~10%: a handful of level-1 tiles.
        val keys = TilePyramid.tilesCovering(10_000, 10_000, 0.45f, 0.45f, 0.55f, 0.55f, sample = 1)
        assertTrue(keys.size in 4..9)
        val first = TilePyramid.rectOf(keys.first(), 10_000, 10_000)
        // The first tile contains (or is adjacent to) the window's centre, 5000,5000.
        assertTrue(first.left <= 5000 && first.right >= 5000 - 512)
        assertTrue(keys.all { it.sample == 1 })
    }

    @Test
    fun `margin adds a ring of tiles and an empty window adds none`() {
        val plain = TilePyramid.tilesCovering(8000, 8000, 0.4f, 0.4f, 0.5f, 0.5f, 1, marginTiles = 0)
        val ringed = TilePyramid.tilesCovering(8000, 8000, 0.4f, 0.4f, 0.5f, 0.5f, 1, marginTiles = 1)
        assertTrue(ringed.size > plain.size)
        assertTrue(ringed.containsAll(plain))
        assertTrue(TilePyramid.tilesCovering(8000, 8000, 0.5f, 0.5f, 0.5f, 0.5f, 1).isEmpty())
        assertTrue(TilePyramid.tilesCovering(0, 0, 0f, 0f, 1f, 1f, 1).isEmpty())
    }

    @Test
    fun `a window never returns more than the cap`() {
        val keys = TilePyramid.tilesCovering(40_000, 40_000, 0f, 0f, 1f, 1f, sample = 1)
        assertEquals(TilePyramid.MAX_TILES_PER_VIEW, keys.size)
    }

    @Test
    fun `window past the image edge is clamped not crashed`() {
        val keys = TilePyramid.tilesCovering(1000, 1000, -0.5f, -0.5f, 2f, 2f, sample = 1)
        assertEquals(4, keys.size)
    }
}
