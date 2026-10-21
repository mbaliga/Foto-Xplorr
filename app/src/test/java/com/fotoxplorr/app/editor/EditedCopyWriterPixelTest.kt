package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [compositeOntoWhiteIfNeeded] — bug #5: a translucent photo saved into an alpha-less format
 * (JPEG, lossy WebP) must composite onto WHITE, matching what a viewer expects from "no colour
 * was ever there", not the solid BLACK `Bitmap.compress` produces by silently dropping alpha off
 * premultiplied colour.
 *
 * NATIVE graphics mode, the same reason [MetadataWriterTest] uses it: this asserts on actual
 * drawn pixel VALUES after a `Canvas.drawColor`/`drawBitmap` pair, and the legacy Robolectric
 * shadow does not rasterize those calls into the bitmap's own pixel buffer at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditedCopyWriterPixelTest {

    @Test
    fun `a transparent pixel composites to opaque white, an opaque one is untouched`() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.TRANSPARENT)
        source.setPixel(1, 0, Color.RED)

        val flattened = compositeOntoWhiteIfNeeded(source, OutputFormat.JPEG)

        assertEquals(Color.WHITE, flattened.getPixel(0, 0))
        assertEquals(Color.RED, flattened.getPixel(1, 0))
    }

    @Test
    fun `a format that keeps its own alpha channel is left alone, same bitmap instance`() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.TRANSPARENT)
        assertSame(source, compositeOntoWhiteIfNeeded(source, OutputFormat.PNG))
    }

    @Test
    fun `an opaque bitmap is never composited, even into an alpha-less format`() {
        val opaque = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        opaque.setPixel(0, 0, Color.BLUE)
        opaque.setHasAlpha(false)
        assertSame(opaque, compositeOntoWhiteIfNeeded(opaque, OutputFormat.JPEG))
    }

    @Test
    fun `JPEG and lossy WebP encode no alpha, PNG always does`() {
        assertTrue(!OutputFormat.JPEG.encodesAlpha())
        assertTrue(OutputFormat.PNG.encodesAlpha())
        assertTrue(!OutputFormat.HEIC.encodesAlpha())
    }
}
