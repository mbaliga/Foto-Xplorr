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
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.metadata.ExifCopier
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile
import kotlin.math.abs

/**
 * The whole export, from decode to the file handed to the library, on real small JPEGs with real
 * Exif. The library itself is a fake [PrintPublisher] that keeps what it was given; the MediaStore
 * insert is the one step that needs a device. What this proves: the file has the planned size, the
 * crop is the middle, the density is in the Exif AND the JFIF header (over a source that said 72),
 * the location is gone, the photo is untouched, a lock mid-export stops the write, and a refusal,
 * a cancellation and an out-of-memory each end cleanly with nothing left in the cache.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PrintExporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakePublisher(var taken: Set<String> = emptySet(), var onNames: () -> Unit = {}) : PrintPublisher {
        var published: ByteArray? = null
        var name: String? = null
        var dateTaken: Long? = null
        var count = 0

        override fun existingNames(prefix: String): Set<String> {
            onNames()
            return taken
        }

        override fun publish(file: File, displayName: String, dateTakenMillis: Long): Uri {
            count++
            published = file.readBytes()
            name = displayName
            dateTaken = dateTakenMillis
            return Uri.parse("content://fake/$displayName")
        }
    }

    private lateinit var publisher: FakePublisher
    private lateinit var exporter: PrintExporter
    private val stagingDir get() = File(context.cacheDir, PrintExporter.STAGING_DIRECTORY)

    @Before
    fun setUp() {
        publisher = FakePublisher()
        exporter = PrintExporter(context, publisher)
    }

    /** Four vertical bands, red, green, blue, yellow, each a quarter of the width. */
    private fun bands(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val colours = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        colours.forEachIndexed { i, c -> canvas.drawRect((w * i / 4).toFloat(), 0f, (w * (i + 1) / 4).toFloat(), h.toFloat(), Paint().apply { color = c }) }
        return bitmap
    }

    private fun asset(w: Int, h: Int, name: String = "wall-shot.jpg", withExif: Boolean = true): Pair<MediaAsset, File> {
        val file = createTempFile("fx-print", ".jpg").toFile().also { it.deleteOnExit() }
        FileOutputStream(file).use { bands(w, h).compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (withExif) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MAKE, "TestCam")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:01 10:00:00")
                setAttribute(ExifInterface.TAG_X_RESOLUTION, "72/1")
                setAttribute(ExifInterface.TAG_Y_RESOLUTION, "72/1")
                setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2")
                setLatLong(52.5, 13.4)
                saveAttributes()
            }
        }
        val media = MediaAsset(
            id = MediaId(7), contentUriString = Uri.fromFile(file).toString(), displayName = name,
            mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 1_714_557_600_000, dateModifiedSeconds = 0,
            width = w, height = h, sizeBytes = file.length(), relativePath = null, isFavorite = false, isTrashed = false,
        )
        return media to file
    }

    private fun source(
        asset: MediaAsset,
        mayExport: () -> Boolean = { true },
        decode: (suspend (DecodeLimits) -> com.fotoxplorr.app.media.DecodedBitmap?)? = null,
    ) = PrintSource(asset, mayExport, decode ?: { limits -> decodeUpright(context, asset.contentUri, limits) })

    private fun save(source: PrintSource, size: PrintSize, progress: MutableList<Float?> = mutableListOf()) =
        runBlocking { exporter.save(source, size) { progress += it } }

    private fun bitmapOf(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    private fun near(expected: Int, actual: Int, tolerance: Int = 40) =
        abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    // --- the file ---

    @Test
    fun `the file has the planned size, and keeps the middle of the photo`() {
        val (media, _) = asset(1200, 900)
        val size = PrintSize(20.0, 30.0)
        val saved = save(source(media), size).getOrThrow()

        // 4:3 photo to a 2:3 frame: the middle 600 x 900 of it, and too few pixels for 300 dpi.
        assertEquals(600, saved.widthPx)
        assertEquals(900, saved.heightPx)
        val picture = bitmapOf(publisher.published!!)
        assertEquals(600, picture.width)
        assertEquals(900, picture.height)
        // The bands were 300 px wide: the kept 300..900 is the green band, then the blue one.
        assertTrue(near(Color.GREEN, picture.getPixel(100, 450)))
        assertTrue(near(Color.BLUE, picture.getPixel(500, 450)))
    }

    @Test
    fun `the metadata copier carries a source's own resolution, which is why the print's is written after it`() {
        // The premise of the ordering in PrintExporter.writeMetadata: if this stops being true the order stops mattering.
        assertTrue(ExifInterface.TAG_X_RESOLUTION in ExifCopier.COPIED_TAGS)
        assertTrue(ExifInterface.TAG_Y_RESOLUTION in ExifCopier.COPIED_TAGS)
        assertTrue(ExifInterface.TAG_RESOLUTION_UNIT in ExifCopier.COPIED_TAGS)
    }

    @Test
    fun `the density is in the Exif and in the JFIF header, and the source's 72 does not win`() {
        val (media, _) = asset(1200, 900)
        val saved = save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        val expected = PrintPlanner.plan(1200, 900, PrintSize(20.0, 30.0))!!.dpi
        assertEquals(expected, saved.dpi)
        assertNotEquals(72, expected)

        val bytes = publisher.published!!
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertEquals(expected.toDouble(), exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0), 0.001)
        assertEquals(expected.toDouble(), exif.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0.0), 0.001)
        assertEquals(2, exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0))
        assertEquals(expected to expected, JfifDensity.readDpi(bytes))
        assertEquals(600, exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
        assertEquals(900, exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
    }

    @Test
    fun `the photo's other Exif is kept, the orientation is upright, and the location is gone`() {
        val (media, _) = asset(1200, 900)
        save(source(media), PrintSize(20.0, 30.0)).getOrThrow()

        val bytes = publisher.published!!
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertEquals("TestCam", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("2024:05:01 10:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertNull(exif.latLong)
        val file = createTempFile("fx-print-out", ".jpg").toFile().also { it.deleteOnExit() }
        file.writeBytes(bytes)
        assertFalse(carriesLocation(file))
    }

    @Test
    fun `a photo with no readable Exif still gives a good file with the density`() {
        val (media, _) = asset(1200, 900, withExif = false)
        val saved = save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        assertEquals(saved.dpi to saved.dpi, JfifDensity.readDpi(publisher.published!!))
    }

    @Test
    fun `a photo with enough pixels is saved at 300 dpi`() {
        val (media, _) = asset(2400, 1800)
        // 10 x 15 cm at 300 dpi is 1181 x 1772; the middle of a 2400 x 1800 photo has 1200 x 1800.
        val saved = save(source(media), PrintSize(10.0, 15.0)).getOrThrow()
        assertEquals(1181, saved.widthPx)
        assertEquals(1772, saved.heightPx)
        assertEquals(300, saved.dpi)
        assertEquals(300 to 300, JfifDensity.readDpi(publisher.published!!))
    }

    @Test
    fun `the new file carries the photo's date, and a name that is not the photo's`() {
        val (media, _) = asset(1200, 900)
        save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        assertEquals("wall-shot-print-20x30cm.jpg", publisher.name)
        assertEquals(media.dateTakenMillis, publisher.dateTaken)
    }

    @Test
    fun `a name already in the library gets a number`() {
        val (media, _) = asset(1200, 900)
        publisher.taken = setOf("wall-shot-print-20x30cm.jpg")
        val saved = save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        assertEquals("wall-shot-print-20x30cm (2).jpg", saved.displayName)
    }

    @Test
    fun `the photo's own file is never written to`() {
        val (media, file) = asset(1200, 900)
        val before = file.readBytes()
        val modified = file.lastModified()
        save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        assertArrayEquals(before, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertNotEquals(file.name, publisher.name)
    }

    @Test
    fun `progress only goes forward and ends at 1`() {
        val (media, _) = asset(1200, 900)
        val progress = mutableListOf<Float?>()
        save(source(media), PrintSize(20.0, 30.0), progress).getOrThrow()
        val values = progress.filterNotNull()
        assertEquals(1f, values.last(), 0f)
        assertEquals(values.sorted(), values)
    }

    @Test
    fun `nothing is left in the cache after a save`() {
        val (media, _) = asset(1200, 900)
        save(source(media), PrintSize(20.0, 30.0)).getOrThrow()
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    // --- refusals and failures ---

    @Test
    fun `a photo that may not be exported is refused before anything is read or written`() {
        val (media, _) = asset(1200, 900)
        var decodes = 0
        val result = save(source(media, mayExport = { false }, decode = { decodes++; null }), PrintSize(20.0, 30.0))

        assertTrue(result.exceptionOrNull() is PrintRefusedException)
        assertEquals(WallCopy.CANT_EXPORT, result.exceptionOrNull()!!.message)
        assertEquals(0, decodes)
        assertEquals(0, publisher.count)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a photo locked after the work began is refused at the write, and the file is not published`() {
        val (media, _) = asset(1200, 900)
        var locked = false
        // The library is asked for its names just before the write: the lock lands there.
        publisher.onNames = { locked = true }
        val result = save(source(media, mayExport = { !locked }), PrintSize(20.0, 30.0))

        assertTrue(result.exceptionOrNull() is PrintRefusedException)
        assertEquals(0, publisher.count)
        assertNull(publisher.published)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `a photo that cannot be decoded is a sentence, not a crash`() {
        val (media, _) = asset(1200, 900)
        val result = save(source(media, decode = { null }), PrintSize(20.0, 30.0))
        assertTrue(result.exceptionOrNull() is PrintFailedException)
        assertEquals(WallCopy.CANT_READ_PHOTO, result.exceptionOrNull()!!.message)
        assertEquals(0, publisher.count)
    }

    @Test
    fun `a size that means nothing is a failure, not a division by zero`() {
        val (media, _) = asset(1200, 900)
        assertTrue(save(source(media), PrintSize(0.0, 30.0)).isFailure)
        assertEquals(0, publisher.count)
    }

    @Test
    fun `out of memory steps the file down and the density follows the real pixels`() {
        val (media, _) = asset(3000, 2000)
        var refused = 0
        // Any decode over 2400 px runs out of memory: the first rung (3000) does, the second (2304) does not.
        val decode: suspend (DecodeLimits) -> com.fotoxplorr.app.media.DecodedBitmap? = { limits ->
            if (limits.maxLongEdge > 2400) {
                refused++
                throw OutOfMemoryError("test")
            }
            decodeUpright(context, media.contentUri, limits)
        }
        val saved = save(source(media, decode = decode), PrintSize(20.0, 30.0)).getOrThrow()

        assertEquals(1, refused)
        assertEquals(1536, saved.heightPx)
        assertTrue(saved.widthPx in 1023..1025)
        val full = PrintPlanner.plan(3000, 2000, PrintSize(20.0, 30.0))!!
        assertTrue(saved.dpi < full.dpi)
        assertEquals(saved.dpi, ExifInterface(ByteArrayInputStream(publisher.published!!)).getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0).toInt())
        assertEquals(saved.dpi to saved.dpi, JfifDensity.readDpi(publisher.published!!))
    }

    @Test
    fun `when every rung runs out of memory the person gets a sentence`() {
        val (media, _) = asset(3000, 2000)
        val result = save(source(media, decode = { limits -> if (limits.maxLongEdge > 100) throw OutOfMemoryError("test") else decodeUpright(context, media.contentUri, limits) }), PrintSize(20.0, 30.0))
        assertEquals(WallCopy.NOT_ENOUGH_MEMORY, result.exceptionOrNull()!!.message)
        assertEquals(0, publisher.count)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `cancelling during the work rethrows the cancellation and leaves nothing behind`() {
        val (media, _) = asset(1200, 900)
        val started = CompletableDeferred<Unit>()
        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try {
                    exporter.save(source(media, decode = { started.complete(Unit); awaitCancellation() }), PrintSize(20.0, 30.0))
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            withTimeout(10_000) { started.await() }
            job.cancelAndJoin()
        }
        assertTrue(thrown is CancellationException)
        assertEquals(0, publisher.count)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    // --- the cache ---

    @Test
    fun `old staging files are pruned and new ones kept`() {
        stagingDir.mkdirs()
        val old = File(stagingDir, "old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(System.currentTimeMillis() - 3 * 24 * 3600 * 1000L) }
        val fresh = File(stagingDir, "fresh.jpg").apply { writeBytes(byteArrayOf(1)) }
        val removed = runBlocking { exporter.pruneCache() }
        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        fresh.delete()
    }

    // --- the system print path ---

    @Test
    fun `render hands over a bitmap of the print's shape and recycles it afterwards`() {
        val (media, _) = asset(2400, 1800)
        var held: Bitmap? = null
        val plan = runBlocking {
            exporter.render(source(media), PrintSize(10.0, 15.0), dpi = 100) { bitmap, p ->
                held = bitmap
                assertEquals(p.outW, bitmap.width)
                assertEquals(p.outH, bitmap.height)
                p
            }
        }
        assertEquals(394, plan.outW) // 10 cm at 100 dpi
        assertEquals(591, plan.outH)
        assertTrue(held!!.isRecycled)
    }

    @Test
    fun `render is refused for a photo that may not be exported, and asks again before it hands over`() {
        val (media, _) = asset(2400, 1800)
        var calls = 0
        var handed = false
        // True at the start of the render, false by the time the bitmap is ready.
        val src = source(media, mayExport = { ++calls <= 1 })
        val result = runCatching { runBlocking { exporter.render(src, PrintSize(10.0, 15.0), 100) { _, _ -> handed = true } } }
        assertTrue(result.exceptionOrNull() is PrintRefusedException)
        assertFalse(handed)

        val none = runCatching { runBlocking { exporter.render(source(media, mayExport = { false }), PrintSize(10.0, 15.0), 100) { _, _ -> fail("must not run") } } }
        assertTrue(none.exceptionOrNull() is PrintRefusedException)
    }

    // --- the drawing ---

    @Test
    fun `a big reduction by halves averages the pixels, where one draw would skip them`() {
        // A one-pixel checkerboard: any honest reduction is mid-grey.
        val size = 640
        val checker = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) checker.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        val out = resample(checker, aspect = 1.0, outW = 20, outH = 20)

        assertEquals(20, out.width)
        for (y in 0 until 20 step 5) for (x in 0 until 20 step 5) {
            val grey = Color.red(out.getPixel(x, y))
            assertTrue("pixel $x,$y was $grey", grey in 112..144)
        }
    }

    @Test
    fun `a transparent source is flattened onto white, not black`() {
        val clear = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        clear.eraseColor(Color.TRANSPARENT)
        val out = resample(clear, 1.0, 4, 4)
        assertEquals(Color.WHITE, out.getPixel(1, 1))
        assertFalse(out.hasAlpha())
    }

    @Test
    fun `the result is sRGB and a Display-P3 source is converted, not just relabelled`() {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888, true, p3)
        source.eraseColor(Color.pack(0.6f, 0.4f, 0.4f, 1f, p3))
        val out = resample(source, 1.0, 4, 4)

        assertEquals(srgb, out.colorSpace)
        val expected = Color.valueOf(0.6f, 0.4f, 0.4f, 1f, p3).convert(srgb)
        val pixel = out.getPixel(1, 1)
        assertEquals(expected.red() * 255, Color.red(pixel).toFloat(), 3f)
        assertEquals(expected.green() * 255, Color.green(pixel).toFloat(), 3f)
        assertEquals(expected.blue() * 255, Color.blue(pixel).toFloat(), 3f)
        // And it is not the same numbers the P3 file held: that would be the label changed and the colours kept.
        assertTrue(abs(Color.red(pixel) - (0.6f * 255).toInt()) > 5)
    }
}
