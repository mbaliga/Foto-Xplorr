package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

class MediaIndexer(
    private val scanner: MediaScanner,
    private val repository: MediaRepository,
) {
    fun refresh(): Flow<ScanEvent> = channelFlow {
        val discovered = ArrayList<MediaAsset>()

        scanner.scan().collect { event ->
            when (event) {
                is ScanEvent.AssetFound -> discovered += event.asset
                is ScanEvent.Completed -> repository.replaceAll(discovered)
                else -> Unit
            }
            send(event)
        }
    }
}
