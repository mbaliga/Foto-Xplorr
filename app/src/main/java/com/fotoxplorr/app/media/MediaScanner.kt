package com.fotoxplorr.app.media

import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.Flow

sealed interface ScanEvent {
    data class Started(val source: String) : ScanEvent
    data class Progress(val scanned: Int, val discovered: Int) : ScanEvent
    data class AssetFound(val asset: MediaAsset) : ScanEvent

    /**
     * @param total rows visited by this pass — the whole library for a full scan, only the
     *   changed rows for a delta.
     * @param plan which kind of pass this was, so the UI can distinguish "indexed your
     *   library" from "picked up 2 new photos".
     * @param newestModifiedSeconds the largest `DATE_MODIFIED` seen, which becomes the next
     *   watermark. Null when the pass saw no rows at all, in which case the existing
     *   watermark must be kept rather than reset.
     */
    data class Completed(
        val total: Int,
        val plan: ScanPlan = ScanPlan.Full,
        val newestModifiedSeconds: Long? = null,
    ) : ScanEvent

    data class Failed(val error: Throwable) : ScanEvent
}

interface MediaScanner {
    /**
     * Run [plan]. A [ScanPlan.Delta] must query only rows at or after its bound — the whole
     * point is that a new screenshot costs a handful of rows, not a re-read of the library.
     */
    fun scan(plan: ScanPlan = ScanPlan.Full): Flow<ScanEvent>
}

interface MediaRepository {
    fun observeAll(): Flow<List<MediaAsset>>
    suspend fun upsert(items: List<MediaAsset>)
    suspend fun remove(ids: Set<MediaId>)

    /**
     * Deletes every row whose id is NOT in [keep] -- the sweep half of a full scan's deletion
     * reconciliation (TRAPS #1/#8). Replaces the delete-everything-then-reinsert-everything
     * `replaceAll` this interface used to require: only a completed full pass ever needs to
     * remove anything at all, and every row it DID see was already upserted during the scan, so
     * removing is all a sweep ever has left to do.
     */
    suspend fun removeAllExcept(keep: Set<MediaId>)

    /**
     * How many assets are held. Used to decide between a full and a delta scan: a delta over
     * an empty repository would leave the library empty, so an empty store always forces a
     * full pass regardless of what the watermark says.
     */
    suspend fun count(): Int
}
