package com.fotoxplorr.app.recognition

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v6: face boxes and stored people, against the real store -- including the upgrade from a v5
 * database that holds faces, which must keep them and queue exactly those photos for a box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionStorePeopleTest {

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = 1_000L,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    private val vector = FaceClustering.normalized(FloatArray(16) { (it + 1).toFloat() })
    private val box = FaceBox(0.2f, 0.2f, 0.5f, 0.5f, 1.5f)

    private fun row(id: Long, withBox: Boolean = true) = AssetRecognition(
        mediaId = MediaId(id),
        sourceRevision = asset(id).recognitionRevision(),
        faceCount = 1,
        faceDescriptors = listOf(FaceDescriptor(MediaId(id), 0, vector, 0.09f, if (withBox) box else null)),
    )

    @Test
    fun `a stored box reads back exactly`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1)))
        store.reload()
        val face = store.observe().value.people.single().faces.single()
        assertEquals(box, face.box)
    }

    @Test
    fun `two photos of one face make one named-able person with a stable id`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1), row(2)))
        store.reload()
        val person = store.observe().value.roster.single()
        assertEquals(1L, person.id)
        assertEquals("Person 1", person.displayName)

        store.renamePerson(person.id, "Asha")
        assertEquals("Asha", store.observe().value.roster.single().displayName)

        // More photos arrive: same person, same id, name kept.
        store.upsert(listOf(row(3), row(4)))
        store.reload()
        val after = store.observe().value.roster.single()
        assertEquals(1L, after.id)
        assertEquals("Asha", after.name)
        assertEquals(4, after.mediaIds.size)

        // And a fresh store over the same file (a new process) still knows her.
        val reopened = RecognitionStore(RuntimeEnvironment.getApplication())
        reopened.reload()
        assertEquals("Asha", reopened.observe().value.roster.single().name)
    }

    @Test
    fun `not this person, hide and merge persist`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1), row(2), row(3)))
        store.reload()
        val id = store.observe().value.roster.single().id

        store.excludeFromPerson(id, MediaId(2))
        assertEquals(setOf(MediaId(1), MediaId(3)), store.observe().value.roster.single().mediaIds.toSet())

        store.hidePerson(id, true)
        assertTrue(store.observe().value.roster.single().hidden)
        store.hidePerson(id, false)

        // A second person with a different face, then merged into the first.
        val other = FaceClustering.normalized(FloatArray(16) { if (it % 2 == 0) 5f else -3f })
        fun otherRow(i: Long) = row(i).copy(faceDescriptors = listOf(FaceDescriptor(MediaId(i), 0, other, 0.09f, box)))
        store.upsert(listOf(otherRow(10), otherRow(11)))
        store.reload()
        assertEquals(2, store.observe().value.roster.size)
        val second = store.observe().value.roster.first { MediaId(10) in it.mediaIds }.id
        store.mergePeople(second, id)
        val merged = store.observe().value.roster.single()
        assertEquals(id, merged.id)
        assertTrue(MediaId(10) in merged.mediaIds && MediaId(1) in merged.mediaIds)
    }

    @Test
    fun `clear forgets people along with the faces they were remembered by`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1), row(2)))
        store.reload()
        store.renamePerson(1L, "Asha")
        store.clear()
        store.upsert(listOf(row(1), row(2)))
        store.reload()
        assertNull(store.observe().value.roster.single().name)
    }

    @Test
    fun `upgrading from v5 keeps the faces and queues exactly the boxless photos, first`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = context.getDatabasePath("foto_xplorr_recognition.db").also { it.parentFile?.mkdirs() }
        val legacy = asset(1)
        val faceless = asset(2)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE asset_recognition (media_id INTEGER PRIMARY KEY, source_revision INTEGER NOT NULL, " +
                    "face_count INTEGER NOT NULL, pet_verdict TEXT NOT NULL, identity_verdict TEXT NOT NULL, " +
                    "labels TEXT NOT NULL DEFAULT '', categories TEXT NOT NULL DEFAULT '', " +
                    "caption TEXT NOT NULL DEFAULT '', hashtags TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL(
                "CREATE TABLE face_descriptor (media_id INTEGER NOT NULL, face_index INTEGER NOT NULL, " +
                    "relative_area REAL NOT NULL, vector BLOB NOT NULL, PRIMARY KEY (media_id, face_index))",
            )
            db.execSQL(
                "CREATE TABLE asset_text_block (media_id INTEGER NOT NULL, block_index INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, left REAL NOT NULL, top REAL NOT NULL, right REAL NOT NULL, " +
                    "bottom REAL NOT NULL, PRIMARY KEY (media_id, block_index))",
            )
            db.execSQL(
                "CREATE TABLE recognition_failure (media_id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, " +
                    "attempts INTEGER NOT NULL, last_attempt_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE asset_palette (media_id INTEGER NOT NULL, swatch_index INTEGER NOT NULL, " +
                    "argb INTEGER NOT NULL, proportion REAL NOT NULL, PRIMARY KEY (media_id, swatch_index))",
            )
            for (a in listOf(legacy, faceless)) {
                db.execSQL(
                    "INSERT INTO asset_recognition (media_id, source_revision, face_count, pet_verdict, identity_verdict) " +
                        "VALUES (${a.id.value}, ${a.recognitionRevision()}, ${if (a == legacy) 1 else 0}, 'NONE', 'NONE')",
                )
            }
            db.execSQL("INSERT INTO face_descriptor VALUES (1, 0, 0.09, x'0000803f')")
            db.version = 5
        }

        val store = RecognitionStore(context)
        store.reload()

        // The face survived (a vector-bearing row, no box).
        val face = store.observe().value.peopleMediaIds
        assertEquals(setOf(MediaId(1)), face)
        // The face-bearing photo is read again; the faceless one is not.
        assertEquals(listOf(legacy), store.pendingAssets(listOf(faceless, legacy)))
        // Upserting it again with a box settles it.
        store.upsert(listOf(row(1)))
        assertTrue(store.pendingAssets(listOf(faceless, legacy)).isEmpty())
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(6, it.version) }
        assertNotNull(store.observe().value)
    }

    @Test
    fun `boxless photos are queued ahead of new ones`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = RecognitionStore(context)
        store.upsert(listOf(row(1)))
        // A legacy face (frame_aspect 0 = box never tried), as v5 wrote it.
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("foto_xplorr_recognition.db").path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { it.execSQL("UPDATE face_descriptor SET frame_aspect = 0 WHERE media_id = 1") }
        val fresh = asset(5)
        assertEquals(listOf(asset(1), fresh), store.pendingAssets(listOf(fresh, asset(1))))
    }

    @Test
    fun `a face whose box was tried and unusable is not read again on every pass`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1, withBox = false)))
        assertTrue(store.pendingAssets(listOf(asset(1))).isEmpty())
    }

    @Test
    fun `not this person on a selection is one write and keeps every other photo`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert((1L..6L).map { row(it) })
        store.reload()
        val id = store.observe().value.roster.single().id
        store.excludeFromPerson(id, setOf(MediaId(2), MediaId(4), MediaId(6)))
        assertEquals(setOf(MediaId(1), MediaId(3), MediaId(5)), store.observe().value.roster.single().mediaIds.toSet())
        store.excludeFromPerson(id, emptySet())
        assertEquals(3, store.observe().value.roster.single().mediaIds.size)
    }

    @Test
    fun `a finished pass says so only once something is indexed and nothing failed`() {
        assertEquals(false, RecognitionProgress().passFinished)
        assertEquals(true, RecognitionProgress(indexedCount = 10, completed = 3, total = 3).passFinished)
        assertEquals(false, RecognitionProgress(indexedCount = 10, completed = 3, total = 3, failed = 1).passFinished)
        assertEquals(false, RecognitionProgress(running = true, indexedCount = 10, completed = 1, total = 3).passFinished)
        assertEquals(false, RecognitionProgress(indexedCount = 10, completed = 1, total = 3).passFinished)
    }

    @Test
    fun `publishing a pass update keeps the indexed count reload recorded`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(row(1), row(2)))
        store.reload()
        store.publishProgress(RecognitionProgress(running = false, completed = 0, total = 0))
        assertEquals(2, store.observeProgress().value.indexedCount)
        assertTrue(store.observeProgress().value.passFinished)
    }
}
