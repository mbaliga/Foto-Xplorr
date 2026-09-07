package com.fotoxplorr.app.audio

import com.fotoxplorr.app.media.ScanPlan
import com.fotoxplorr.app.media.ScanWatermark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * [com.fotoxplorr.app.media.MediaIndexer]'s exact batching/full-vs-delta logic, for [AudioAsset].
 * See that class's own doc for why a delta pass must never call [AudioRepository.replaceAll] —
 * the same reasoning applies here unchanged, which is exactly why this mirrors it line for line
 * rather than risking a subtly different reimplementation of a rule this important.
 */
class AudioIndexer(
    private val scanner: AudioScanner,
    private val repository: AudioRepository,
    private val watermark: ScanWatermark,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    fun refresh(userRequested: Boolean = false): Flow<AudioScanEvent> = channelFlow {
        val plan = ScanPlan.decide(
            lastCompletedSeconds = watermark.lastCompletedSeconds(),
            knownAssetCount = repository.count(),
            userRequested = userRequested,
        )

        val discovered = ArrayList<AudioAsset>()
        val pending = ArrayList<AudioAsset>(batchSize)

        scanner.scan(plan).collect { event ->
            when (event) {
                is AudioScanEvent.AssetFound -> {
                    discovered += event.asset
                    pending += event.asset
                    if (pending.size >= batchSize) {
                        repository.upsert(pending.toList())
                        pending.clear()
                    }
                }

                is AudioScanEvent.Completed -> {
                    if (pending.isNotEmpty()) {
                        repository.upsert(pending.toList())
                        pending.clear()
                    }
                    when (plan) {
                        is ScanPlan.Full -> repository.replaceAll(discovered)
                        is ScanPlan.Delta -> Unit
                    }
                    event.newestModifiedSeconds?.let { watermark.record(it) }
                }

                else -> Unit
            }
            send(event)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 512
    }
}
