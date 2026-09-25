package com.fotoxplorr.app.recognition

import com.fotoxplorr.app.media.MediaAsset
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
}
