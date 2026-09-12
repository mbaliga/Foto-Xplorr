package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class MediaIndexer(
    private val scanner: MediaScanner,
    private val repository: MediaRepository,
    private val watermark: ScanWatermark,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /**
     * Bring the repository up to date.
     *
     * A full pass replaces everything; a delta pass upserts only what changed and leaves
     * every untouched row alone. Deletion reconciliation is therefore only possible on a
     * full pass — a delta cannot tell "absent because unchanged" from "absent because
     * deleted" — so deletions are picked up by the next full pass or by the repository's own
     * removal calls when the user trashes something in-app.
     *
     * @param userRequested a human asked for this; always runs a full pass.
     */
    fun refresh(userRequested: Boolean = false): Flow<ScanEvent> = channelFlow {
        val plan = ScanPlan.decide(
            lastCompletedSeconds = watermark.lastCompletedSeconds(),
            knownAssetCount = repository.count(),
            userRequested = userRequested,
        )

        val discovered = ArrayList<MediaAsset>()
        val pending = ArrayList<MediaAsset>(batchSize)

        scanner.scan(plan).collect { event ->
            when (event) {
                is ScanEvent.AssetFound -> {
                    discovered += event.asset
                    pending += event.asset
                    if (pending.size >= batchSize) {
                        repository.upsert(pending.toList())
                        pending.clear()
                    }
                }

                is ScanEvent.Completed -> {
                    if (pending.isNotEmpty()) {
                        repository.upsert(pending.toList())
                        pending.clear()
                    }
                    when (plan) {
                        // Full pass: `discovered` is the whole truth, so anything not in it
                        // is genuinely gone.
                        is ScanPlan.Full -> repository.replaceAll(discovered)
                        // Delta pass: the upserts above already applied every change. A
                        // replaceAll here would delete the entire untouched library.
                        is ScanPlan.Delta -> Unit
                    }
                    // Advance the watermark only on a pass that actually completed, and only
                    // when it saw something — an empty delta must not reset it to zero.
                    event.newestModifiedSeconds?.let { watermark.record(it) }
                }

                else -> Unit
            }
            send(event)
        }
    }

    private companion object {
        /**
         * How many assets accumulate before the catalogue is updated and the UI is told.
         *
         * Every flush publishes a new catalogue to a StateFlow the gallery collects, so the batch
         * size is really "how often does a scan interrupt the user". At 64 a large library
         * produced hundreds of interruptions during one scan; 512 cuts that by 8x while still
         * showing the first photos almost immediately, because the first flush happens as soon as
         * 512 have been read, not at the end.
         *
         * Tests that assert on batching pass their own value explicitly rather than relying on
         * this, so tuning it here cannot quietly rewrite what they check.
         */
        const val DEFAULT_BATCH_SIZE = 512
    }
}

/** Persisted high-water mark of the newest `DATE_MODIFIED` a completed scan has seen. */
interface ScanWatermark {
    fun lastCompletedSeconds(): Long
    fun record(seconds: Long)
}
