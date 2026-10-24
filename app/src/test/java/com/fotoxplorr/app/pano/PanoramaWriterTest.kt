package com.fotoxplorr.app.pano

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * [PanoramaWriter] and [BitmapPanoCanvas] with real bitmaps (Robolectric native graphics) and a
 * stand-in for MediaStore's provider that keeps its rows: what is inserted with which values, what
 * is updated and deleted, the bytes behind a row, and a query by name. What this cannot show: the
 * real provider's own refusals (a folder it rejects), its renaming of a clashing name, the expiry of
 * a pending row, and the scanner reading the finished file; those are on the device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PanoramaWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val taken = 1_700_000_000_000L

    @Before
    fun attach() {
        PanoramaStore.reset(context.cacheDir)
        Robolectric.buildContentProvider(PanoramaStore::class.java).create(MediaStore.AUTHORITY)
        File(context.cacheDir, PanoramaWriter.STAGING_DIRECTORY).deleteRecursively()
    }

    private fun writer() = PanoramaWriter(context)

    /** A canvas [w] x [h] whose pixel is `0xFF` and a colour that depends on the place, with [hole] rows at the top left transparent. */
    private fun canvas(w: Int = 120, h: Int = 80, hole: Int = 0): BitmapPanoCanvas {
        val c = BitmapPanoCanvas(w, h)
        val row = IntArray(w * h) { i -> if (i / w < hole && i % w < hole) 0 else (0xFF shl 24) or (((i % w) * 2) shl 16) or (((i / w) * 3) shl 8) or 0x40 }
        c.write(0, 0, w, h, row)
        return c
    }

    private fun publish(
        c: BitmapPanoCanvas,
        crop: AutoCrop.CropRect = AutoCrop.CropRect(0, 0, c.width, c.height),
        name: String = "IMG_0001.jpg",
        allowed: () -> Boolean = { true },
        progress: (Float) -> Unit = {},
    ): String = runBlocking { writer().publish(c, crop, taken, name, allowed, progress) }

    private fun stagedFiles(): List<String> = File(context.cacheDir, PanoramaWriter.STAGING_DIRECTORY).list()?.toList().orEmpty()

    // ---- the row and the file -----------------------------------------------------------------

    @Test
    fun `a panorama becomes one visible JPEG row in the panoramas album, dated by the first photo`() {
        val uri = publish(canvas())
        val id = ContentUris.parseId(Uri.parse(uri))
        val row = PanoramaStore.rows.getValue(id)
        assertEquals("IMG_0001-panorama.jpg", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Fotoz/Panoramas/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("the first frame's date", taken, row.getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
        assertEquals("pending is cleared at the end", 0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("and was set when the row was made", 1, PanoramaStore.insertedPending.single())
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, PanoramaStore.insertedInto.single())
        assertTrue("the staging file is gone: ${stagedFiles()}", stagedFiles().isEmpty())
    }

    @Test
    fun `the saved file is a JPEG of exactly the crop, with the canvas pixels in it`() {
        val c = canvas(120, 80, hole = 10)
        val crop = AutoCrop.CropRect(10, 10, 110, 70)
        val uri = publish(c, crop)
        val bytes = PanoramaStore.fileOf(ContentUris.parseId(Uri.parse(uri))).readBytes()
        assertEquals("a JPEG starts FF D8", 0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(100, decoded.width)
        assertEquals(60, decoded.height)
        // The decoded pixel (0, 0) is the canvas pixel (10, 10), to JPEG accuracy.
        val got = decoded.getPixel(0, 0)
        val want = c.bitmap.getPixel(10, 10)
        for (shift in listOf(16, 8, 0)) {
            val diff = kotlin.math.abs(((got shr shift) and 0xFF) - ((want shr shift) and 0xFF))
            assertTrue("channel at $shift differs by $diff", diff <= 12)
        }
    }

    @Test
    fun `a crop that is the whole canvas is written without copying it`() {
        val c = canvas()
        val uri = publish(c)
        val decoded = BitmapFactory.decodeFile(PanoramaStore.fileOf(ContentUris.parseId(Uri.parse(uri))).path)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)
        assertFalse("the canvas itself is not recycled by the writer", c.bitmap.isRecycled)
    }

    @Test
    fun `no GPS and no other metadata from the photos reaches the file`() {
        val uri = publish(canvas())
        val file = PanoramaStore.fileOf(ContentUris.parseId(Uri.parse(uri)))
        val exif = ExifInterface(file.path)
        assertNull("no latitude and longitude", exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("no Exif segment", text.contains("Exif"))
        assertFalse("no GPS words", text.contains("GPS"))
        assertFalse("no XMP packet", text.contains("xmpmeta"))
    }

    // ---- never a partial row -------------------------------------------------------------------

    @Test
    fun `a photo that became protected means no row and no staging file`() {
        try {
            publish(canvas(), allowed = { false })
            fail("expected the protected exception")
        } catch (expected: PanoramaProtectedException) {
            // the file was never created
        }
        assertEquals("nothing was inserted", 0, PanoramaStore.insertedInto.size)
        assertTrue(PanoramaStore.rows.isEmpty())
        assertTrue("the staged JPEG was deleted: ${stagedFiles()}", stagedFiles().isEmpty())
    }

    @Test
    fun `a copy that fails deletes the pending row`() {
        PanoramaStore.failOpen = true
        try {
            publish(canvas())
            fail("expected a failure")
        } catch (expected: java.io.IOException) {
            // fine
        }
        assertEquals("the row was made", 1, PanoramaStore.insertedInto.size)
        assertTrue("and removed again", PanoramaStore.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `a row the system will not make visible is deleted`() {
        PanoramaStore.failUnpend = true
        try {
            publish(canvas())
            fail("expected a failure")
        } catch (expected: java.io.IOException) {
            // fine
        }
        assertTrue(PanoramaStore.rows.isEmpty())
    }

    @Test
    fun `a provider that refuses the insert is a failure with nothing left`() {
        PanoramaStore.refuseInsert = true
        try {
            publish(canvas())
            fail("expected a failure")
        } catch (expected: java.io.IOException) {
            // fine
        }
        assertTrue(PanoramaStore.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `cancelling while the bytes are being copied removes the row`() {
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(canvas(), AutoCrop.CropRect(0, 0, 120, 80), taken, "IMG_0001.jpg", { true }, {})
            }
            // The provider cancels the job as it hands out the file to write.
            PanoramaStore.onOpen = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("the row had been made", 1, PanoramaStore.insertedInto.size)
        assertTrue("and was deleted: ${PanoramaStore.rows}", PanoramaStore.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `cancelling before the file is created makes no row at all`() {
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                // The last look is the first moment after the encode; cancel in it.
                writer().publish(canvas(), AutoCrop.CropRect(0, 0, 120, 80), taken, "IMG_0001.jpg", { job.cancel(); true }, {})
            }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("no insert was ever made", 0, PanoramaStore.insertedInto.size)
        assertTrue(stagedFiles().isEmpty())
    }

    // ---- dates, names, progress ---------------------------------------------------------------

    @Test
    fun `a date taken the scanner replaced when pending was cleared is put back`() {
        PanoramaStore.rewriteDateTakenOnPublish = 12_345L
        val uri = publish(canvas())
        assertEquals(taken, PanoramaStore.rows.getValue(ContentUris.parseId(Uri.parse(uri))).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
    }

    @Test
    fun `a photo with no known date is dated now, never 1970`() {
        val before = System.currentTimeMillis()
        val uri = runBlocking { writer().publish(canvas(), AutoCrop.CropRect(0, 0, 120, 80), 0L, "x.jpg", { true }, {}) }
        val date = PanoramaStore.rows.getValue(ContentUris.parseId(Uri.parse(uri))).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN)
        assertTrue(date >= before)
    }

    @Test
    fun `a second panorama of the same photo gets a new name and never replaces the first`() {
        val first = publish(canvas())
        val second = publish(canvas())
        val third = publish(canvas())
        val names = listOf(first, second, third).map {
            PanoramaStore.rows.getValue(ContentUris.parseId(Uri.parse(it))).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        }
        assertEquals(listOf("IMG_0001-panorama.jpg", "IMG_0001-panorama-2.jpg", "IMG_0001-panorama-3.jpg"), names)
        assertEquals("three rows, three files", 3, PanoramaStore.rows.size)
    }

    @Test
    fun `a name taken in another album does not count`() {
        PanoramaStore.seed("IMG_0001-panorama.jpg", "Pictures/Other/")
        val uri = publish(canvas())
        assertEquals(
            "IMG_0001-panorama.jpg",
            PanoramaStore.rows.getValue(ContentUris.parseId(Uri.parse(uri))).getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
        )
    }

    @Test
    fun `progress goes from encoded to finished`() {
        val seen = ArrayList<Float>()
        publish(canvas(), progress = { seen += it })
        assertEquals(2, seen.size)
        assertTrue(seen[0] in 0.1f..0.9f)
        assertEquals(1f, seen.last(), 0f)
    }

    @Test
    fun `old staging files are swept and fresh ones are kept`() {
        val dir = File(context.cacheDir, PanoramaWriter.STAGING_DIRECTORY).apply { mkdirs() }
        val old = File(dir, "pano-old.jpg").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        val fresh = File(dir, "pano-new.jpg").apply { writeText("x") }
        assertEquals(1, writer().pruneStaging())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a canvas that is not a bitmap canvas is refused`() {
        try {
            runBlocking { writer().publish(FakeCanvas(10, 10), AutoCrop.CropRect(0, 0, 10, 10), taken, "a.jpg", { true }, {}) }
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // fine
        }
        assertEquals(0, PanoramaStore.insertedInto.size)
    }

    @Test
    fun `a crop off the canvas is refused before anything is staged`() {
        try {
            publish(canvas(), AutoCrop.CropRect(0, 0, 500, 80))
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // fine
        }
        assertTrue(stagedFiles().isEmpty())
        assertEquals(0, PanoramaStore.insertedInto.size)
    }

    // ---- the file name ------------------------------------------------------------------------

    @Test
    fun `names are the stem of the first photo with panorama, a number when taken`() {
        assertEquals("IMG_0001-panorama.jpg", panoramaFileName("IMG_0001.jpg") { false })
        assertEquals("IMG_0001-panorama.jpg", panoramaFileName("IMG_0001.HEIC") { false })
        assertEquals("photo-panorama.jpg", panoramaFileName("  ") { false })
        assertEquals("noext-panorama.jpg", panoramaFileName("noext") { false })
        assertEquals(".hidden-panorama.jpg", panoramaFileName(".hidden") { false })
        assertEquals("a.b-panorama.jpg", panoramaFileName("a.b.jpg") { false })
        assertEquals("a_b_c-panorama.jpg", panoramaFileName("a/b\\c.jpg") { false })
        assertEquals("IMG-panorama.jpg", panoramaFileName("IMG-panorama.jpg") { false })
        val taken = setOf("a-panorama.jpg", "a-panorama-2.jpg", "a-panorama-3.jpg")
        assertEquals("a-panorama-4.jpg", panoramaFileName("a.jpg") { it in taken })
        assertTrue("a very long name is cut", panoramaFileName("x".repeat(400) + ".jpg") { false }.length < 140)
    }

    @Test
    fun `a library with endless clashes still gets a name`() {
        val name = panoramaFileName("a.jpg") { true }
        assertTrue(name, name.startsWith("a-panorama-") && name.endsWith(".jpg"))
    }

    // ---- the bitmap canvas ----------------------------------------------------------------------

    @Test
    fun `a new bitmap canvas is transparent and reads back what was written`() {
        val c = BitmapPanoCanvas(64, 48)
        val all = IntArray(64 * 48)
        c.read(0, 0, 64, 48, all)
        assertTrue("a new canvas is all zero", all.all { it == 0 })
        val patch = IntArray(5 * 4) { (0xFF shl 24) or ((it * 7) shl 8) or 0x10 }
        c.write(10, 20, 5, 4, patch)
        val back = IntArray(5 * 4)
        c.read(10, 20, 5, 4, back)
        assertArrayEquals(patch, back)
        c.read(9, 20, 1, 1, back)
        assertEquals("beside the patch is still empty", 0, back[0])
        c.release()
        assertTrue(c.bitmap.isRecycled)
    }

    @Test
    fun `disjoint rectangles written from four threads all land`() {
        val c = BitmapPanoCanvas(400, 100)
        val start = CountDownLatch(1)
        val workers = List(4) { k ->
            thread {
                start.await()
                val strip = IntArray(100 * 100) { (0xFF shl 24) or ((k + 1) * 0x10 shl 16) or (it % 251) }
                repeat(20) { c.write(k * 100, 0, 100, 100, strip) }
            }
        }
        start.countDown()
        workers.forEach { it.join(30_000) }
        val got = IntArray(400 * 100)
        c.read(0, 0, 400, 100, got)
        for (k in 0 until 4) for (i in 0 until 100 * 100) {
            val x = k * 100 + i % 100
            val y = i / 100
            assertEquals((0xFF shl 24) or ((k + 1) * 0x10 shl 16) or (i % 251), got[y * 400 + x])
        }
    }

    @Test
    fun `the compositor draws the same pixels on a bitmap canvas as on a heap canvas`() {
        val scene = ColourScene.of(900, 300, 11)
        val xs = listOf(0, 260, 520)
        val sources = xs.map { x -> FunctionSource(380, 280, 1.0e7) { w, h -> scene.tile(x, 10, w, h) } }
        val positions = xs.map { PanoPoint(it, 0) }
        val heap = stitch(sources, positions)
        val plan = heap.plan
        val bitmap = BitmapPanoCanvas(plan.canvasWidth, plan.canvasHeight)
        val composed = runBlocking { PanoCompositor.compose(sources, positions, plan, bitmap) }
        assertEquals(heap.composed.crop, composed.crop)
        val got = IntArray(plan.canvasWidth * plan.canvasHeight)
        bitmap.read(0, 0, plan.canvasWidth, plan.canvasHeight, got)
        assertArrayEquals("bit for bit with the heap canvas", heap.canvas.pixels, got)
        assertNotNull(composed.crop)
    }
}

/**
 * A MediaStore stand-in that keeps its rows: an insert makes a row with the values it was given
 * (and a file behind it), an update merges values, a delete removes the row, and a query by id or by
 * name and album answers from the rows. Switches make each step fail the way the real provider can.
 */
class PanoramaStore : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        insertedInto += uri
        if (refuseInsert) return null
        insertedPending += values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: 0
        val id = ++lastId
        rows[id] = ContentValues(values)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        if (failUnpend && values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) return 0
        row.putAll(values)
        if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) {
            rewriteDateTakenOnPublish?.let { row.put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
        }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
        if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        val last = uri.lastPathSegment
        if (last != null && last.all { it.isDigit() }) {
            rows[last.toLong()]?.let { row -> cursor.addRow(columns.map { row.get(it) }) }
            return cursor
        }
        // By name, and by album when the selection asks for one.
        val byAlbum = selection?.contains(MediaStore.MediaColumns.RELATIVE_PATH) == true
        for ((id, row) in rows) {
            val nameMatches = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == args?.getOrNull(0)
            val albumMatches = !byAlbum || row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == args?.getOrNull(1)
            if (nameMatches && albumMatches) cursor.addRow(columns.map { if (it == MediaStore.MediaColumns._ID) id else row.get(it) })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        onOpen()
        // A descriptor that cannot be written to: the copy fails on its first write (Robolectric's
        // resolver hands out a stream even when the provider throws, so a refusal cannot be simulated).
        if (failOpen) return ParcelFileDescriptor.open(fileOf(ContentUris.parseId(uri)).apply { createNewFile() }, ParcelFileDescriptor.MODE_READ_ONLY)
        return ParcelFileDescriptor.open(
            fileOf(ContentUris.parseId(uri)),
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    companion object {
        val rows = LinkedHashMap<Long, ContentValues>()
        val insertedInto = ArrayList<Uri>()
        val insertedPending = ArrayList<Int>()
        var lastId = 0L
        var refuseInsert = false
        var failOpen = false
        var failUnpend = false
        var rewriteDateTakenOnPublish: Long? = null
        var onOpen: () -> Unit = {}
        private var directory: File = File(".")

        fun reset(cache: File) {
            rows.clear()
            insertedInto.clear()
            insertedPending.clear()
            lastId = 0
            refuseInsert = false
            failOpen = false
            failUnpend = false
            rewriteDateTakenOnPublish = null
            onOpen = {}
            directory = File(cache, "fake-panorama-store").apply { deleteRecursively(); mkdirs() }
        }

        fun fileOf(id: Long): File = File(directory, "$id.bin")

        /** A row that is already in the library, in [album]. */
        fun seed(name: String, album: String) {
            rows[++lastId] = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, album)
            }
        }
    }
}
