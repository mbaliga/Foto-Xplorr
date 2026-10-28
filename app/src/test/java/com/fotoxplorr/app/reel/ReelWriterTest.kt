package com.fotoxplorr.app.reel

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
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
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

/**
 * [ReelWriter] with a stand-in for MediaStore's provider that keeps its rows: what is inserted into which
 * collection with which values, what is updated and deleted, the bytes behind a row, and a query by name.
 * What this cannot show: the real provider's own refusals (a folder it rejects for a video, a name it
 * renames), the expiry of a pending row, and the scanner reading the finished file; those are on the device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReelWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val taken = 1_750_000_000_000L

    @Before
    fun attach() {
        ReelStore.reset(context.cacheDir)
        Robolectric.buildContentProvider(ReelStore::class.java).create(MediaStore.AUTHORITY)
        File(context.cacheDir, ReelWriter.STAGING_DIRECTORY).deleteRecursively()
    }

    private fun writer(freeSpace: (File) -> Long = { 5_000_000_000L }) = ReelWriter(context, freeSpace)

    private fun staged(size: Int = 200_000, seed: Int = 3): File {
        val dir = writer().stagingDirectory()
        return File.createTempFile("reel-", ".mp4", dir).apply { writeBytes(ByteArray(size) { ((it * 31 + seed) and 255).toByte() }) }
    }

    private fun publish(
        file: File = staged(),
        stem: String = "Lisbon",
        date: Long = taken,
        allowed: () -> Boolean = { true },
        progress: (Float) -> Unit = {},
        w: ReelWriter = writer(),
    ): String = runBlocking { w.publish(file, stem, date, allowed, progress) }

    private fun row(uri: String): ContentValues = ReelStore.rows.getValue(ContentUris.parseId(Uri.parse(uri)))

    // ---- the row and the file ---------------------------------------------------------------------------

    @Test
    fun `a reel becomes one visible video row in the reels album, dated by the first picture`() {
        val uri = publish()
        val r = row(uri)
        assertEquals("Lisbon.mp4", r.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("video/mp4", r.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Movies/Fotoz/Reels/", r.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(taken, r.getAsLong(MediaStore.Video.VideoColumns.DATE_TAKEN))
        assertEquals("pending is cleared at the end", 0, r.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("and was set when the row was made", 1, ReelStore.insertedPending.single())
        assertEquals("it is a video row", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ReelStore.insertedInto.single())
    }

    @Test
    fun `the saved bytes are exactly the staged bytes, so nothing is added to them`() {
        val file = staged(300_000, seed = 9)
        val uri = publish(file)
        val saved = ReelStore.fileOf(ContentUris.parseId(Uri.parse(uri))).readBytes()
        assertArrayEquals(file.readBytes(), saved)
        val row = row(uri)
        assertFalse("no place is asked for in the row", row.keySet().any { it.contains("latitude", true) || it.contains("longitude", true) })
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
        } catch (expected: ReelProtectedException) {
            // the row was never created
        }
        assertEquals("nothing was inserted", 0, ReelStore.insertedInto.size)
        assertTrue(ReelStore.rows.isEmpty())
    }

    @Test
    fun `a copy that fails deletes the pending row`() {
        ReelStore.failOpen = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertEquals("the row was made", 1, ReelStore.insertedInto.size)
        assertTrue("and removed again", ReelStore.rows.isEmpty())
    }

    @Test
    fun `a write that fails on a nearly full phone says there is no space, and leaves nothing`() {
        ReelStore.failOpen = true
        try {
            publish(w = writer(freeSpace = { 1_000_000L }))
            fail("expected a failure")
        } catch (expected: ReelNoSpaceException) {
            assertTrue(ReelFailures.isOutOfSpace(expected))
        }
        assertTrue(ReelStore.rows.isEmpty())
    }

    @Test
    fun `a write that fails on a phone with room is a plain failure`() {
        ReelStore.failOpen = true
        try {
            publish(w = writer(freeSpace = { 5_000_000_000L }))
            fail("expected a failure")
        } catch (expected: IOException) {
            assertFalse(expected is ReelNoSpaceException)
        }
        assertTrue(ReelStore.rows.isEmpty())
    }

    @Test
    fun `a protected photo is still protected on a nearly full phone`() {
        try {
            publish(allowed = { false }, w = writer(freeSpace = { 0L }))
            fail("expected the protected exception")
        } catch (expected: ReelProtectedException) {
            // the refusal is not a save failure
        }
    }

    @Test
    fun `a row the system will not make visible is deleted`() {
        ReelStore.failUnpend = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(ReelStore.rows.isEmpty())
    }

    @Test
    fun `a provider that refuses the insert is a failure with nothing left`() {
        ReelStore.refuseInsert = true
        try {
            publish()
            fail("expected a failure")
        } catch (expected: IOException) {
            // fine
        }
        assertTrue(ReelStore.rows.isEmpty())
    }

    @Test
    fun `cancelling while the bytes are being copied removes the row`() {
        runBlocking {
            val file = staged(2_000_000)
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(file, "Lisbon", taken, { true }, {})
            }
            // The provider cancels the job as it hands out the file to write.
            ReelStore.onOpen = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("the row had been made", 1, ReelStore.insertedInto.size)
        assertTrue("and was deleted: ${ReelStore.rows}", ReelStore.rows.isEmpty())
    }

    @Test
    fun `cancelling before the row is created makes no row at all`() {
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            val file = staged()
            job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                // The last look is the first moment after the checks; cancel in it.
                writer().publish(file, "Lisbon", taken, { job.cancel(); true }, {})
            }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertEquals("no insert was ever made", 0, ReelStore.insertedInto.size)
    }

    @Test
    fun `cancelling just after the row was made visible removes it, so Cancelled never has a file behind it`() {
        runBlocking {
            val file = staged()
            val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
                writer().publish(file, "Lisbon", taken, { true }, {})
            }
            // The cancel lands while IS_PENDING is being cleared: after the last check, before publish returns.
            ReelStore.onUnpend = { job.cancel() }
            job.start()
            withTimeout(30_000) { job.join() }
            assertTrue("the caller sees a cancel", job.isCancelled)
        }
        assertEquals("the row had been made visible", 1, ReelStore.insertedInto.size)
        assertTrue("and was deleted again: ${ReelStore.rows}", ReelStore.rows.isEmpty())
    }

    // ---- dates, names, progress -----------------------------------------------------------------------------

    @Test
    fun `a date taken the scanner replaced when pending was cleared is put back`() {
        ReelStore.rewriteDateTakenOnPublish = 12_345L
        assertEquals(taken, row(publish()).getAsLong(MediaStore.Video.VideoColumns.DATE_TAKEN))
    }

    @Test
    fun `a source with no known date is dated now, never 1970`() {
        val before = System.currentTimeMillis()
        val date = row(publish(date = 0L)).getAsLong(MediaStore.Video.VideoColumns.DATE_TAKEN)
        assertTrue(date >= before)
    }

    @Test
    fun `a second reel of the same name gets a new name and never replaces the first`() {
        val names = List(3) { row(publish()).getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }
        assertEquals(listOf("Lisbon.mp4", "Lisbon-2.mp4", "Lisbon-3.mp4"), names)
        assertEquals("three rows, three files", 3, ReelStore.rows.size)
    }

    @Test
    fun `a name taken in another album does not count`() {
        ReelStore.seed("Lisbon.mp4", "Movies/Other/")
        assertEquals("Lisbon.mp4", row(publish()).getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
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
        val old = File(dir, "reel-old.mp4").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        val fresh = File(dir, "reel-new.mp4").apply { writeText("x") }
        assertEquals(1, writer().pruneStaging())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `publishing sweeps old working files too`() {
        val dir = writer().stagingDirectory()
        val old = File(dir, "reel-old.mp4").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        publish()
        assertFalse(old.exists())
    }

    @Test
    fun `an old job folder is swept with what is in it and a fresh one is kept`() {
        val dir = writer().stagingDirectory()
        val old = File(dir, "job-1").apply { mkdirs() }
        File(old, "still-r1.jpg").writeText("x")
        File(old, "reel-0.mp4").writeText("x")
        old.walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        val fresh = File(dir, "job-2").apply { mkdirs() }
        File(fresh, "still-r1.jpg").writeText("x")
        assertEquals(1, writer().pruneStaging())
        assertFalse("a killed export's folder goes with its pictures", old.exists())
        assertTrue(fresh.isDirectory)
        assertTrue(File(fresh, "still-r1.jpg").exists())
    }

    @Test
    fun `a job folder that was written to lately is kept whatever its own date says`() {
        val dir = writer().stagingDirectory()
        val running = File(dir, "job-3").apply { mkdirs() }
        File(running, "card-t1.png").writeText("x").also { File(running, "card-t1.png").setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        File(running, "reel-0.mp4").writeText("still being written")
        running.setLastModified(System.currentTimeMillis() - 3 * 3_600_000L)
        assertEquals(0, writer().pruneStaging())
        assertTrue(File(running, "card-t1.png").exists())
        assertTrue(File(running, "reel-0.mp4").exists())
    }

    @Test
    fun `the writer sweeps when asked and publishing sweeps job folders too`() {
        val dir = writer().stagingDirectory()
        val old = File(dir, "job-4").apply { mkdirs() }
        File(old, "reel-0.mp4").writeText("x")
        old.walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        writer().sweepStaging()
        assertFalse(old.exists())

        val again = File(dir, "job-5").apply { mkdirs() }
        File(again, "reel-0.mp4").writeText("x")
        again.walkTopDown().forEach { it.setLastModified(System.currentTimeMillis() - 3 * 3_600_000L) }
        publish()
        assertFalse(again.exists())
    }

    @Test
    fun `the staging directory is made when missing`() {
        File(context.cacheDir, ReelWriter.STAGING_DIRECTORY).deleteRecursively()
        assertTrue(writer().stagingDirectory().isDirectory)
    }

    // ---- the file name -----------------------------------------------------------------------------------------

    @Test
    fun `a stem is the project's name cleaned, or the date`() {
        val utc = ZoneOffset.UTC
        assertEquals("Summer trip", reelStem("Summer trip", taken, utc))
        assertEquals("Summer trip", reelStem("  Summer trip.mp4  ", taken, utc))
        assertEquals("Summer trip", reelStem("Summer trip.MP4", taken, utc))
        assertEquals("a_b_c_d", reelStem("a/b\\c:d", taken, utc))
        assertEquals("ab", reelStem("a\u0000b\n", taken, utc))
        // A file name is cut on a whole character: an emoji at the cut does not leave half of itself.
        val stem = reelStem("z".repeat(99) + "\uD83D\uDE00", taken, utc)
        assertEquals("z".repeat(99), stem)
        assertEquals("Reel-2025-06-15", reelStem("", taken, utc))
        assertEquals("Reel-2025-06-15", reelStem("   ", taken, utc))
        assertEquals("a name that is only dots falls back", "Reel-2025-06-15", reelStem("...", taken, utc))
        assertEquals("the zone decides the day", "Reel-2025-06-16", reelStem("", taken, ZoneOffset.ofHours(10)))
        assertEquals(100, reelStem("x".repeat(400), taken, utc).length)
        assertTrue("no date is today, never 1970", !reelStem("", 0L, utc).contains("1970"))
    }

    @Test
    fun `a file name is the stem with mp4 and a number when taken`() {
        assertEquals("Lisbon.mp4", reelFileName("Lisbon") { false })
        assertEquals("Reel.mp4", reelFileName("  ") { false })
        val taken = setOf("a.mp4", "a-2.mp4", "a-3.mp4")
        assertEquals("a-4.mp4", reelFileName("a") { it in taken })
    }

    @Test
    fun `a library with endless clashes still gets a name`() {
        val name = reelFileName("a") { true }
        assertTrue(name, name.startsWith("a-") && name.endsWith(".mp4"))
    }
}

/**
 * A MediaStore stand-in that keeps its rows: an insert makes a row with the values it was given (and a file
 * behind it), an update merges values, a delete removes the row, and a query by id or by name and album answers
 * from the rows. Switches make each step fail the way the real provider can.
 */
class ReelStore : ContentProvider() {
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
            rewriteDateTakenOnPublish?.let { row.put(MediaStore.Video.VideoColumns.DATE_TAKEN, it) }
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
            directory = File(cache, "fake-reel-store").apply { deleteRecursively(); mkdirs() }
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
