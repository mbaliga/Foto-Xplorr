package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

class MediaIndexer(
    private val scanner: MediaScanner,
    private val repository: MediaRepository,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    fun refresh(): Flow<ScanEvent> = channelFlow {
        val discovered = ArrayList<MediaAsset>()
        val pending = ArrayList<MediaAsset>(batchSize)

        scanner.scan().collect { event ->
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
                    repository.replaceAll(discovered)
                }

                else -> Unit
            }
            send(event)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 64
    }
}
