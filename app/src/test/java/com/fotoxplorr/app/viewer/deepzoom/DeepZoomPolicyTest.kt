package com.fotoxplorr.app.viewer.deepzoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepZoomPolicyTest {
    // A 100 MP landscape photo on a 1080 x 2400 portrait screen: fitted to the width.
    private val oneToOne = DeepZoomPolicy.oneToOneScale(11_550, 8_660, 1080f, 2400f)

    @Test
    fun `one to one on a 100 MP photo is about ten times the fit scale`() {
        assertEquals(11_550f / 1080f, oneToOne, 1e-3f)
    }

    @Test
    fun `max scale reaches twice 1 to 1 on a big photo and keeps 6 on a small one`() {
        assertEquals(2f * oneToOne, DeepZoomPolicy.maxScale(11_550, 8_660, 1080f, 2400f), 1e-3f)
        assertEquals(6f, DeepZoomPolicy.maxScale(800, 600, 1080f, 2400f), 0f)
    }

    @Test
    fun `max scale is capped`() {
        assertEquals(64f, DeepZoomPolicy.maxScale(60_000, 60_000, 360f, 800f), 0f)
    }

    @Test
    fun `double tap cycles fit to 1 to 1 to fit`() {
        val up = DeepZoomPolicy.doubleTapTarget(1f, rotated = false, oneToOne = oneToOne)
        assertEquals(oneToOne, up, 1e-4f)
        assertEquals(1f, DeepZoomPolicy.doubleTapTarget(up, rotated = false, oneToOne = oneToOne), 0f)
    }

    @Test
    fun `double tap on a pinched or turned photo goes back to fit`() {
        assertEquals(1f, DeepZoomPolicy.doubleTapTarget(3.3f, false, oneToOne), 0f)
        assertEquals(1f, DeepZoomPolicy.doubleTapTarget(1f, true, oneToOne), 0f)
    }

    @Test
    fun `a small photo keeps the old fixed double tap zoom`() {
        val small = DeepZoomPolicy.oneToOneScale(800, 600, 1080f, 2400f)
        assertTrue(small < 1f)
        assertEquals(2.5f, DeepZoomPolicy.doubleTapTarget(1f, false, small), 0f)
    }

    @Test
    fun `unknown size is neutral`() {
        assertEquals(1f, DeepZoomPolicy.oneToOneScale(0, 0, 1080f, 2400f), 0f)
        assertEquals(1f, DeepZoomPolicy.oneToOneScale(100, 100, 0f, 0f), 0f)
    }

    @Test
    fun `tiles run only for region readable big non animated unfiltered photos`() {
        assertTrue(DeepZoomPolicy.tilesWorthRunning("image/jpeg", oneToOne, false, false))
        assertFalse(DeepZoomPolicy.tilesWorthRunning("image/gif", oneToOne, false, false))
        assertFalse(DeepZoomPolicy.tilesWorthRunning(null, oneToOne, false, false))
        assertFalse(DeepZoomPolicy.tilesWorthRunning("image/jpeg", 1.1f, false, false))
        assertFalse(DeepZoomPolicy.tilesWorthRunning("image/webp", oneToOne, true, false))
        assertFalse(DeepZoomPolicy.tilesWorthRunning("image/png", oneToOne, false, true))
    }

    @Test
    fun `level follows the zoom down from 1 to 1`() {
        val s = oneToOne
        assertEquals(1, TilePyramid.sampleFor(DeepZoomPolicy.pixelsPerSourcePx(s, oneToOne)))
        assertEquals(2, TilePyramid.sampleFor(DeepZoomPolicy.pixelsPerSourcePx(s / 2f, oneToOne)))
        assertEquals(8, TilePyramid.sampleFor(DeepZoomPolicy.pixelsPerSourcePx(s / 8f, oneToOne)))
    }

    @Test
    fun `without tiles the old ceiling and the fixed double tap zoom apply`() {
        assertEquals(6f, DeepZoomPolicy.maxScaleFor(false, 11_550, 8_660, 1080f, 2400f), 0f)
        assertEquals(DeepZoomPolicy.maxScale(11_550, 8_660, 1080f, 2400f), DeepZoomPolicy.maxScaleFor(true, 11_550, 8_660, 1080f, 2400f), 0f)
        assertEquals(1f, DeepZoomPolicy.oneToOneFor(false, oneToOne), 0f)
        assertEquals(2.5f, DeepZoomPolicy.doubleTapTarget(1f, false, DeepZoomPolicy.oneToOneFor(false, oneToOne)), 0f)
        assertEquals(oneToOne, DeepZoomPolicy.oneToOneFor(true, oneToOne), 0f)
    }
}
