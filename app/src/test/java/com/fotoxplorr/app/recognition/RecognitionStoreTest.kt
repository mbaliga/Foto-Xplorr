package com.fotoxplorr.app.recognition

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0-12: failure bookkeeping (bounded retries) and the throttled reload, against the real
 * [RecognitionStore]/[RecognitionOpenHelper], not a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecognitionStoreTest {

    private fun asset(id: Long, dateModifiedSeconds: Long = 1_000L, isTrashed: Boolean = false) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = dateModifiedSeconds,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = isTrashed,
    )

    @Test
    fun `three failures at the same revision excludes the asset from pending`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        val revision = target.recognitionRevision()

        store.recordFailure(target.id, revision)
        assertTrue(target in store.pendingAssets(listOf(target)))
        store.recordFailure(target.id, revision)
        assertTrue(target in store.pendingAssets(listOf(target)))
        store.recordFailure(target.id, revision)
        assertFalse(target in store.pendingAssets(listOf(target)))
    }

    @Test
    fun `a revision change resets the failure count and makes the asset pending again`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        repeat(3) { store.recordFailure(target.id, target.recognitionRevision()) }
        assertFalse(target in store.pendingAssets(listOf(target)))

        val changed = asset(1, dateModifiedSeconds = 9_999L)
        assertTrue(changed in store.pendingAssets(listOf(changed)))
    }

    @Test
    fun `a trashed or video asset is never pending regardless of failure history`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val trashed = asset(1, isTrashed = true)
        assertFalse(trashed in store.pendingAssets(listOf(trashed)))
    }

    @Test
    fun `a success clears a previously recorded failure`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        repeat(3) { store.recordFailure(target.id, target.recognitionRevision()) }
        assertFalse(target in store.pendingAssets(listOf(target)))

        store.clearFailure(target.id)
        assertTrue(target in store.pendingAssets(listOf(target)))
    }

    @Test
    fun `upsert throttles how often the published index refreshes, but always persists to disk`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(AssetRecognition(MediaId(1), 0, faceCount = 0, petVerdict = PetVerdict.DOG)))
        val afterFirst = store.observe().value
        assertEquals(setOf(MediaId(1)), afterFirst.petMediaIds)

        // Immediately after -- well inside the throttle window -- must NOT republish yet.
        store.upsert(listOf(AssetRecognition(MediaId(2), 0, faceCount = 0, petVerdict = PetVerdict.CAT)))
        assertEquals(afterFirst, store.observe().value)

        // The write itself still landed on disk -- an explicit reload (what the indexer's own
        // unconditional end-of-pass call does) sees it immediately, regardless of the throttle.
        store.reload()
        assertEquals(setOf(MediaId(1), MediaId(2)), store.observe().value.petMediaIds)
    }

    @Test
    fun `removeMissing also drops a stale asset's recorded failure`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        val revision = target.recognitionRevision()
        store.recordFailure(target.id, revision)
        store.recordFailure(target.id, revision)

        // The asset is no longer in the catalogue at all (e.g. permanently deleted) -- its
        // failure bookkeeping should be dropped along with everything else about it, not linger.
        store.removeMissing(emptySet())

        // If the two prior failures had survived, this one would bring the count to 3 and
        // exclude the asset; since removeMissing cleared them, a fresh count of 1 still leaves
        // it pending.
        store.recordFailure(target.id, revision)
        assertTrue(target in store.pendingAssets(listOf(target)))
    }

    // ---- palettes (store v5) ----

    private val red = PaletteSwatch(0xFFC81E1E.toInt(), 0.6f)
    private val blue = PaletteSwatch(0xFF1E3CC8.toInt(), 0.4f)

    private fun rawSql(sql: String) {
        SQLiteDatabase.openDatabase(
            RuntimeEnvironment.getApplication().getDatabasePath("foto_xplorr_recognition.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { it.execSQL(sql) }
    }

    private fun paletteRowCount(mediaId: Long): Int =
        SQLiteDatabase.openDatabase(
            RuntimeEnvironment.getApplication().getDatabasePath("foto_xplorr_recognition.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT count(*) FROM asset_palette WHERE media_id = $mediaId", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    @Test
    fun `a palette round-trips through the store, in order, in both shapes`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(listOf(AssetRecognition(MediaId(1), 0, faceCount = 0, palette = listOf(red, blue))))
        store.reload()

        val index = store.observe().value
        assertEquals(listOf(red, blue), index.paletteFor(MediaId(1)))
        val sample = index.searchPaletteFor(MediaId(1))
        assertEquals(2, sample.size)
        assertEquals(red.argb, sample.argbAt(0))
        assertEquals(blue.proportion, sample.proportionAt(1), 0f)
        // A photo the store knows nothing about has no palette, in either shape, without a lookup failing.
        assertTrue(index.paletteFor(MediaId(99)).isEmpty())
        assertTrue(index.searchPaletteFor(MediaId(99)).isEmpty)
    }

    @Test
    fun `malformed palette rows read back as an empty palette, never as a crash or a half palette`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        store.upsert(
            listOf(
                AssetRecognition(MediaId(1), 0, faceCount = 0, palette = listOf(red, blue)),
                AssetRecognition(MediaId(2), 0, faceCount = 0, palette = listOf(red, blue)),
                AssetRecognition(MediaId(3), 0, faceCount = 0, palette = listOf(red, blue)),
            ),
        )
        // A share no quantiser can produce, on one of photo 2's rows; non-numeric text on photo 3's.
        rawSql("UPDATE asset_palette SET proportion = 7.5 WHERE media_id = 2 AND swatch_index = 1")
        rawSql("UPDATE asset_palette SET proportion = 'garbage' WHERE media_id = 3")

        store.reload()

        val index = store.observe().value
        assertEquals(listOf(red, blue), index.paletteFor(MediaId(1)))
        assertTrue(index.paletteFor(MediaId(2)).isEmpty())
        assertTrue(index.paletteFor(MediaId(3)).isEmpty())
        // Rows exist, however bad: the photo still counts as sampled, so the backfill does not
        // loop on it forever. Fixing a genuinely corrupt row is a re-index, not a retry.
        val sampled = listOf(asset(2), asset(3))
        store.upsert(sampled.map { AssetRecognition(it.id, it.recognitionRevision(), faceCount = 0, palette = listOf(red)) })
        rawSql("UPDATE asset_palette SET proportion = 7.5 WHERE media_id IN (2, 3)")
        assertTrue(store.pendingPaletteOnly(sampled).isEmpty())
    }

    @Test
    fun `pendingPaletteOnly names recognised photos with no palette rows, and nothing else`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val legacy = asset(1) // recognised, palette rows deleted below -- a pre-v5 row
        val unknown = asset(2) // never recognised: the detector pass's job, not this one's
        val sampled = asset(3) // recognised with a palette
        val blank = asset(4) // recognised, sampled, nothing visible: stored as the marker row
        val stale = asset(5, dateModifiedSeconds = 1L) // recognised at an older revision: detector pass again
        val video = asset(6).copy(mimeType = "video/mp4")
        store.upsert(
            listOf(
                AssetRecognition(legacy.id, legacy.recognitionRevision(), faceCount = 0),
                AssetRecognition(sampled.id, sampled.recognitionRevision(), faceCount = 0, palette = listOf(red)),
                AssetRecognition(blank.id, blank.recognitionRevision(), faceCount = 0, palette = emptyList()),
                AssetRecognition(stale.id, asset(5, dateModifiedSeconds = 2L).recognitionRevision(), faceCount = 0),
                AssetRecognition(video.id, video.recognitionRevision(), faceCount = 0),
            ),
        )
        rawSql("DELETE FROM asset_palette WHERE media_id IN (1, 6)")

        val all = listOf(legacy, unknown, sampled, blank, stale, video)
        assertEquals(listOf(legacy), store.pendingPaletteOnly(all))
        assertEquals(listOf(unknown, stale), store.pendingAssets(all))

        // Once sampled -- even to an empty palette -- it is never asked again.
        store.upsertPalettes(mapOf(legacy.id to emptyList()))
        assertTrue(store.pendingPaletteOnly(all).isEmpty())
    }

    @Test
    fun `upsertPalettes writes only palettes and respects the reload throttle`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        store.upsert(listOf(AssetRecognition(target.id, target.recognitionRevision(), faceCount = 3, labels = listOf("Dog"))))
        rawSql("DELETE FROM asset_palette WHERE media_id = 1")
        val before = store.observe().value

        // Inside the throttle window: on disk, not yet published.
        store.upsertPalettes(mapOf(target.id to listOf(red, blue)))
        assertEquals(before, store.observe().value)

        store.reload()
        val after = store.observe().value
        assertEquals(listOf(red, blue), after.paletteFor(target.id))
        // Everything else about the row is exactly as it was.
        assertEquals(setOf(target.id), after.peopleMediaIds)
        assertEquals(listOf("Dog"), after.labelsByMedia[target.id])
    }

    @Test
    fun `three palette-only failures stop the palette-only retries`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val target = asset(1)
        store.upsert(listOf(AssetRecognition(target.id, target.recognitionRevision(), faceCount = 0)))
        rawSql("DELETE FROM asset_palette WHERE media_id = 1")

        repeat(2) {
            store.recordFailure(target.id, target.recognitionRevision())
            assertEquals(listOf(target), store.pendingPaletteOnly(listOf(target)))
        }
        store.recordFailure(target.id, target.recognitionRevision())
        assertTrue(store.pendingPaletteOnly(listOf(target)).isEmpty())
    }

    @Test
    fun `removeMissing and clear take the palette rows with them`() = runBlocking {
        val store = RecognitionStore(RuntimeEnvironment.getApplication())
        val kept = asset(1)
        val gone = asset(2)
        store.upsert(
            listOf(
                AssetRecognition(kept.id, kept.recognitionRevision(), faceCount = 0, palette = listOf(red)),
                AssetRecognition(gone.id, gone.recognitionRevision(), faceCount = 0, palette = listOf(blue)),
            ),
        )

        store.removeMissing(setOf(kept.id))
        assertEquals(listOf(red), store.observe().value.paletteFor(kept.id))
        assertTrue(store.observe().value.paletteFor(gone.id).isEmpty())
        // No palette rows left behind for the gone photo either: were there any, a photo
        // re-added under the same id would wrongly read as already sampled.
        assertEquals(1, paletteRowCount(kept.id.value))
        assertEquals(0, paletteRowCount(gone.id.value))

        store.clear()
        assertTrue(store.observe().value.palettesByMedia.isEmpty())
        assertEquals(0, paletteRowCount(kept.id.value))
        store.reload()
        assertTrue(store.observe().value.palettesByMedia.isEmpty())
    }
}
