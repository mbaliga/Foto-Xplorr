package com.fotoxplorr.app.audio

import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SqliteAudioRepository]'s persistence and ordering, the same way
 * `LibraryStoreCurationMemoryTest` pins `LibraryStore`'s SharedPreferences round-trip: a fake that
 * skipped SQLite entirely would pass while the real repository lost writes or forgot its schema
 * across a process restart, which is exactly the case this exists to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqliteAudioRepositoryTest {

    private fun repository() = SqliteAudioRepository(RuntimeEnvironment.getApplication())

    private fun asset(id: Long, title: String, artist: String? = null, dateAdded: Long = id) = AudioAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/audio/media/$id",
        displayName = "$title.mp3",
        title = title,
        artist = artist,
        album = null,
        mimeType = "audio/mpeg",
        durationMillis = 180_000,
        sizeBytes = 4_000_000,
        dateAddedSeconds = dateAdded,
        dateModifiedSeconds = dateAdded,
    )

    @Test
    fun `replaceAll persists and awaitLoaded sees it on a fresh instance`() = runBlocking {
        val first = repository()
        first.replaceAll(listOf(asset(1, "Zebra"), asset(2, "Apple")))

        val second = repository()
        val loaded = second.awaitLoaded()
        assertEquals(listOf("Apple", "Zebra"), loaded.map { it.title })
    }

    @Test
    fun `upsert merges into the existing catalogue rather than replacing it`() = runBlocking {
        val repo = repository()
        repo.replaceAll(listOf(asset(1, "One"), asset(2, "Two")))
        repo.upsert(listOf(asset(3, "Three")))

        assertEquals(setOf("One", "Two", "Three"), repo.observeAll().first().map { it.title }.toSet())
    }

    @Test
    fun `upsert overwrites a row with the same id rather than duplicating it`() = runBlocking {
        val repo = repository()
        repo.replaceAll(listOf(asset(1, "Old Title", artist = "Old Artist")))
        repo.upsert(listOf(asset(1, "New Title", artist = "New Artist")))

        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("New Title", all.single().title)
        assertEquals("New Artist", all.single().artist)
    }

    @Test
    fun `remove deletes from both the in-memory state and the persisted table`() = runBlocking {
        val first = repository()
        first.replaceAll(listOf(asset(1, "Keep"), asset(2, "Gone")))
        first.remove(setOf(MediaId(2)))

        assertEquals(listOf("Keep"), first.observeAll().first().map { it.title })

        val second = repository()
        assertEquals(listOf("Keep"), second.awaitLoaded().map { it.title })
    }

    @Test
    fun `ordering is by title, case-insensitively, same as InMemoryAudioRepository`() = runBlocking {
        val repo = repository()
        repo.replaceAll(listOf(asset(1, "banana"), asset(2, "Apple"), asset(3, "cherry")))
        assertEquals(listOf("Apple", "banana", "cherry"), repo.observeAll().first().map { it.title })
    }

    @Test
    fun `count reflects the in-memory mirror without a suspending database read`() = runBlocking {
        val repo = repository()
        assertEquals(0, repo.count())
        repo.replaceAll(listOf(asset(1, "A"), asset(2, "B")))
        assertEquals(2, repo.count())
    }

    @Test
    fun `album id and track number survive a round trip through the database`() = runBlocking {
        val withAlbumInfo = asset(1, "Track").copy(albumId = 42L, trackNumber = 5)
        val repo = repository()
        repo.replaceAll(listOf(withAlbumInfo))

        val reloaded = repository().awaitLoaded().single()
        assertEquals(42L, reloaded.albumId)
        assertEquals(5, reloaded.trackNumber)
    }

    @Test
    fun `a null artist, album, album id and track number round-trip as null, not zero or blank`() = runBlocking {
        val repo = repository()
        repo.replaceAll(listOf(asset(1, "No Metadata")))

        val reloaded = repository().awaitLoaded().single()
        assertTrue(reloaded.artist == null)
        assertTrue(reloaded.album == null)
        assertTrue(reloaded.albumId == null)
        assertTrue(reloaded.trackNumber == null)
    }
}
