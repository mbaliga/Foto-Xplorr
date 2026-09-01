package com.fotoxplorr.app.moments

import android.content.Context
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Runs key-moment detection for one video, end to end, and persists the result.
 *
 * Three phases -- sample, detect, persist -- and each phase is a class that only does its own
 * job: [FrameSampler] touches Android and never decides what a moment is; [KeyMomentDetector]
 * decides what a moment is and never touches Android; this class is the thin seam between them
 * and [VideoMomentStore]. That shape deliberately mirrors
 * `com.fotoxplorr.app.recognition.RecognitionIndexer` -- same batching/cancellation idiom, same
 * reason for it: the expensive part must be interruptible, and the pure decision-making part
 * must be testable without a device.
 *
 * ## Why a corrupt or unreadable video still ends up marked scanned
 * [FrameSampler.sample] never throws; an unreadable file comes back as an empty signature list
 * (see its class doc), which [KeyMomentDetector.detect] turns into an empty moment list, which
 * this class writes and marks scanned exactly as it would for a real video that genuinely has no
 * cuts in it. That is deliberate, not an oversight: [VideoMomentStore]'s scanned table exists
 * precisely so "found nothing" and "could not look" are not distinguished at the storage layer --
 * without it, a video this app can never read would be re-attempted, and fail, forever.
 *
 * ## Why [VideoMomentStore.replaceAuto] is called before [VideoMomentStore.markScanned], not after
 * If the process dies between the two calls, the video is left with moments written but not yet
 * marked scanned, so it is simply re-scanned next time -- wasted work, but no data lost. The
 * other order would risk the opposite: a video permanently marked "done" with no moments ever
 * actually recorded for it, which nothing would ever retry.
 */
class VideoMomentIndexer(
    context: Context,
    private val store: VideoMomentStore,
    private val sampler: FrameSampler = FrameSampler(context),
) {
    /**
     * @return on success, how many AUTO moments were found. Zero is a normal, common answer --
     *   see [KeyMomentDetector]'s class doc on why "found nothing" must be a real, representable
     *   outcome. An already-scanned video is skipped and also answers 0, so a caller cannot tell
     *   "nothing found this time" apart from "already done" from the number alone; neither is
     *   new information worth surfacing to a person, so nothing here needs to distinguish them.
     */
    suspend fun index(asset: MediaAsset): Result<Int> = withContext(Dispatchers.Default) {
        runCatching {
            require(asset.isVideo) { "${asset.displayName} is not a video" }
            if (store.hasBeenScanned(asset.id)) return@runCatching 0

            currentCoroutineContext().ensureActive()
            val signatures = sampler.sample(asset)

            currentCoroutineContext().ensureActive()
            val detected = KeyMomentDetector.detect(signatures, asset.durationMillis)

            currentCoroutineContext().ensureActive()
            val moments = detected.map { moment ->
                VideoMoment(
                    mediaId = asset.id,
                    positionMs = moment.positionMs,
                    source = MomentSource.AUTO,
                    confidence = moment.confidence,
                    label = moment.label,
                )
            }
            store.replaceAuto(asset.id, moments)
            store.markScanned(asset.id)
            detected.size
        }.onFailure { error ->
            // Mirrors RecognitionIndexer's own handling exactly: cancellation must propagate as
            // a cancellation, not collapse into an ordinary Result.failure that a caller might
            // treat as "this video failed to index" and retry or report as an error.
            if (error is CancellationException) throw error
        }
    }
}
