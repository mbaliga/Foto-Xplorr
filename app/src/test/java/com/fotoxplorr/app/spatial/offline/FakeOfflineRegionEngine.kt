package com.fotoxplorr.app.spatial.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.takeWhile

/**
 * An in-memory [OfflineRegionEngine] that keeps the contract the real one must keep.
 *
 * It never touches a network. A test scripts the downloader by calling [emit]; [calls] records
 * every call in order so a test can prove what did NOT happen (no [resume] on create, no
 * [delete] on cancel). Shared by the other tests of this package.
 */
class FakeOfflineRegionEngine(override var throttleInstalled: Boolean = true) : OfflineRegionEngine {

    private class Entry(val id: Long, var record: RegionRecord) {
        val feed = MutableSharedFlow<RegionProgress?>(replay = 1, extraBufferCapacity = 64)
        var progress: RegionProgress = RegionProgress.EMPTY
        var running = false
    }

    private val entries = LinkedHashMap<Long, Entry>()
    private var nextId = 1L

    /** Every call made, as `name` or `name:id`, oldest first. */
    val calls = mutableListOf<String>()

    fun isRunning(id: Long): Boolean = entries[id]?.running == true

    /**
     * Pretends the downloader reported [progress] for region [id]. A complete reading also stops
     * the region, as the real downloader does by itself.
     */
    fun emit(id: Long, progress: RegionProgress) {
        val entry = entries[id] ?: return
        entry.progress = progress
        if (progress.complete) entry.running = false
        entry.feed.tryEmit(progress)
    }

    override suspend fun list(): EngineResult<List<StoredRegion>> {
        calls += "list"
        return EngineResult.Ok(entries.values.map(::stored))
    }

    override suspend fun create(record: RegionRecord): EngineResult<StoredRegion> {
        calls += "create"
        val entry = Entry(nextId++, record)
        entries[entry.id] = entry
        entry.feed.tryEmit(entry.progress)
        return EngineResult.Ok(stored(entry))
    }

    override suspend fun resume(id: Long): EngineResult<Unit> {
        calls += "resume:$id"
        if (!throttleInstalled) return EngineResult.Failure(EngineFailure.THROTTLE_NOT_INSTALLED)
        val entry = entries[id] ?: return missing()
        entry.running = true
        return EngineResult.Ok(Unit)
    }

    override suspend fun pause(id: Long): EngineResult<Unit> {
        calls += "pause:$id"
        val entry = entries[id] ?: return missing()
        entry.running = false
        return EngineResult.Ok(Unit)
    }

    override suspend fun delete(id: Long): EngineResult<Unit> {
        calls += "delete:$id"
        val entry = entries.remove(id) ?: return missing()
        entry.feed.tryEmit(null)
        return EngineResult.Ok(Unit)
    }

    override fun progress(id: Long): Flow<RegionProgress> = flow {
        val entry = entries[id] ?: return@flow
        emitAll(entry.feed.takeWhile { it != null }.map { it!! })
    }

    private fun stored(entry: Entry) = StoredRegion(entry.id, entry.record, entry.progress, entry.running)

    private fun missing() = EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
}
