package com.fotoxplorr.app.media

import com.fotoxplorr.core.organize.ScanPlan
import com.fotoxplorr.core.organize.SweepPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class MediaIndexer(
    private val scanner: MediaScanner,
    private val repository: MediaRepository,
    private val watermark: ScanWatermark,
    private val sweepPolicy: SweepPolicy = SweepPolicy,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** Told about a sweep the [sweepPolicy] refused, so a caller that can log (this class
     * deliberately can't -- see its own doc) still gets to know. A no-op by default so every
     * plain-JUnit test here stays exactly that: plain JUnit, no Android framework involved. */
    private val onSweepRefused: (catalogueSize: Int, missingCount: Int) -> Unit = { _, _ -> },
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /**
     * Bring the repository up to date.
     *
     * A full pass reconciles deletions; a delta pass upserts only what changed and leaves every
     * untouched row alone. Deletion reconciliation is therefore only possible on a full pass — a
     * delta cannot tell "absent because unchanged" from "absent because deleted" — so deletions
     * are picked up by the next full pass or by the repository's own removal calls when the user
     * trashes something in-app.
     *
     * A full pass's own deletion reconciliation ("sweep") is itself gated by [sweepPolicy]
     * (TRAPS #8): every row this pass discovered was already upserted above, so [sweepPolicy]
     * only ever has to decide whether it is safe to remove the rest.
     *
     * @param userRequested a human asked for this; always runs a full pass.
     * @param partialAccess true when this app currently holds only a limited "selected photos"
     *   grant rather than full media access — see [SweepPolicy] for why that refuses a sweep.
     */
    fun refresh(userRequested: Boolean = false, partialAccess: Boolean = false): Flow<ScanEvent> = channelFlow {
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
                        // Full pass: `discovered` is the whole truth this pass saw, so anything
                        // else the repository holds is either genuinely gone or, under partial
                        // access, simply never in scope — sweepPolicy tells the two apart.
                        is ScanPlan.Full -> {
                            val keep = discovered.mapTo(mutableSetOf()) { it.id }
                            val catalogueSize = repository.count()
                            val missingCount = (catalogueSize - keep.size).coerceAtLeast(0)
                            if (sweepPolicy.shouldSweep(catalogueSize, missingCount, userRequested, partialAccess)) {
                                repository.removeAllExcept(keep)
                            } else {
                                onSweepRefused(catalogueSize, missingCount)
                            }
                        }
                        // Delta pass: the upserts above already applied every change. Sweeping
                        // here would delete the entire untouched library.
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
