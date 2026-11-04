package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import coil3.toBitmap
import com.fotoxplorr.core.formats.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
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
 * [RouteCoilDecoder] over constructed files, the way Coil hands it a request: a Crop tile (Scale.FILL) gets a
 * bitmap whose short edge covers the tile, a Fit one fits inside it, a declined file leaves nothing buffered in
 * Coil's source for the decoders after it, a tile does not decode a picture past the tile cap, and a TIFF that is
 * really a RAW gets its preview before the whole-file TIFF decode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RouteCoilDecoderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = createTempDirectory("fx-route").toFile().also { it.deleteOnExit() }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    /** Runs the decoder as Coil would, over a buffered source of [f]; returns the result and that source. */
    private fun decode(f: File, format: MediaFormat, size: Size, scale: Scale): Pair<Bitmap?, okio.BufferedSource> {
        val source = FileSystem.SYSTEM.source(f.toPath().toOkioPath()).buffer()
        val fetched = SourceFetchResult(ImageSource(source, FileSystem.SYSTEM), null, DataSource.DISK)
        val options = Options(context, size = size, scale = scale)
        val result = runBlocking { RouteCoilDecoder(fetched, options, format, Uri.fromFile(f)).decode() }
        return result?.image?.toBitmap() to source
    }

    /** An uncompressed 24-bit top-down TGA of [w] x [h], left half red and right half blue. */
    private fun tga(w: Int, h: Int): ByteArray {
        val out = ByteArray(18 + w * h * 3)
        out[2] = 2
        out[12] = (w and 0xFF).toByte(); out[13] = (w shr 8).toByte()
        out[14] = (h and 0xFF).toByte(); out[15] = (h shr 8).toByte()
        out[16] = 24; out[17] = 0x20
        for (y in 0 until h) for (x in 0 until w) {
            val p = 18 + (y * w + x) * 3
            if (x < w / 2) out[p + 2] = 0xFF.toByte() else out[p] = 0xFF.toByte()
        }
        return out
    }

    /** A run-length TGA of [w] x [h] in one grey: large in pixels, small on disk. */
    private fun rleTga(w: Int, h: Int): ByteArray {
        val packets = (w.toLong() * h + 127) / 128
        val out = ByteArrayOutputStream()
        val header = ByteArray(18)
        header[2] = 10
        header[12] = (w and 0xFF).toByte(); header[13] = (w shr 8).toByte()
        header[14] = (h and 0xFF).toByte(); header[15] = (h shr 8).toByte()
        header[16] = 24; header[17] = 0x20
        out.write(header)
        var left = w.toLong() * h
        repeat(packets.toInt()) {
            val n = minOf(128L, left).toInt()
            out.write(0x80 or (n - 1)); out.write(0x80); out.write(0x80); out.write(0x80)
            left -= n
        }
        return out.toByteArray()
    }

    @Test
    fun `a Crop tile is decoded with its short edge covering the tile`() {
        val (bitmap, _) = decode(file("wide.tga", tga(600, 400)), MediaFormat.Tga, Size(300, 300), Scale.FILL)
        assertNotNull(bitmap)
        assertEquals(450, bitmap!!.width)
        assertEquals(300, bitmap.height)
    }

    @Test
    fun `a Fit tile is decoded inside the tile`() {
        val (bitmap, _) = decode(file("wide.tga", tga(600, 400)), MediaFormat.Tga, Size(300, 300), Scale.FIT)
        assertEquals(300, bitmap!!.width)
        assertEquals(200, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(10, 100))
        assertEquals(Color.BLUE, bitmap.getPixel(290, 100))
    }

    @Test
    fun `a picture smaller than the tile is never scaled up`() {
        val (bitmap, _) = decode(file("small.tga", tga(60, 40)), MediaFormat.Tga, Size(300, 300), Scale.FILL)
        assertEquals(60, bitmap!!.width)
        assertEquals(40, bitmap.height)
    }

    @Test
    fun `a declined file leaves nothing buffered for the decoders that come after`() {
        val (bitmap, source) = decode(file("broken.tga", ByteArray(3 shl 20)), MediaFormat.Tga, Size(300, 300), Scale.FILL)
        assertNull(bitmap)
        assertTrue("buffered ${source.buffer.size} bytes", source.buffer.size <= 64 * 1024)
    }

    @Test
    fun `a tile does not decode a picture over the tile cap, the viewer still does`() {
        val big = file("big.tga", rleTga(4000, 3200))
        assertNull(decode(big, MediaFormat.Tga, Size(300, 300), Scale.FILL).first)
        val viewer = decode(big, MediaFormat.Tga, Size(2000, 2000), Scale.FIT).first
        assertEquals(2000, viewer!!.width)
        assertEquals(1600, viewer.height)
    }

    // ---- a TIFF that is really a RAW ------------------------------------------------------------------

    /** A 40x20 JPEG, left half red, right half blue. */
    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.BLUE); clipRect(0, 0, 20, 20); drawColor(Color.RED) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    private fun ifd(buf: ByteBuffer, at: Int, next: Int, vararg e: IntArray) {
        buf.putShort(at, e.size.toShort())
        e.forEachIndexed { i, (tag, type, count, value) ->
            val p = at + 2 + i * 12
            buf.putShort(p, tag.toShort()); buf.putShort(p + 2, type.toShort()); buf.putInt(p + 4, count)
            if (type == 3 && count == 1) buf.putShort(p + 8, value.toShort()) else buf.putInt(p + 8, value)
        }
        buf.putInt(at + 2 + e.size * 12, next)
    }

    /**
     * A DNG-shaped TIFF: IFD0 is a reduced-resolution (NewSubfileType 1) 6x6 grey picture the baseline TIFF
     * decoder would refuse (a RAW's thumbnail), a SubIFD points at the 40x20 JPEG preview, and 4 MB of sensor
     * data follow, as in a real RAW.
     */
    private fun rawShapedTiff(): ByteArray {
        val jpeg = jpeg()
        val buf = ByteBuffer.allocate(1024 + jpeg.size + (4 shl 20)).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 'I'.code.toByte()).put(1, 'I'.code.toByte()).putShort(2, 42).putInt(4, 8)
        ifd(
            buf, 8, 0,
            intArrayOf(254, 4, 1, 1),
            intArrayOf(256, 3, 1, 6), intArrayOf(257, 3, 1, 6), intArrayOf(258, 3, 1, 8),
            intArrayOf(259, 3, 1, 1), intArrayOf(262, 3, 1, 1), intArrayOf(273, 4, 1, 600),
            intArrayOf(277, 3, 1, 1), intArrayOf(278, 3, 1, 6), intArrayOf(279, 4, 1, 36),
            intArrayOf(330, 4, 1, 300),
        )
        ifd(buf, 300, 0, intArrayOf(254, 4, 1, 1), intArrayOf(0x0201, 4, 1, 1024), intArrayOf(0x0202, 4, 1, jpeg.size))
        for (i in 0 until 36) buf.put(600 + i, 0x80.toByte())
        jpeg.copyInto(buf.array(), 1024)
        return buf.array()
    }

    @Test
    fun `a TIFF that is really a RAW goes to its preview without the whole file being read first`() {
        val (bitmap, source) = decode(file("IMG_0001", rawShapedTiff()), MediaFormat.Tiff, Size(300, 300), Scale.FIT)
        assertNotNull(bitmap)
        assertEquals(40, bitmap!!.width)
        assertEquals(20, bitmap.height)
        assertEquals(Color.RED, nearest(bitmap.getPixel(5, 10)))
        assertEquals(Color.BLUE, nearest(bitmap.getPixel(35, 10)))
        assertTrue("buffered ${source.buffer.size} bytes", source.buffer.size <= 64 * 1024)
    }

    private fun nearest(c: Int): Int = if (Color.red(c) > Color.blue(c)) Color.RED else Color.BLUE
}
