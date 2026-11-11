package com.fotoxplorr.app.wall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.editor.carriesLocation
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempFile
import kotlin.math.abs

/**
 * The enlarging export end to end on real small JPEGs (the library is a fake publisher, as in
 * [PrintExporterTest]) and the Android end of the resampler. What this proves: a photo short of
 * pixels is saved at the planned size and 300 dpi with the density in both headers, the middle of
 * the photo survives, a photo that has enough is not touched, a lock, a cancellation between
 * bands and an out-of-memory each end cleanly. What it cannot: that an enlargement LOOKS right, or
 * what a 12 or 48 megapixel photo costs in time and memory on a phone (device checks).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EnlargeExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakePublisher(var onNames: () -> Unit = {}) : PrintPublisher {
        var published: ByteArray? = null
        var count = 0

        override fun existingNames(prefix: String): Set<String> {
            onNames()
            return emptySet()
        }

        override fun publish(file: File, displayName: String, dateTakenMillis: Long): Uri {
            count++
            published = file.readBytes()
            return Uri.parse("content://fake/$displayName")
        }
    }

    private lateinit var publisher: FakePublisher
    private lateinit var exporter: PrintExporter
    private val stagingDir get() = File(context.cacheDir, PrintExporter.STAGING_DIRECTORY)

    @Before
    fun setUp() {
        warmUpNativeImageDecoder()
        publisher = FakePublisher()
        exporter = PrintExporter(context, publisher)
    }

    /** Four vertical bands: red, green, blue, yellow. */
    private fun bands(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, c ->
            canvas.drawRect((w * i / 4).toFloat(), 0f, (w * (i + 1) / 4).toFloat(), h.toFloat(), Paint().apply { color = c })
        }
        return bitmap
    }

    private fun asset(w: Int, h: Int): MediaAsset {
        val file = createTempFile("fx-enlarge", ".jpg").toFile().also { it.deleteOnExit() }
        FileOutputStream(file).use { bands(w, h).compress(Bitmap.CompressFormat.JPEG, 92, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_X_RESOLUTION, "72/1")
            setAttribute(ExifInterface.TAG_Y_RESOLUTION, "72/1")
            setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2")
            setLatLong(52.5, 13.4)
            saveAttributes()
        }
        return MediaAsset(
            id = MediaId(7), contentUriString = Uri.fromFile(file).toString(), displayName = "small.jpg",
            mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 1_714_557_600_000, dateModifiedSeconds = 0,
            width = w, height = h, sizeBytes = file.length(), relativePath = null, isFavorite = false, isTrashed = false,
        )
    }

    private fun source(
        asset: MediaAsset,
        mayExport: () -> Boolean = { true },
        decode: (suspend (DecodeLimits) -> DecodedBitmap?)? = null,
    ) = PrintSource(asset, mayExport, decode ?: { limits -> decodeUpright(context, asset.contentUri, limits) })

    private fun near(expected: Int, actual: Int, tolerance: Int = 40) =
        abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun bitmapOf(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    // --- the export ---

    @Test
    fun `a photo short of pixels is saved enlarged, at the planned size and 300 dpi in both headers`() {
        val media = asset(600, 400)
        val size = PrintSize(10.0, 15.0)
        val saved = runBlocking { exporter.save(source(media), size, enlarge = true) }.getOrThrow()

        // The middle 267 x 400 of a 3:2 photo for a 2:3 frame, made up to 10 x 15 cm at 300 dpi.
        assertEquals(1181, saved.widthPx)
        assertEquals(1772, saved.heightPx)
        assertEquals(300, saved.dpi)
        assertTrue(saved.enlarged)

        val bytes = publisher.published!!
        val picture = bitmapOf(bytes)
        assertEquals(1181, picture.width)
        assertEquals(1772, picture.height)
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertEquals(300.0, exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0), 0.001)
        assertEquals(2, exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0))
        assertEquals(1181, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(300 to 300, JfifDensity.readDpi(bytes))
        // Same as any print file: the other Exif kept, the place gone.
        assertEquals("TestCam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.latLong)
        val file = createTempFile("fx-enlarge-out", ".jpg").toFile().also { it.deleteOnExit() }
        file.writeBytes(bytes)
        assertFalse(carriesLocation(file))
    }

    @Test
    fun `the middle of the photo is what is enlarged`() {
        val media = asset(600, 400)
        runBlocking { exporter.save(source(media), PrintSize(10.0, 15.0), enlarge = true) }.getOrThrow()
        val picture = bitmapOf(publisher.published!!)
        // Bands are 150 px wide; the kept 167..433 is the end of green and the start of blue.
        assertTrue(near(Color.GREEN, picture.getPixel(150, 886)))
        assertTrue(near(Color.BLUE, picture.getPixel(1030, 886)))
    }

    @Test
    fun `a photo that already has the pixels is not enlarged, whatever was asked`() {
        val media = asset(2400, 1800)
        val size = PrintSize(10.0, 15.0)
        val asked = runBlocking { exporter.save(source(media), size, enlarge = true) }.getOrThrow()
        assertFalse(asked.enlarged)
        assertEquals(1181, asked.widthPx)
        assertEquals(1772, asked.heightPx)
        assertEquals(300, asked.dpi)
    }

    @Test
    fun `without the request a short photo is still saved at its own pixels with the honest density`() {
        val media = asset(600, 400)
        val saved = runBlocking { exporter.save(source(media), PrintSize(10.0, 15.0)) }.getOrThrow()
        assertFalse(saved.enlarged)
        assertEquals(267, saved.widthPx)
        assertEquals(400, saved.heightPx)
        assertTrue(saved.dpi < 200)
    }

    @Test
    fun `progress only goes forward and ends at one while enlarging`() {
        val media = asset(600, 400)
        val progress = mutableListOf<Float?>()
        runBlocking { exporter.save(source(media), PrintSize(10.0, 15.0), enlarge = true) { progress += it } }.getOrThrow()
        val values = progress.filterNotNull()
        assertEquals(1f, values.last(), 0f)
        assertEquals(values.sorted(), values)
        // The bands report inside the resample's share of the bar, between decode and encode.
        assertTrue(values.count { it > 0.25f && it < 0.5f } >= 2)
    }

    @Test
    fun `a photo locked after the work began is refused at the write, nothing published or left behind`() {
        val media = asset(600, 400)
        var locked = false
        publisher.onNames = { locked = true }
        val result = runBlocking { exporter.save(source(media, mayExport = { !locked }), PrintSize(10.0, 15.0), enlarge = true) }
        assertTrue(result.exceptionOrNull() is PrintRefusedException)
        assertEquals(0, publisher.count)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a photo that may not be exported is refused before anything is read, enlarging or not`() {
        val media = asset(600, 400)
        var decodes = 0
        val result = runBlocking { exporter.save(source(media, mayExport = { false }, decode = { decodes++; null }), PrintSize(10.0, 15.0), enlarge = true) }
        assertTrue(result.exceptionOrNull() is PrintRefusedException)
        assertEquals(0, decodes)
    }

    @Test
    fun `cancelling between bands rethrows and leaves nothing published or in the cache`() {
        val media = asset(600, 400)
        val progress = mutableListOf<Float?>()
        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.Default) {
                val self = checkNotNull(coroutineContext[Job])
                try {
                    // The first band reported: stop. The next band's check must refuse.
                    exporter.save(source(media), PrintSize(10.0, 15.0), enlarge = true) {
                        progress += it
                        if (it != null && it > 0.26f) self.cancel()
                    }
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            job.join()
        }
        assertTrue(thrown is CancellationException)
        assertEquals(0, publisher.count)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
        // Stopped inside the enlargement, well before its end at one half of the bar.
        assertTrue(progress.filterNotNull().none { it >= 0.5f })
    }

    @Test
    fun `out of memory steps an enlargement down to fewer made up pixels, and the density follows`() {
        val media = asset(600, 400)
        var fullDecodes = 0
        // The first full decode runs out of memory (the probe, a thumbnail, does not): the next rung is 1536 px.
        val decode: suspend (DecodeLimits) -> DecodedBitmap? = { limits ->
            if (limits.maxLongEdge > 64 && ++fullDecodes == 1) throw OutOfMemoryError("test")
            decodeUpright(context, media.contentUri, limits)
        }
        val saved = runBlocking { exporter.save(source(media, decode = decode), PrintSize(10.0, 15.0), enlarge = true) }.getOrThrow()

        assertEquals(2, fullDecodes)
        assertEquals(1536, saved.heightPx)
        assertTrue(saved.enlarged)
        assertTrue(saved.dpi in 200..299)
        assertEquals(saved.dpi to saved.dpi, JfifDensity.readDpi(publisher.published!!))
        assertEquals(saved.dpi, ExifInterface(ByteArrayInputStream(publisher.published!!)).getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0).toInt())
    }

    @Test
    fun `the photo's own file is not written to`() {
        val media = asset(600, 400)
        val file = File(requireNotNull(media.contentUri.path))
        val before = file.readBytes()
        runBlocking { exporter.save(source(media), PrintSize(10.0, 15.0), enlarge = true) }.getOrThrow()
        assertTrue(before.contentEquals(file.readBytes()))
    }

    // --- the Android end of the resampler ---

    @Test
    fun `a bitmap enlarges to an opaque sRGB bitmap of the size asked, flat colour kept`() {
        val flat = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 60, 90)) }
        val out = enlargeBitmap(flat, aspect = 1.0, outW = 25, outH = 25)
        assertEquals(25, out.width)
        assertEquals(25, out.height)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), out.colorSpace)
        for (y in 0 until 25 step 6) for (x in 0 until 25 step 6) assertEquals(Color.rgb(30, 60, 90), out.getPixel(x, y))
        assertFalse(flat.isRecycled)
    }

    @Test
    fun `the part enlarged is the frame's part, the middle`() {
        // 4:1 strip, left half red and right half blue; a square frame keeps the middle 1:1 part, half of each.
        val strip = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888)
        for (y in 0 until 10) for (x in 0 until 40) strip.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        val out = enlargeBitmap(strip, aspect = 1.0, outW = 30, outH = 30)
        assertTrue(near(Color.RED, out.getPixel(2, 15), 30))
        assertTrue(near(Color.BLUE, out.getPixel(27, 15), 30))
    }

    @Test
    fun `reading rows takes the crop, and flattens transparency onto white`() {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        for (y in 0 until 3) for (x in 0 until 4) bitmap.setPixel(x, y, Color.rgb(10 * x, 20 * y, 7))
        bitmap.setPixel(2, 1, Color.TRANSPARENT)
        bitmap.setPixel(3, 1, Color.argb(128, 0, 0, 0))

        val rows = BitmapRows(bitmap, PixelRect(1, 1, 3, 2))
        assertEquals(3, rows.width)
        assertEquals(2, rows.height)
        val row = IntArray(3)
        rows.readRow(0, row)
        assertEquals(Color.rgb(10, 20, 7), row[0])
        assertEquals(Color.WHITE, row[1]) // fully transparent: white, not black
        assertTrue("half transparent black is mid grey, was ${Integer.toHexString(row[2])}", abs(Color.red(row[2]) - 127) <= 2 && Color.alpha(row[2]) == 255)
        rows.readRow(1, row)
        assertEquals(Color.rgb(10, 40, 7), row[0])
    }

    @Test
    fun `a cancelled enlargement throws the cancellation and leaves its source alone`() {
        val src = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        var thrown = false
        try {
            enlargeBitmap(src, 1.0, 25, 25, checkCancelled = { throw CancellationException("stop") })
        } catch (_: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
        assertSame(false, src.isRecycled)
    }
}
