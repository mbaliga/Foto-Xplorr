package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIndexerTest {
    @Test
    fun `a full scan persists progressively and reconciles deletions at completion`() = runBlocking {
        val assets = listOf(asset(1), asset(2), asset(3))
        val repository = RecordingRepository()
        val scanner = RecordingScanner { plan ->
            flow {
                emit(ScanEvent.Started("test"))
                assets.forEach { emit(ScanEvent.AssetFound(it)) }
                emit(ScanEvent.Completed(assets.size, plan, newestModifiedSeconds = 3L))
            }
        }

        MediaIndexer(scanner, repository, FakeWatermark(), batchSize = 2)
            .refresh(userRequested = true)
            .collect {}

        assertEquals(listOf(listOf(assets[0], assets[1]), listOf(assets[2])), repository.upserts)
        assertEquals(assets, repository.replacement)
    }

    @Test
    fun `a delta scan must never replaceAll -- that would delete the untouched library`() = runBlocking {
        // The whole point of a delta: 21,526 known assets, one new screenshot. A replaceAll
        // with only the delta's contents would wipe the other 21,526.
        val repository = RecordingRepository().apply { seed(List(21_526) { asset(it + 100L) }) }
        val fresh = asset(999_999)
        val scanner = RecordingScanner { plan ->
            flow {
                emit(ScanEvent.Started("test"))
                emit(ScanEvent.AssetFound(fresh))
                emit(ScanEvent.Completed(1, plan, newestModifiedSeconds = 999_999L))
            }
        }

        MediaIndexer(scanner, repository, FakeWatermark(lastCompleted = 1_000L))
            .refresh()
            .collect {}

        assertTrue("a populated library + watermark must plan a delta", scanner.lastPlan is ScanPlan.Delta)
        assertEquals("the new asset must be upserted", listOf(listOf(fresh)), repository.upserts)
        assertNull("replaceAll must NOT run on a delta", repository.replacement)
    }

    @Test
    fun `the delta query is bounded by the rewound watermark`() = runBlocking {
        val repository = RecordingRepository().apply { seed(listOf(asset(1))) }
        val scanner = RecordingScanner { plan ->
            flow { emit(ScanEvent.Completed(0, plan, newestModifiedSeconds = null)) }
        }

        MediaIndexer(scanner, repository, FakeWatermark(lastCompleted = 5_000L)).refresh().collect {}

        assertEquals(
            ScanPlan.Delta(5_000L - ScanPlan.WATERMARK_REWIND_SECONDS),
            scanner.lastPlan,
        )
    }

    @Test
    fun `the watermark advances on a completed pass`() = runBlocking {
        val repository = RecordingRepository()
        val watermark = FakeWatermark()
        val scanner = RecordingScanner { plan ->
            flow { emit(ScanEvent.Completed(1, plan, newestModifiedSeconds = 4_242L)) }
        }

        MediaIndexer(scanner, repository, watermark).refresh(userRequested = true).collect {}

        assertEquals(4_242L, watermark.lastCompletedSeconds())
    }

    @Test
    fun `an empty pass leaves the watermark alone rather than resetting it`() = runBlocking {
        // A delta that finds nothing must not zero the mark -- that would force a full
        // re-scan of the entire library on the very next change.
        val repository = RecordingRepository().apply { seed(listOf(asset(1))) }
        val watermark = FakeWatermark(lastCompleted = 7_000L)
        val scanner = RecordingScanner { plan ->
            flow { emit(ScanEvent.Completed(0, plan, newestModifiedSeconds = null)) }
        }

        MediaIndexer(scanner, repository, watermark).refresh().collect {}

        assertEquals(7_000L, watermark.lastCompletedSeconds())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `batch size must be positive`() {
        MediaIndexer(
            scanner = RecordingScanner { flow {} },
            repository = RecordingRepository(),
            watermark = FakeWatermark(),
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

    private class RecordingScanner(
        private val events: (ScanPlan) -> Flow<ScanEvent>,
    ) : MediaScanner {
        var lastPlan: ScanPlan? = null
        override fun scan(plan: ScanPlan): Flow<ScanEvent> {
            lastPlan = plan
            return events(plan)
        }
    }

    private class FakeWatermark(private var lastCompleted: Long = 0L) : ScanWatermark {
        override fun lastCompletedSeconds(): Long = lastCompleted
        override fun record(seconds: Long) {
            if (seconds > lastCompleted) lastCompleted = seconds
        }
    }

    private class RecordingRepository : MediaRepository {
        val upserts = mutableListOf<List<MediaAsset>>()
        /** Null until replaceAll actually runs, so "it never ran" is distinguishable from "it ran empty". */
        var replacement: List<MediaAsset>? = null
        private val state = MutableStateFlow<List<MediaAsset>>(emptyList())

        fun seed(items: List<MediaAsset>) { state.value = items }

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

        override suspend fun count(): Int = state.value.size
    }
}
