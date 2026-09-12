package com.fotoxplorr.app.audio

import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.ScanPlan
import kotlinx.coroutines.flow.Flow

/**
 * [com.fotoxplorr.app.media.ScanEvent]'s exact shape, for [AudioAsset] instead of
 * [com.fotoxplorr.app.media.MediaAsset] — a parallel type rather than a generic
 * `ScanEvent<T>` because genericizing the existing, already-pinned photo/video scan pipeline
 * (`MediaIndexerTest`, `AndroidMediaStoreScannerSelectionTest`) to serve a second asset type is a
 * meaningfully riskier change than one small new file duplicating its shape.
 */
sealed interface AudioScanEvent {
    data class Started(val source: String) : AudioScanEvent
    data class Progress(val scanned: Int, val discovered: Int) : AudioScanEvent
    data class AssetFound(val asset: AudioAsset) : AudioScanEvent

    /** See [com.fotoxplorr.app.media.ScanEvent.Completed] — identical meaning, for the audio
     *  collection's own watermark. */
    data class Completed(
        val total: Int,
        val plan: ScanPlan = ScanPlan.Full,
        val newestModifiedSeconds: Long? = null,
    ) : AudioScanEvent

    data class Failed(val error: Throwable) : AudioScanEvent
}

/** [com.fotoxplorr.app.media.MediaScanner]'s exact contract, over [AudioAsset]. Reuses
 *  [ScanPlan] directly — it is already asset-agnostic pure decision logic with no
 *  [com.fotoxplorr.app.media.MediaAsset] dependency at all. */
interface AudioScanner {
    fun scan(plan: ScanPlan = ScanPlan.Full): Flow<AudioScanEvent>
}

/** [com.fotoxplorr.app.media.MediaRepository]'s exact contract, over [AudioAsset]. */
interface AudioRepository {
    fun observeAll(): Flow<List<AudioAsset>>
    suspend fun replaceAll(items: List<AudioAsset>)
    suspend fun upsert(items: List<AudioAsset>)
    suspend fun remove(ids: Set<MediaId>)
    suspend fun count(): Int
}
