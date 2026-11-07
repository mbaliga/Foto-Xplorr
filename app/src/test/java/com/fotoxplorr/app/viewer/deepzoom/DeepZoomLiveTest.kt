package com.fotoxplorr.app.viewer.deepzoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom ceiling follows whether tiles REALLY run, a translucent file is never tiled over its own
 * base picture, and a ceiling that drops under the current zoom pulls the zoom down with its pan.
 */
class DeepZoomLiveTest {
    // A 100 MP photo on a 1080 x 2400 screen: 1:1 is far past the old ceiling.
    private val w = 12_000
    private val h = 8_000
    private val cw = 1080f
    private val ch = 2400f

    @Test
    fun `tiles wanted but the decoder never opened keep the old ceiling and no 1-1 tap`() {
        val live = DeepZoomPolicy.tilesLive(tilesWanted = true, decoderOpen = false)
        assertFalse(live)
        assertEquals(DeepZoomPolicy.BASE_MAX_SCALE, DeepZoomPolicy.maxScaleFor(live, w, h, cw, ch), 1e-4f)
        val oneToOne = DeepZoomPolicy.oneToOneScale(w, h, cw, ch)
        assertEquals(1f, DeepZoomPolicy.oneToOneFor(live, oneToOne), 1e-4f)
        assertEquals(DeepZoomPolicy.SMALL_PHOTO_TAP_SCALE, DeepZoomPolicy.doubleTapTarget(1f, false, DeepZoomPolicy.oneToOneFor(live, oneToOne)), 1e-4f)
    }

    @Test
    fun `an opened decoder raises the ceiling past 1-1`() {
        val live = DeepZoomPolicy.tilesLive(tilesWanted = true, decoderOpen = true)
        assertTrue(live)
        val oneToOne = DeepZoomPolicy.oneToOneScale(w, h, cw, ch)
        assertTrue(DeepZoomPolicy.maxScaleFor(live, w, h, cw, ch) >= oneToOne)
        assertFalse(DeepZoomPolicy.tilesLive(tilesWanted = false, decoderOpen = true))
    }

    @Test
    fun `a JPEG tile is always drawable`() {
        assertTrue(DeepZoomPolicy.tileDrawable("image/jpeg", tileHasAlpha = false))
        assertTrue(DeepZoomPolicy.tileDrawable("IMAGE/JPEG", tileHasAlpha = true))
    }

    @Test
    fun `a PNG or WebP tile with alpha is not drawn over the base`() {
        assertFalse(DeepZoomPolicy.tileDrawable("image/png", tileHasAlpha = true))
        assertFalse(DeepZoomPolicy.tileDrawable("image/webp", tileHasAlpha = true))
        assertFalse(DeepZoomPolicy.tileDrawable(null, tileHasAlpha = true))
    }

    @Test
    fun `an opaque PNG still tiles`() {
        assertTrue(DeepZoomPolicy.tileDrawable("image/png", tileHasAlpha = false))
    }

    @Test
    fun `drawing a translucent tile over its base would composite twice`() {
        // Why the rule exists: alpha 0.5 over the same alpha 0.5 shows as 0.75.
        val a = 0.5f
        assertEquals(0.75f, 1f - (1f - a) * (1f - a), 1e-6f)
    }

    @Test
    fun `a ceiling under the zoom pulls zoom and pan down together`() {
        val (s, ox, oy) = DeepZoomPolicy.clampToCeiling(30f, 600f, -300f, 6f)
        assertEquals(6f, s, 1e-4f)
        assertEquals(120f, ox, 1e-3f)
        assertEquals(-60f, oy, 1e-3f)
        // The point at the centre stays the centre: -offset / scale is unchanged.
        assertEquals(-600f / 30f, -ox / s, 1e-4f)
    }

    @Test
    fun `a zoom inside the ceiling is left alone`() {
        assertEquals(Triple(4f, 10f, 20f), DeepZoomPolicy.clampToCeiling(4f, 10f, 20f, 6f))
        assertEquals(Triple(6f, 10f, 20f), DeepZoomPolicy.clampToCeiling(6f, 10f, 20f, 6f))
    }
}
