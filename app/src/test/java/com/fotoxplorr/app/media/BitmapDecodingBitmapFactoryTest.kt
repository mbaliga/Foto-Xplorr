package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The [android.graphics.BitmapFactory] fallback of [decodeUpright]. SDK 26 sits below
 * `Build.VERSION_CODES.P`, so [android.graphics.ImageDecoder] is never tried and every decode here
 * goes through `decodeWithBitmapFactory` (the minSdk path, and the one ImageDecoder failures fall
 * back to on newer devices). Its bounds-only pass returns null from `decodeStream` by design; it
 * once treated that as failure, so the editor, lift and framed share failed for every image.
 * NATIVE graphics, like the ImageDecoder tests, because the legacy shadow decodes nothing real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26])
class BitmapDecodingBitmapFactoryTest {

    private val limits = DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)

    private fun decode(file: File, limits: DecodeLimits = this.limits): DecodedBitmap? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking { decodeUpright(context, Uri.fromFile(file), limits) }
    }

    private fun jpegWithOrientation(orientation: Int): File {
        val file = createTempFile("fx-bf", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun stickerPng(): File {
        val file = createTempFile("fx-bf-alpha", ".png").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(2f, 0f, 4f, 2f, Paint().apply { color = Color.RED })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    @Test
    fun `a plain jpeg decodes to a bitmap instead of null`() {
        val decoded = decode(jpegWithOrientation(ExifInterface.ORIENTATION_NORMAL))
        assertNotNull("the bounds pass must not end the decode", decoded)
        assertEquals(4, decoded!!.bitmap.width)
        assertEquals(6, decoded.bitmap.height)
        assertFalse(decoded.downscaled)
    }

    @Test
    fun `a rotate-90 jpeg comes back upright`() {
        val decoded = decode(jpegWithOrientation(ExifInterface.ORIENTATION_ROTATE_90))
        assertNotNull(decoded)
        // The encoded grid is 4 x 6; Orientation = 6 turns it to 6 x 4, and sourceWidth/Height
        // report the upright size too.
        assertEquals(6, decoded!!.bitmap.width)
        assertEquals(4, decoded.bitmap.height)
        assertEquals(6, decoded.sourceWidth)
        assertEquals(4, decoded.sourceHeight)
    }

    @Test
    fun `the limits still bound the fallback`() {
        val decoded = decode(jpegWithOrientation(ExifInterface.ORIENTATION_NORMAL), DecodeLimits(3, 16_000_000L))
        assertNotNull(decoded)
        assertEquals(2, decoded!!.bitmap.width)
        assertEquals(3, decoded.bitmap.height)
        assertTrue(decoded.downscaled)
    }

    @Test
    fun `the BitmapFactory path keeps a transparent png transparent`() {
        val decoded = decode(stickerPng())
        assertNotNull("the sticker did not decode", decoded)
        val bitmap = decoded!!.bitmap
        assertEquals(4, bitmap.width)
        assertEquals(2, bitmap.height)
        assertTrue("the bitmap carries an alpha channel", bitmap.hasAlpha())
        assertEquals("transparent half", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals("opaque half keeps its colour", Color.RED, bitmap.getPixel(3, 1))
    }

    @Test
    fun `a corrupt file is still null`() {
        val file = createTempFile("fx-bf-corrupt", ".jpg").toFile()
        file.deleteOnExit()
        file.writeBytes(ByteArray(64) { it.toByte() })
        assertNull(decode(file))
    }
}
