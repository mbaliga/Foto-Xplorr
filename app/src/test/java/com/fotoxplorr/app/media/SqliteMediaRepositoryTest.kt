package com.fotoxplorr.app.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0-09: [SqliteMediaRepository.removeAllExcept] against the real, disk-backed SQLite database,
 * not a fake -- the sweep half of a full scan's deletion reconciliation. The [CatalogueMergeTest]
 * suite pins [mergeIntoSortedCatalogue] the same way: not by inspecting [removeAllExcept]'s own
 * internals, but by proving its two sides -- the in-memory mirror ([SqliteMediaRepository.observeAll])
 * and what is actually left in the database ([SqliteMediaRepository.awaitLoaded] against a FRESH
 * instance, which only ever reads from disk -- never the mirror it would otherwise share) -- always
 * agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteMediaRepositoryTest {

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = id,
        dateModifiedSeconds = id,
        width = 100,
        height = 100,
        sizeBytes = 1_000,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `removeAllExcept keeps the in-memory mirror and the database in agreement`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = SqliteMediaRepository(context)
        // Forces the constructor's own background initial load (over the still-empty database)
        // to actually finish before any mutation below -- it isn't mutex-guarded against
        // upsert/removeAllExcept, so racing it here (a fresh instance, mutated immediately) can
        // otherwise overwrite a real mutation's state with a stale read, in either order.
        repository.awaitLoaded()
        val all = (1L..20L).map(::asset)
        repository.upsert(all)
        val keep = all.filter { it.id.value % 3 == 0L }.map { it.id }.toSet()

        repository.removeAllExcept(keep)

        val expected = all.filter { it.id in keep }.sortedBy { it.id.value }
        assertEquals(expected, repository.awaitLoaded().sortedBy { it.id.value })
        // A second, independent instance over the same database file reads only from disk --
        // never touching the first instance's own in-memory mirror -- so this proves the DELETE
        // itself landed, not merely that the mirror says the right thing.
        val reopened = SqliteMediaRepository(context)
        assertEquals(expected, reopened.awaitLoaded().sortedBy { it.id.value })
    }

    @Test
    fun `removeAllExcept over a chunk-sized keep set removes exactly the rest`() = runBlocking {
        // Exercises the same chunked-delete path remove() already uses, at a size that spans
        // more than one SQLITE_BIND_LIMIT (900) chunk.
        val context = RuntimeEnvironment.getApplication()
        val repository = SqliteMediaRepository(context)
        repository.awaitLoaded()
        val all = (1L..2_500L).map(::asset)
        repository.upsert(all)
        val keep = all.filter { it.id.value <= 2_000L }.map { it.id }.toSet()

        repository.removeAllExcept(keep)

        val loaded = repository.awaitLoaded()
        assertEquals(2_000, loaded.size)
        assertEquals(keep, loaded.map { it.id }.toSet())
    }

    @Test
    fun `removeAllExcept everything is a no-op`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = SqliteMediaRepository(context)
        repository.awaitLoaded()
        val all = (1L..5L).map(::asset)
        repository.upsert(all)

        repository.removeAllExcept(all.map { it.id }.toSet())

        assertEquals(all.sortedBy { it.id.value }, repository.awaitLoaded().sortedBy { it.id.value })
    }
}
