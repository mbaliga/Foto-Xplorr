package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIndexerTest {
    @Test
    fun `scan results are persisted progressively and reconciled at completion`() = runBlocking {
        val assets = listOf(asset(1), asset(2), asset(3))
        val repository = RecordingRepository()
        val scanner = object : MediaScanner {
            override fun scan(): Flow<ScanEvent> = flow {
                emit(ScanEvent.Started("test"))
                assets.forEach { emit(ScanEvent.AssetFound(it)) }
                emit(ScanEvent.Completed(assets.size))
            }
        }

        MediaIndexer(scanner, repository, batchSize = 2)
            .refresh()
            .collect {}

        assertEquals(listOf(listOf(assets[0], assets[1]), listOf(assets[2])), repository.upserts)
        assertEquals(listOf(assets), repository.replacement)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `batch size must be positive`() {
        MediaIndexer(
            scanner = object : MediaScanner {
                override fun scan(): Flow<ScanEvent> = flow {}
            },
            repository = RecordingRepository(),
            batchSize = 0,
        )
    }

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

    private class RecordingRepository : MediaRepository {
        val upserts = mutableListOf<List<MediaAsset>>()
        var replacement: List<MediaAsset> = emptyList()
        private val state = MutableStateFlow<List<MediaAsset>>(emptyList())

        override fun observeAll(): Flow<List<MediaAsset>> = state

        override suspend fun replaceAll(items: List<MediaAsset>) {
            replacement = items
            state.value = items
        }

        override suspend fun upsert(items: List<MediaAsset>) {
            upserts += items
            state.value = (state.value + items).distinctBy { it.id }
        }

        override suspend fun remove(ids: Set<MediaId>) {
            state.value = state.value.filterNot { it.id in ids }
        }
    }
}
