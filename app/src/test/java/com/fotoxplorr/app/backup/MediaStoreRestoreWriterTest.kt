package com.fotoxplorr.app.backup

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaKind
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MediaStoreRestoreWriter] against a stand-in for MediaStore's provider that behaves the way the
 * real one matters here: an insert makes a row with the values it was given, the bytes go to a file
 * behind the row, clearing `IS_PENDING` can make the "scanner" rewrite `DATE_TAKEN`, and a delete
 * removes the row. What this cannot show: the real provider's own checks (a folder or type it
 * refuses), its renaming of a clashing name, the expiry of a pending row, and a row whose file will
 * not open (Robolectric's resolver hands back a stream whatever the provider says); those are on the
 * device checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreRestoreWriterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L

    @Before
    fun attach() {
        FakeMediaStore.reset(context.cacheDir)
        Robolectric.buildContentProvider(FakeMediaStore::class.java).create(MediaStore.AUTHORITY)
    }

    private fun item(
        name: String = "IMG_1.jpg",
        dir: String = "DCIM/Camera/",
        mime: String = "image/jpeg",
        kind: MediaKind? = MediaKind.IMAGE,
        taken: Long = 1_650_000_000_000L,
    ) = RestoreItem(
        entry = ManifestEntry(
            entryId = 1, mediaId = 41, displayName = name, relativePath = dir, mimeType = mime, sizeBytes = 5,
            dateTakenMillis = taken, dateModifiedSeconds = 9, sha256 = "00".repeat(32), quickHash = null, packName = "pack-0001.fzbp",
        ),
        action = RestoreAction.RESTORE,
        kind = kind,
        targetRelativePath = dir,
        targetDisplayName = name,
        matchedMediaId = null,
    )

    private fun writer() = MediaStoreRestoreWriter(context) { now }

    @Test
    fun `a new row is pending, in the right folder and collection, with the backed up dates`() {
        val pending = writer().begin(item())
        val row = FakeMediaStore.rows.values.single()

        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, FakeMediaStore.insertedInto.single())
        assertEquals("IMG_1.jpg", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("DCIM/Camera/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(1_650_000_000_000L, row.getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
        assertEquals(now / 1000, row.getAsLong(MediaStore.MediaColumns.DATE_ADDED))
        assertEquals("a pending row that nobody finishes expires by itself", now / 1000 + 86_400, row.getAsLong(MediaStore.MediaColumns.DATE_EXPIRES))
        pending.abort()
    }

    @Test
    fun `committing writes the bytes, clears pending and returns the new id and revision`() {
        val pending = writer().begin(item())
        pending.output.write(byteArrayOf(1, 2, 3, 4, 5))
        val committed = pending.commit()

        val id = committed.mediaId
        val row = FakeMediaStore.rows.getValue(id)
        assertEquals(0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), FakeMediaStore.fileOf(id).readBytes())
        assertEquals("the id comes from the insert uri", 1L, id)
        assertEquals(FakeMediaStore.MODIFIED_SECONDS, committed.dateModifiedSeconds)
    }

    @Test
    fun `a date taken the scanner changed when pending was cleared is put back`() {
        FakeMediaStore.rewriteDateTakenOnPublish = 1_111L
        val pending = writer().begin(item(taken = 1_650_000_000_000L))
        pending.output.write(1)
        val id = pending.commit().mediaId
        assertEquals(1_650_000_000_000L, FakeMediaStore.rows.getValue(id).getAsLong(MediaStore.Images.ImageColumns.DATE_TAKEN))
    }

    @Test
    fun `a date taken that was not changed is not written again`() {
        val pending = writer().begin(item())
        pending.output.write(1)
        pending.commit()
        assertEquals(1, FakeMediaStore.updates.count { it.containsKey(MediaStore.MediaColumns.IS_PENDING) })
        assertEquals(0, FakeMediaStore.updates.count { it.containsKey(MediaStore.Images.ImageColumns.DATE_TAKEN) })
    }

    @Test
    fun `no date taken is asked for when the backup did not know one`() {
        val pending = writer().begin(item(taken = 0))
        assertFalse(FakeMediaStore.rows.values.single().containsKey(MediaStore.Images.ImageColumns.DATE_TAKEN))
        pending.abort()
    }

    @Test
    fun `a thrown away file leaves no row behind`() {
        val pending = writer().begin(item())
        pending.output.write(byteArrayOf(9, 9))
        pending.abort()
        assertTrue(FakeMediaStore.rows.isEmpty())
        pending.abort() // more than once is fine
        assertEquals(1, FakeMediaStore.deleted.size)
    }

    @Test
    fun `a video goes to the video collection`() {
        val pending = writer().begin(item(name = "clip.mp4", dir = "Movies/Trips/", mime = "video/mp4", kind = MediaKind.VIDEO))
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, FakeMediaStore.insertedInto.single())
        assertEquals("Movies/Trips/", FakeMediaStore.rows.values.single().getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        pending.abort()
    }

    @Test
    fun `an item that is not an image or a video is refused before anything is inserted`() {
        try {
            writer().begin(item(kind = null))
            org.junit.Assert.fail("a file with no kind must not be inserted")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertTrue(FakeMediaStore.rows.isEmpty())
    }

    @Test
    fun `a provider that will not insert fails the create and leaves nothing`() {
        FakeMediaStore.refuseInsert = true
        try {
            writer().begin(item())
            org.junit.Assert.fail("a refused insert must fail the create")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertTrue(FakeMediaStore.rows.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MediaStoreRestoreWriterBeforeAndroid10Test {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun attach() {
        FakeMediaStore.reset(context.cacheDir)
        Robolectric.buildContentProvider(FakeMediaStore::class.java).create(MediaStore.AUTHORITY)
    }

    @Test
    fun `before Android 10 there is no folder and no pending flag to set`() {
        val item = RestoreItem(
            entry = ManifestEntry(
                entryId = 1, mediaId = 1, displayName = "a.jpg", relativePath = "DCIM/Camera/", mimeType = "image/jpeg", sizeBytes = 1,
                dateTakenMillis = 5, dateModifiedSeconds = 1, sha256 = "00".repeat(32), quickHash = null, packName = "pack-0001.fzbp",
            ),
            action = RestoreAction.RESTORE, kind = MediaKind.IMAGE, targetRelativePath = "DCIM/Camera/", targetDisplayName = "a.jpg", matchedMediaId = null,
        )
        val pending = MediaStoreRestoreWriter(context).begin(item)
        val row = FakeMediaStore.rows.values.single()
        assertNull(row.get(MediaStore.MediaColumns.RELATIVE_PATH))
        assertNull(row.get(MediaStore.MediaColumns.IS_PENDING))
        assertNull(row.get(MediaStore.MediaColumns.DATE_EXPIRES))
        pending.output.write(1)
        val committed = pending.commit()
        assertTrue(committed.mediaId > 0)
        assertEquals("nothing to clear", 0, FakeMediaStore.updates.count { it.containsKey(MediaStore.MediaColumns.IS_PENDING) })
    }

    @Test
    fun `the target says the folder cannot be chosen`() {
        assertFalse(MediaStoreRestoreTarget(context, emptyList(), lockedFolders = { emptySet() }).canChooseFolder)
    }
}

/** The pieces of MediaStore's provider that a restore touches, in memory and on temp files. */
class FakeMediaStore : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (refuseInsert) return null
        val id = ++lastId
        rows[id] = ContentValues(values)
        insertedInto += uri
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        val row = rows[id] ?: return 0
        updates += ContentValues(values)
        row.putAll(values)
        if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) {
            rewriteDateTakenOnPublish?.let { row.put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
        }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        deleted += uri
        return if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val row = rows[ContentUris.parseId(uri)] ?: return MatrixCursor(projection ?: emptyArray())
        val columns = projection ?: emptyArray()
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    if (column == MediaStore.MediaColumns.DATE_MODIFIED) MODIFIED_SECONDS else row.get(column)
                },
            )
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileOf(ContentUris.parseId(uri))
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    companion object {
        const val MODIFIED_SECONDS = 1_700_000_123L
        val rows = LinkedHashMap<Long, ContentValues>()
        val insertedInto = ArrayList<Uri>()
        val updates = ArrayList<ContentValues>()
        val deleted = ArrayList<Uri>()
        var lastId = 0L
        var refuseInsert = false
        var rewriteDateTakenOnPublish: Long? = null
        private var directory: File = File(".")

        fun reset(cache: File) {
            rows.clear()
            insertedInto.clear()
            updates.clear()
            deleted.clear()
            lastId = 0
            refuseInsert = false
            rewriteDateTakenOnPublish = null
            directory = File(cache, "fake-media-store").apply { deleteRecursively(); mkdirs() }
        }

        fun fileOf(id: Long): File = File(directory, "$id.bin")
    }
}
