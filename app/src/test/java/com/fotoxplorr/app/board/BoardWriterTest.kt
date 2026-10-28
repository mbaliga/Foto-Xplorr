package com.fotoxplorr.app.board

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
import com.fotoxplorr.app.editor.OutputFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.IOException

/**
 * [BoardWriter] with real bitmaps (Robolectric native graphics) and a stand-in for MediaStore's
 * provider that keeps its rows. What this cannot show: the real provider's own refusals, its renaming
 * of a clashing name, the expiry of a pending row, and the scanner reading the finished file; those are
 * on the device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BoardWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val taken = 1_700_000_000_000L

    @Before
    fun attach() {
        BoardMediaProvider.reset(context.cacheDir)
        Robolectric.buildContentProvider(BoardMediaProvider::class.java).create(MediaStore.AUTHORITY)
        File(context.cacheDir, BoardWriter.STAGING_DIRECTORY).deleteRecursively()
    }

    private fun writer() = BoardWriter(context)

    private fun picture(w: Int = 120, h: Int = 80, alpha: Int = 255): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i -> (alpha shl 24) or (((i % w) * 2) shl 16) or (((i / w) * 3) shl 8) or 0x40 }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun publish(
        b: Bitmap = picture(),
        format: OutputFormat = OutputFormat.JPEG,
        name: String = "Spring",
        allowed: () -> Boolean = { true },
        progress: (Float) -> Unit = {},
    ): BoardPublished = runBlocking { writer().publish(b, format, 92, name, taken, allowed, progress) }

    private fun rowOf(p: BoardPublished) = BoardMediaProvider.rows.getValue(ContentUris.parseId(Uri.parse(p.uri)))

    private fun fileOf(p: BoardPublished) = BoardMediaProvider.fileOf(ContentUris.parseId(Uri.parse(p.uri)))

    private fun stagedFiles(): List<String> = File(context.cacheDir, BoardWriter.STAGING_DIRECTORY).list()?.toList().orEmpty()

    // ---- the row and the file ----

    @Test
    fun `a board becomes one visible row in the boards album with the type and name asked`() {
        val p = publish(format = OutputFormat.PNG)
        val row = rowOf(p)
        assertEquals("Spring.png", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("Spring.png", p.displayName)
        assertEquals("image/png", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Fotoz/Boards/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(taken, row.getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
        assertEquals("pending is cleared at the end", 0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("and was set when the row was made", 1, BoardMediaProvider.insertedPending.single())
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, BoardMediaProvider.insertedInto.single())
        assertTrue("the staging file is gone: ${stagedFiles()}", stagedFiles().isEmpty())
    }

    @Test
    fun `a PNG keeps every pixel including see-through ones`() {
        val b = picture(alpha = 128)
        val p = publish(b, OutputFormat.PNG)
        val bytes = fileOf(p).readBytes()
        assertEquals("a PNG starts 89 50 4E 47", 0x89, bytes[0].toInt() and 0xFF)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)
        val got = decoded.getPixel(5, 5)
        assertEquals("alpha survives", 128, got ushr 24)
        val red = (got shr 16) and 0xFF
        assertTrue("the red channel is right (was $red)", kotlin.math.abs(red - 10) <= 3)
    }

    @Test
    fun `a JPEG is a JPEG of the whole picture`() {
        val p = publish(picture(), OutputFormat.JPEG)
        val bytes = fileOf(p).readBytes()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        assertEquals("image/jpeg", rowOf(p).getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Spring.jpg", rowOf(p).getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)
    }

    @Test
    fun `the picture handed over is not recycled by the writer`() {
        val b = picture()
        publish(b)
        assertFalse(b.isRecycled)
    }

    @Test
    fun `no GPS and no other metadata reaches the file`() {
        for (format in listOf(OutputFormat.JPEG, OutputFormat.PNG)) {
            val file = fileOf(publish(format = format))
            val exif = ExifInterface(file.path)
            assertNull("no latitude and longitude", exif.latLong)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("no GPS words in $format", text.contains("GPS"))
            assertFalse("no XMP packet in $format", text.contains("xmpmeta"))
            assertFalse("no Exif segment in $format", text.contains("Exif"))
        }
    }

    // ---- never a partial row ----

    @Test
    fun `a photo that became protected means no row and no staging file`() {
        try {
            publish(allowed = { false })
            fail("expected the protected exception")
        } catch (expected: BoardProtectedException) {
            // the file was never created
        }
        assertEquals("nothing was inserted", 0, BoardMediaProvider.insertedInto.size)
        assertTrue(BoardMediaProvider.rows.isEmpty())
        assertTrue("the staged file was deleted: ${stagedFiles()}", stagedFiles().isEmpty())
    }

    @Test
    fun `a copy that fails deletes the pending row`() {
        BoardMediaProvider.failOpen = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertEquals("the row was made", 1, BoardMediaProvider.insertedInto.size)
        assertTrue("and removed again", BoardMediaProvider.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `a write that fails on a nearly full phone says there is no space, and leaves nothing`() {
        BoardMediaProvider.failOpen = true
        try {
            runBlocking { BoardWriter(context, freeSpace = { 1_000_000L }).publish(picture(), OutputFormat.PNG, 92, "a", taken, { true }, {}) }
            fail("expected a failure")
        } catch (expected: BoardNoSpaceException) {
            // the job reads it as a full phone
        }
        assertTrue(BoardMediaProvider.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `a write that fails on a phone with room is a plain failure`() {
        BoardMediaProvider.failOpen = true
        try {
            runBlocking { BoardWriter(context, freeSpace = { 5_000_000_000L }).publish(picture(), OutputFormat.PNG, 92, "a", taken, { true }, {}) }
            fail("expected a failure")
        } catch (expected: IOException) {
            assertFalse(expected is BoardNoSpaceException)
        }
        assertTrue(BoardMediaProvider.rows.isEmpty())
    }

    @Test
    fun `a photo that became protected is still protected on a nearly full phone`() {
        try {
            runBlocking { BoardWriter(context, freeSpace = { 0L }).publish(picture(), OutputFormat.PNG, 92, "a", taken, { false }, {}) }
            fail("expected the protected exception")
        } catch (expected: BoardProtectedException) {
            // the refusal is not a save failure
        }
    }

    @Test
    fun `a row the system will not make visible is deleted`() {
        BoardMediaProvider.failUnpend = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(BoardMediaProvider.rows.isEmpty())
    }

    @Test
    fun `a provider that refuses the insert is a failure with nothing left`() {
        BoardMediaProvider.refuseInsert = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(BoardMediaProvider.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `cancelling while the bytes are being copied removes the row`() {
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(picture(), OutputFormat.PNG, 92, "x", taken, { true }, {})
            }
            // The provider cancels the job as it hands out the file to write.
            BoardMediaProvider.onOpen = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("the row had been made", 1, BoardMediaProvider.insertedInto.size)
        assertTrue("and was deleted: ${BoardMediaProvider.rows}", BoardMediaProvider.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `cancelling before the file is created makes no row at all`() {
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                // The last look is the first moment after the encode; cancel in it.
                writer().publish(picture(), OutputFormat.PNG, 92, "x", taken, { job.cancel(); true }, {})
            }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("no insert was ever made", 0, BoardMediaProvider.insertedInto.size)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `cancelling just after the row was made visible removes it, so Cancelled never has a file behind it`() {
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(picture(), OutputFormat.PNG, 92, "x", taken, { true }, {})
            }
            // The cancel lands while IS_PENDING is being cleared: after the last check, before publish returns.
            BoardMediaProvider.onUnpend = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue("the caller sees a cancel", job.isCancelled)
        }
        assertEquals("the row had been made visible", 1, BoardMediaProvider.insertedInto.size)
        assertTrue("and was deleted again: ${BoardMediaProvider.rows}", BoardMediaProvider.rows.isEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    // ---- dates, names, progress ----

    @Test
    fun `a date the scanner replaced when pending was cleared is put back`() {
        BoardMediaProvider.rewriteDateTakenOnPublish = 12_345L
        assertEquals(taken, rowOf(publish()).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
    }

    @Test
    fun `no known date means now, never 1970`() {
        val before = System.currentTimeMillis()
        val p = runBlocking { writer().publish(picture(), OutputFormat.PNG, 92, "x", 0L, { true }, {}) }
        assertTrue(rowOf(p).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN) >= before)
    }

    @Test
    fun `a second save of the same board gets a new name and never replaces the first`() {
        val names = List(3) { publish(format = OutputFormat.PNG).displayName }
        assertEquals(listOf("Spring.png", "Spring-2.png", "Spring-3.png"), names)
        assertEquals("three rows, three files", 3, BoardMediaProvider.rows.size)
    }

    @Test
    fun `a name taken in another album does not count`() {
        BoardMediaProvider.seed("Spring.png", "Pictures/Other/")
        assertEquals("Spring.png", publish(format = OutputFormat.PNG).displayName)
    }

    @Test
    fun `progress goes from encoded to finished`() {
        val seen = ArrayList<Float>()
        publish(progress = { seen += it })
        assertEquals(2, seen.size)
        assertTrue(seen[0] in 0.1f..0.9f)
        assertEquals(1f, seen.last(), 0f)
    }

    @Test
    fun `old staging files are swept and fresh ones are kept`() {
        val dir = File(context.cacheDir, BoardWriter.STAGING_DIRECTORY).apply { mkdirs() }
        val old = File(dir, "board-old.png").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        val fresh = File(dir, "board-new.png").apply { writeText("x") }
        assertEquals(1, writer().pruneStaging())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a picture that was recycled is refused before anything is staged`() {
        val b = picture().also { it.recycle() }
        try {
            publish(b)
            fail("expected a refusal")
        } catch (expected: IllegalArgumentException) {
            // fine
        }
        assertEquals(0, BoardMediaProvider.insertedInto.size)
        assertTrue(stagedFiles().isEmpty())
    }
}

/**
 * A stand-in for MediaStore's provider that keeps its rows: an insert keeps the values (and a file
 * behind the row), an update merges values, a delete removes the row, and a query by id or by name and
 * album answers from the rows. Switches make each step fail the way the real provider can.
 */
class BoardMediaProvider : ContentProvider() {
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
            onUnpend()
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
        // A descriptor that cannot be written to: the copy fails on its first write.
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
        var onUnpend: () -> Unit = {}
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
            onUnpend = {}
            directory = File(cache, "fake-board-store").apply { deleteRecursively(); mkdirs() }
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
