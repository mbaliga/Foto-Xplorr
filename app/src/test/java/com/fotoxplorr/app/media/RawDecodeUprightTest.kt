package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [decodeUpright] over CONSTRUCTED TIFF-shaped files (no real RAW is committed): a portrait RAW's
 * preview comes back upright and flagged as a preview, an extension-less RAW is still found, and a
 * plain TIFF is never answered with its thumbnail. On API 34 too, where ImageDecoder is first for
 * ordinary files but must not be first for a RAW.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RawDecodeUprightTest {

    private val limits = DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)
    private val dir = createTempDirectory("fx-raw").toFile().also { it.deleteOnExit() }

    private fun decode(file: File): DecodedBitmap? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking { decodeUpright(context, Uri.fromFile(file), limits) }
    }

    /** A 40x20 JPEG, left half red, right half blue. */
    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.BLUE); clipRect(0, 0, 20, 20); drawColor(Color.RED) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    private class Le(size: Int) {
        val buf: ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        /** An IFD at [at]: entries of (tag, type, count, value) and the next-IFD offset. */
        fun ifd(at: Int, next: Int, vararg e: IntArray) {
            buf.putShort(at, e.size.toShort())
            e.forEachIndexed { i, (tag, type, count, value) ->
                val p = at + 2 + i * 12
                buf.putShort(p, tag.toShort()); buf.putShort(p + 2, type.toShort()); buf.putInt(p + 4, count)
                if (type == 3 && count == 1) buf.putShort(p + 8, value.toShort()) else buf.putInt(p + 8, value)
            }
            buf.putInt(at + 2 + e.size * 12, next)
        }
    }

    /** A NEF-shaped file: IFD0 Orientation 6 + SubIFD pointing at a JPEG preview. */
    private fun rawFile(name: String): File {
        val jpeg = jpeg()
        val f = Le(512 + jpeg.size)
        f.buf.put(0, 'I'.code.toByte()).put(1, 'I'.code.toByte()).putShort(2, 42).putInt(4, 8)
        f.ifd(8, 0, intArrayOf(0x0112, 3, 1, 6), intArrayOf(0x014A, 4, 1, 100))
        f.ifd(100, 0, intArrayOf(0x0201, 4, 1, 512), intArrayOf(0x0202, 4, 1, jpeg.size))
        jpeg.copyInto(f.buf.array(), 512)
        return File(dir, name).also { it.writeBytes(f.buf.array()) }
    }

    /** A plain TIFF whose IFD0 image cannot be decoded (JPEG compression) but whose IFD1 holds a thumbnail. */
    private fun tiffWithThumbnail(): File {
        val jpeg = jpeg()
        val f = Le(512 + jpeg.size)
        f.buf.put(0, 'I'.code.toByte()).put(1, 'I'.code.toByte()).putShort(2, 42).putInt(4, 8)
        f.ifd(
            8, 200,
            intArrayOf(256, 4, 1, 4000), intArrayOf(257, 4, 1, 3000), intArrayOf(259, 3, 1, 7),
            intArrayOf(262, 3, 1, 2), intArrayOf(273, 4, 1, 400), intArrayOf(279, 4, 1, 10),
        )
        f.ifd(200, 0, intArrayOf(0x0201, 4, 1, 512), intArrayOf(0x0202, 4, 1, jpeg.size))
        jpeg.copyInto(f.buf.array(), 512)
        return File(dir, "scan.tif").also { it.writeBytes(f.buf.array()) }
    }

    private fun assertUprightPreview(d: DecodedBitmap?) {
        assertNotNull(d)
        // Orientation 6 turns the 40x20 sensor-up preview into a 20x40 portrait.
        assertEquals(20, d!!.bitmap.width)
        assertEquals(40, d.bitmap.height)
        assertTrue(d.fromPreview)
        assertTrue("a preview always reports reduced size", d.downscaled)
        // Red was on the left of the sensor-up picture; rotated 90 clockwise it is on top.
        val top = d.bitmap.getPixel(10, 3)
        assertTrue("top is red: ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.blue(top) < 60)
    }

    @Test
    @Config(sdk = [26])
    fun `a portrait raw comes back upright and flagged as a preview on api 26`() = assertUprightPreview(decode(rawFile("portrait.nef")))

    @Test
    @Config(sdk = [34])
    fun `a portrait raw comes back upright and flagged as a preview on api 34`() = assertUprightPreview(decode(rawFile("portrait.nef")))

    @Test
    @Config(sdk = [34])
    fun `an extension-less raw is sniffed and still shows its preview`() = assertUprightPreview(decode(rawFile("IMG_0042")))

    @Test
    @Config(sdk = [34])
    fun `a plain tiff is not answered with its thumbnail`() = assertNull(decode(tiffWithThumbnail()))
}
