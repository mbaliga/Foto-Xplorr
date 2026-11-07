package com.fotoxplorr.app.viewer.colour

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pro card's words live in its Copy object, and the analysis copy of the photo is recycled once measured. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProOverlayCopyTest {
    @Test
    fun `the clipping line reads both shares`() {
        assertEquals("Highlights 2.0%  Shadows <0.1%", ProOverlayCopy.clippingLine(0.02f, 0.0005f))
        assertEquals("Highlights 0.0%  Shadows 12.5%", ProOverlayCopy.clippingLine(0f, 0.125f))
    }

    @Test
    fun `the histogram has a description`() {
        assertEquals("Histogram of the photo's tones", ProOverlayCopy.HISTOGRAM_DESCRIPTION)
    }

    @Test
    fun `the analysis bitmap is recycled after its pixels are copied out`() {
        val bmp = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val analysis = analyseAndRecycle(bmp)
        assertTrue(bmp.isRecycled)
        assertEquals(4, analysis.width)
        assertEquals(2, analysis.height)
        // All white: every pixel is a blown highlight, measured before the recycle.
        assertEquals(1f, analysis.clipping.highlights, 1e-6f)
    }
}
