package com.fotoxplorr.app.viewer.deepzoom

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A file whose region decode has alpha (any RGBA PNG, even a fully opaque one) is found out when the
 * decoder OPENS, from one tiny probe region, so tiles are never reported live for it. Finding it from
 * the first tile instead raised the ceiling and the 1:1 double-tap, then snapped the zoom back to 6x
 * some 70 ms later. (Robolectric's BitmapRegionDecoder returns null, so the open itself and the
 * layer's timing are device checks; the decision is tested here with real bitmaps.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DeepZoomAlphaProbeTest {
    private fun region(hasAlpha: Boolean): Bitmap =
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(40, 120, 200))
            setHasAlpha(hasAlpha)
        }

    @Test
    fun `an RGBA PNG is not drawable from its probe, even when every pixel is opaque`() {
        val probe = region(hasAlpha = true)
        assertFalse(RegionTileDecoder.drawableAfterProbe("image/png") { probe })
        assertTrue("the probe region is recycled", probe.isRecycled)
    }

    @Test
    fun `a WebP with alpha is not drawable either`() {
        assertFalse(RegionTileDecoder.drawableAfterProbe("image/webp") { region(hasAlpha = true) })
    }

    @Test
    fun `an opaque PNG or WebP is drawable`() {
        assertTrue(RegionTileDecoder.drawableAfterProbe("image/png") { region(hasAlpha = false) })
        assertTrue(RegionTileDecoder.drawableAfterProbe("image/webp") { region(hasAlpha = false) })
    }

    @Test
    fun `a JPEG is drawable without decoding a probe`() {
        var probes = 0
        assertTrue(RegionTileDecoder.drawableAfterProbe("image/jpeg") { probes++; region(hasAlpha = true) })
        assertEquals(0, probes)
    }

    @Test
    fun `a probe that fails or throws means no tiles`() {
        assertFalse(RegionTileDecoder.drawableAfterProbe("image/png") { null })
        assertFalse(RegionTileDecoder.drawableAfterProbe("image/png") { throw IllegalArgumentException("bad region") })
    }

    @Test
    fun `only formats that may carry alpha are probed`() {
        assertFalse(DeepZoomPolicy.needsAlphaProbe("image/jpeg"))
        assertFalse(DeepZoomPolicy.needsAlphaProbe("IMAGE/JPG"))
        assertTrue(DeepZoomPolicy.needsAlphaProbe("image/png"))
        assertTrue(DeepZoomPolicy.needsAlphaProbe("image/webp"))
        assertTrue(DeepZoomPolicy.needsAlphaProbe("image/heic"))
        assertTrue(DeepZoomPolicy.needsAlphaProbe(null))
    }
}
