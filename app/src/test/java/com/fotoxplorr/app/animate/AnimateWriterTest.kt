package com.fotoxplorr.app.animate

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * [AnimateWriter] with a stand-in for MediaStore's provider that keeps its rows: what is inserted
 * into which collection with which values, what is updated and deleted, the bytes behind a row, and a
 * query by name. What this cannot show: the real provider's own refusals (a folder it rejects for a
 * video, a name it renames), the expiry of a pending row, and the scanner reading the finished file;
 * those are on the device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AnimateWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val taken = 1_700_000_000_000L

    @Before
    fun attach() {
        AnimateStore.reset(context.cacheDir)
        Robolectric.buildContentProvider(AnimateStore::class.java).create(MediaStore.AUTHORITY)
        File(context.cacheDir, AnimateWriter.STAGING_DIRECTORY).deleteRecursively()
    }

    private fun writer(freeSpace: (File) -> Long = { 5_000_000_000L }) = AnimateWriter(context, freeSpace)

    private fun staged(size: Int = 200_000, seed: Int = 3): File {
        val dir = writer().stagingDirectory()
        return File.createTempFile("anim-", ".bin", dir).apply { writeBytes(ByteArray(size) { ((it * 31 + seed) and 255).toByte() }) }
    }

    private fun publish(
        file: File = staged(),
        output: AnimOutput = AnimOutput.GIF,
        name: String = "IMG_0001.jpg",
        date: Long = taken,
        allowed: () -> Boolean = { true },
        progress: (Float) -> Unit = {},
        w: AnimateWriter = writer(),
    ): String = runBlocking { w.publish(file, output, date, name, allowed, progress) }

    private fun row(uri: String): ContentValues = AnimateStore.rows.getValue(ContentUris.parseId(Uri.parse(uri)))

    private fun stagedFiles(): List<String> = File(context.cacheDir, AnimateWriter.STAGING_DIRECTORY).list()?.toList().orEmpty()

    // ---- the row and the file ---------------------------------------------------------------------------

    @Test
    fun `a GIF becomes one visible image row in the animations album, dated by the first source`() {
        val uri = publish()
        val r = row(uri)
        assertEquals("IMG_0001-animation.gif", r.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/gif", r.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Fotoz/Animations/", r.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(taken, r.getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
        assertEquals("pending is cleared at the end", 0, r.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("and was set when the row was made", 1, AnimateStore.insertedPending.single())
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, AnimateStore.insertedInto.single())
    }

    @Test
    fun `an APNG is a png file with the png type`() {
        val r = row(publish(output = AnimOutput.APNG))
        assertEquals("IMG_0001-animation.png", r.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/png", r.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, AnimateStore.insertedInto.single())
    }

    @Test
    fun `a video goes to the video collection with the video date column`() {
        val r = row(publish(output = AnimOutput.VIDEO, name = "VID_0001.mp4"))
        assertEquals("VID_0001-animation.mp4", r.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("video/mp4", r.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, AnimateStore.insertedInto.single())
        assertEquals(taken, r.getAsLong(MediaStore.Video.VideoColumns.DATE_TAKEN))
        assertEquals("Pictures/Fotoz/Animations/", r.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun `the saved bytes are exactly the staged bytes, so nothing is added to them`() {
        val file = staged(300_000, seed = 9)
        val uri = publish(file)
        val saved = AnimateStore.fileOf(ContentUris.parseId(Uri.parse(uri))).readBytes()
        assertArrayEquals(file.readBytes(), saved)
        val text = String(saved, Charsets.ISO_8859_1)
        assertFalse("no GPS words were written", text.contains("GPSLatitude"))
    }

    @Test
    fun `the staged file is the job's to delete, the writer leaves it`() {
        val file = staged()
        publish(file)
        assertTrue(file.exists())
    }

    // ---- never a partial row -------------------------------------------------------------------------------

    @Test
    fun `a photo that became protected means no row`() {
        try {
            publish(allowed = { false })
            fail("expected the protected exception")
        } catch (expected: AnimateProtectedException) {
            // the row was never created
        }
        assertEquals("nothing was inserted", 0, AnimateStore.insertedInto.size)
        assertTrue(AnimateStore.rows.isEmpty())
    }

    @Test
    fun `a copy that fails deletes the pending row`() {
        AnimateStore.failOpen = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertEquals("the row was made", 1, AnimateStore.insertedInto.size)
        assertTrue("and removed again", AnimateStore.rows.isEmpty())
    }

    @Test
    fun `a write that fails on a nearly full phone says there is no space, and leaves nothing`() {
        AnimateStore.failOpen = true
        try {
            publish(w = writer(freeSpace = { 1_000_000L }))
            fail("expected a failure")
        } catch (expected: AnimateNoSpaceException) {
            assertTrue("the job reads it as a full phone", AnimateJob.isOutOfSpace(expected))
        }
        assertTrue(AnimateStore.rows.isEmpty())
    }

    @Test
    fun `a write that fails on a phone with room is a plain failure`() {
        AnimateStore.failOpen = true
        try {
            publish(w = writer(freeSpace = { 5_000_000_000L }))
            fail("expected a failure")
        } catch (expected: IOException) {
            assertFalse(expected is AnimateNoSpaceException)
        }
        assertTrue(AnimateStore.rows.isEmpty())
    }

    @Test
    fun `a protected photo is still protected on a nearly full phone`() {
        try {
            publish(allowed = { false }, w = writer(freeSpace = { 0L }))
            fail("expected the protected exception")
        } catch (expected: AnimateProtectedException) {
            // the refusal is not a save failure
        }
    }

    @Test
    fun `a row the system will not make visible is deleted`() {
        AnimateStore.failUnpend = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(AnimateStore.rows.isEmpty())
    }

    @Test
    fun `a provider that refuses the insert is a failure with nothing left`() {
        AnimateStore.refuseInsert = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(AnimateStore.rows.isEmpty())
    }

    @Test
    fun `cancelling while the bytes are being copied removes the row`() {
        runBlocking {
            val file = staged(2_000_000)
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(file, AnimOutput.GIF, taken, "IMG_0001.jpg", { true }, {})
            }
            // The provider cancels the job as it hands out the file to write.
            AnimateStore.onOpen = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("the row had been made", 1, AnimateStore.insertedInto.size)
        assertTrue("and was deleted: ${AnimateStore.rows}", AnimateStore.rows.isEmpty())
    }

    @Test
    fun `cancelling before the row is created makes no row at all`() {
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            val file = staged()
            job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                // The last look is the first moment after the checks; cancel in it.
                writer().publish(file, AnimOutput.GIF, taken, "IMG_0001.jpg", { job.cancel(); true }, {})
            }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("no insert was ever made", 0, AnimateStore.insertedInto.size)
    }

    @Test
    fun `cancelling just after the row was made visible removes it, so Cancelled never has a file behind it`() {
        runBlocking {
            val file = staged()
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(file, AnimOutput.VIDEO, taken, "IMG_0001.jpg", { true }, {})
            }
            // The cancel lands while IS_PENDING is being cleared: after the last check, before publish returns.
            AnimateStore.onUnpend = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue("the caller sees a cancel", job.isCancelled)
        }
        assertEquals("the row had been made visible", 1, AnimateStore.insertedInto.size)
        assertTrue("and was deleted again: ${AnimateStore.rows}", AnimateStore.rows.isEmpty())
    }

    // ---- dates, names, progress -----------------------------------------------------------------------------

    @Test
    fun `a date taken the scanner replaced when pending was cleared is put back`() {
        AnimateStore.rewriteDateTakenOnPublish = 12_345L
        assertEquals(taken, row(publish()).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
        AnimateStore.rewriteDateTakenOnPublish = 12_345L
        assertEquals(taken, row(publish(output = AnimOutput.VIDEO)).getAsLong(MediaStore.Video.VideoColumns.DATE_TAKEN))
    }

    @Test
    fun `a source with no known date is dated now, never 1970`() {
        val before = System.currentTimeMillis()
        val date = row(publish(date = 0L)).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN)
        assertTrue(date >= before)
    }

    @Test
    fun `a second animation of the same photo gets a new name and never replaces the first`() {
        val names = List(3) { row(publish()).getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }
        assertEquals(listOf("IMG_0001-animation.gif", "IMG_0001-animation-2.gif", "IMG_0001-animation-3.gif"), names)
        assertEquals("three rows, three files", 3, AnimateStore.rows.size)
    }

    @Test
    fun `a name taken in another album does not count`() {
        AnimateStore.seed("IMG_0001-animation.gif", "Pictures/Other/")
        assertEquals("IMG_0001-animation.gif", row(publish()).getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
    }

    @Test
    fun `a GIF and an APNG of the same photo do not clash, they differ by their extension`() {
        val a = row(publish(output = AnimOutput.GIF)).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        val b = row(publish(output = AnimOutput.APNG)).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        assertEquals("IMG_0001-animation.gif", a)
        assertEquals("IMG_0001-animation.png", b)
    }

    @Test
    fun `progress follows the copy and ends at 1`() {
        val seen = ArrayList<Float>()
        publish(staged(1_000_000), progress = { seen += it })
        assertTrue("several steps for a big file: $seen", seen.size >= 4)
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(seen.dropLast(1).all { it < 1f })
    }

    @Test
    fun `old working files are swept and fresh ones are kept`() {
        val dir = writer().stagingDirectory()
        val old = File(dir, "anim-old.gif").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        val fresh = File(dir, "anim-new.gif").apply { writeText("x") }
        assertEquals(1, writer().pruneStaging())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `publishing sweeps old working files too`() {
        val dir = writer().stagingDirectory()
        val old = File(dir, "anim-old.gif").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        publish()
        assertFalse(old.exists())
        assertTrue("and the file just staged is not old", stagedFiles().isNotEmpty())
    }

    @Test
    fun `an old frames folder left by a killed video job is swept with its pictures`() {
        val dir = writer().stagingDirectory()
        val old = System.currentTimeMillis() - 3 * 3_600_000L
        val frames = File(dir, "frames-123").apply { mkdirs() }
        val pictures = (0 until 3).map { File(frames, "f%05d.jpg".format(it)).apply { writeText("x"); setLastModified(old) } }
        frames.setLastModified(old)
        assertEquals(1, writer().pruneStaging())
        assertFalse(frames.exists())
        assertTrue(pictures.none { it.exists() })
    }

    @Test
    fun `a frames folder still being written is kept even when the folder itself is old`() {
        val dir = writer().stagingDirectory()
        val old = System.currentTimeMillis() - 3 * 3_600_000L
        val frames = File(dir, "frames-456").apply { mkdirs() }
        val fresh = File(frames, "f00000.jpg").apply { writeText("x") }
        frames.setLastModified(old)
        assertEquals(0, writer().pruneStaging())
        assertTrue(frames.exists() && fresh.exists())
    }

    @Test
    fun `a job's first look at its working folder sweeps what a killed one left, with no save needed`() {
        val dir = writer().stagingDirectory()
        val frames = File(dir, "frames-789").apply { mkdirs() }
        File(frames, "f00000.jpg").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        frames.setLastModified(System.currentTimeMillis() - 3 * 3_600_000L)
        File(dir, "anim-old.mp4").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        writer().stagingDirectory()
        assertEquals(emptyList<String>(), stagedFiles())
    }

    // ---- the file name --------------------------------------------------------------------------------------

    @Test
    fun `names are the stem of the first source with animation, the type's extension, a number when taken`() {
        assertEquals("IMG_0001-animation.gif", animationFileName("IMG_0001.jpg", AnimOutput.GIF) { false })
        assertEquals("IMG_0001-animation.png", animationFileName("IMG_0001.HEIC", AnimOutput.APNG) { false })
        assertEquals("clip-animation.mp4", animationFileName("clip.mov", AnimOutput.VIDEO) { false })
        assertEquals("animation-animation.gif", animationFileName("  ", AnimOutput.GIF) { false })
        assertEquals("noext-animation.gif", animationFileName("noext", AnimOutput.GIF) { false })
        assertEquals(".hidden-animation.gif", animationFileName(".hidden", AnimOutput.GIF) { false })
        assertEquals("a.b-animation.gif", animationFileName("a.b.jpg", AnimOutput.GIF) { false })
        assertEquals("a_b_c-animation.gif", animationFileName("a/b\\c.jpg", AnimOutput.GIF) { false })
        assertEquals("loop-animation.gif", animationFileName("loop-animation.gif", AnimOutput.GIF) { false })
        val taken = setOf("a-animation.gif", "a-animation-2.gif", "a-animation-3.gif")
        assertEquals("a-animation-4.gif", animationFileName("a.jpg", AnimOutput.GIF) { it in taken })
        assertTrue("a very long name is cut", animationFileName("x".repeat(400) + ".jpg", AnimOutput.GIF) { false }.length < 140)
    }

    @Test
    fun `a library with endless clashes still gets a name`() {
        val name = animationFileName("a.jpg", AnimOutput.GIF) { true }
        assertTrue(name, name.startsWith("a-animation-") && name.endsWith(".gif"))
    }

    // ---- the output types -----------------------------------------------------------------------------------

    @Test
    fun `each output has its own extension and type, and only a video is a video`() {
        assertEquals("gif" to "image/gif", AnimOutput.GIF.extension to AnimOutput.GIF.mime)
        assertEquals("png" to "image/png", AnimOutput.APNG.extension to AnimOutput.APNG.mime)
        assertEquals("mp4" to "video/mp4", AnimOutput.VIDEO.extension to AnimOutput.VIDEO.mime)
        assertEquals(listOf(false, false, true), AnimOutput.values().map { it.isVideo })
        assertEquals(AnimFormat.APNG, AnimOutput.APNG.planFormat)
        assertEquals(AnimFormat.GIF, AnimOutput.GIF.planFormat)
    }

    @Test
    fun `the staging directory is made when missing`() {
        File(context.cacheDir, AnimateWriter.STAGING_DIRECTORY).deleteRecursively()
        assertTrue(writer().stagingDirectory().isDirectory)
    }

    @Test
    fun `a video frame is written as a JPEG of the right size`() {
        val maker = Media3VideoMaker(context)
        val file = File.createTempFile("frame", ".jpg", context.cacheDir)
        val w = 64
        val h = 48
        maker.writeFrame(IntArray(w * h) { 0xFF000000.toInt() or (it * 5 and 0xFFFFFF) }, w, h, file)
        val bytes = file.readBytes()
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val back = android.graphics.BitmapFactory.decodeFile(file.path)
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        assertEquals(Bitmap.Config.ARGB_8888, back.config)
    }
}

/**
 * A MediaStore stand-in that keeps its rows: an insert makes a row with the values it was given (and a
 * file behind it), an update merges values, a delete removes the row, and a query by id or by name and
 * album answers from the rows. Switches make each step fail the way the real provider can.
 */
class AnimateStore : ContentProvider() {
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
            rewriteDateTakenOnPublish?.let {
                row.put(MediaStore.Images.ImageColumns.DATE_TAKEN, it)
                row.put(MediaStore.Video.VideoColumns.DATE_TAKEN, it)
            }
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
            directory = File(cache, "fake-animate-store").apply { deleteRecursively(); mkdirs() }
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
