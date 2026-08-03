package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow

sealed interface ScanEvent {
    data class Started(val source: String) : ScanEvent
    data class Progress(val scanned: Int, val discovered: Int) : ScanEvent
    data class AssetFound(val asset: MediaAsset) : ScanEvent
    data class Completed(val total: Int) : ScanEvent
    data class Failed(val error: Throwable) : ScanEvent
}

interface MediaScanner {
    fun scan(): Flow<ScanEvent>
}

interface MediaRepository {
    fun observeAll(): Flow<List<MediaAsset>>
    suspend fun replaceAll(items: List<MediaAsset>)
    suspend fun upsert(items: List<MediaAsset>)
    suspend fun remove(ids: Set<MediaId>)
}
