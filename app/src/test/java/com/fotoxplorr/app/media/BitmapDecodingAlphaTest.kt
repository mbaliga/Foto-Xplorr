package com.fotoxplorr.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.io.path.createTempFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [decodeUpright] must keep a transparent photo transparent (owner, #17). It is not the viewer's
 * display path (that is Coil), but the lift editor, share and the editor decode through it, and a
 * sticker flattened onto black on the way in would be flattened for good.
 *
 * A 4 x 2 PNG: the left half fully transparent, the right half opaque red, through the
 * [android.graphics.ImageDecoder] path (API 28 and up). NATIVE graphics, like
 * [BitmapDecodingImageDecoderExifTest], because the legacy shadow decodes nothing real. The
 * [android.graphics.BitmapFactory] fallback below API 28 is covered by
 * [BitmapDecodingBitmapFactoryTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BitmapDecodingAlphaTest {

    private fun stickerPng(): File {
        val file = createTempFile("fx-alpha", ".png").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(2f, 0f, 4f, 2f, Paint().apply { color = Color.RED })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun decode(): DecodedBitmap {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val decoded = runBlocking {
            decodeUpright(context, Uri.fromFile(stickerPng()), DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L))
        }
        return checkNotNull(decoded) { "the sticker did not decode" }
    }

    private fun assertAlphaKept(decoded: DecodedBitmap) {
        val bitmap = decoded.bitmap
        assertEquals(4, bitmap.width)
        assertEquals(2, bitmap.height)
        assertTrue("the bitmap carries an alpha channel", bitmap.hasAlpha())
        assertEquals("transparent half", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("opaque half", 255, Color.alpha(bitmap.getPixel(3, 1)))
        assertEquals("opaque half keeps its colour", Color.RED, bitmap.getPixel(3, 1))
    }

    @Test
    fun `the ImageDecoder path keeps a transparent png transparent`() {
        assertAlphaKept(decode())
    }
}
