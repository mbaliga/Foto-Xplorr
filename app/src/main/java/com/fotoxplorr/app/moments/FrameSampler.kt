package com.fotoxplorr.app.moments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Turns a video into the handful of [FrameSignature]s [KeyMomentDetector] scores.
 *
 * The only Android-touching class in this feature's detection path -- [KeyMomentDetector] is
 * pure so it can be checked on the JVM, and this class exists precisely to keep it that way: it
 * does the messy, platform-specific, occasionally-fails-for-no-clear-reason work of pulling
 * pixels out of a compressed video, and hands back a small, boring list of numbers.
 *
 * ## Why forty frames, and why tiny ones
 * [MAX_FRAMES] bounds the sample count regardless of how long the video is: decoding hundreds of
 * frames -- even small ones -- would take seconds per video on a phone CPU, for a pass meant to
 * run quietly in the background across a whole library. [SAMPLE_EDGE_PX] bounds the other side of
 * that cost: [KeyMomentDetector] only ever looks at a 64-bin luma histogram, so a 64x64 sample is
 * already more resolution than the analysis can use, and decoding a full-size frame forty times
 * over would be paying for detail that gets thrown away one line later.
 *
 * ## Why `OPTION_CLOSEST_SYNC`, and what it gives up
 * `OPTION_CLOSEST_SYNC` returns the nearest keyframe, decoded on its own. `OPTION_CLOSEST`
 * returns the frame nearest the requested instant -- and to do that it must seek to the previous
 * keyframe and decode every frame in between at FULL resolution, however small the bitmap asked
 * for, because the decoder cannot produce a P-frame without the frames it depends on. On a 4K
 * recording with a two-second keyframe interval that is thirty to sixty full-resolution software
 * decodes per sample, forty samples per video, running the first time each video is opened --
 * while it plays. Minutes of a saturated CPU for a 64x64 histogram.
 *
 * The cost of the cheap option is precision: on a video whose keyframes are sparser than the
 * sample spacing, consecutive requested positions can round to the SAME keyframe, and a change
 * that happens entirely between two keyframes is invisible to this pass. Two things make that an
 * acceptable trade. Phone recordings keyframe every one to two seconds, close to the sample
 * spacing, so the blind stretch is short; and identical consecutive keyframes produce identical
 * signatures, which the detector already treats as "nothing happened" rather than as a moment.
 * A reported moment lands within a keyframe interval of the real cut, which is inside the
 * detector's own 3s minimum spacing between moments anyway.
 *
 * ## Why the duration comes back with the signatures
 * The positions are computed against the duration the RETRIEVER reports, with the MediaStore
 * value only as a fallback. The detector needs that same number: handed the MediaStore duration
 * instead, a video whose row carries 0 (metadata that failed to extract at scan time, a file
 * copied in over MTP and scanned before it was parseable) sampled forty frames perfectly well and
 * then had every one of them discarded by the detector's `durationMs <= 0` guard -- and was marked
 * scanned, so it was never looked at again. Returning both together is what keeps the two from
 * disagreeing.
 *
 * ## Why an unreadable video comes back as an empty list, never a thrown exception
 * A corrupt file, an unsupported codec, or a `MediaStore` row whose file vanished out from under
 * it all fail inside [MediaMetadataRetriever] the same way an ordinary, readable video with no
 * scene changes ends this class's work: with nothing to report. `VideoMomentIndexer` relies on
 * that -- see its class doc -- to treat "could not read it" and "read it, found nothing"
 * identically, which is what lets a video this app can never open be marked scanned once and left
 * alone, instead of being retried forever.
 */
/**
 * What one pass over a video produced: its signatures and the duration they were positioned
 * against. See [FrameSampler]'s class doc on why the two travel together.
 */
data class SampledVideo(val signatures: List<FrameSignature>, val durationMs: Long) {
    companion object {
        val EMPTY = SampledVideo(emptyList(), 0L)
    }
}

class FrameSampler(context: Context) {
    private val appContext = context.applicationContext

    suspend fun sample(asset: MediaAsset, maxFrames: Int = MAX_FRAMES): SampledVideo =
        withContext(Dispatchers.IO) {
            if (!asset.isVideo) return@withContext SampledVideo.EMPTY

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, asset.contentUri)
                val durationMs = readDurationMs(retriever) ?: asset.durationMillis
                if (durationMs <= 0L) return@withContext SampledVideo.EMPTY

                val frameCount = frameCountFor(durationMs, maxFrames)
                val signatures = ArrayList<FrameSignature>(frameCount)
                for (index in 0 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    // Never the literal final instant: some decoders refuse to return a frame
                    // exactly at end-of-stream, and `i` only reaches `frameCount - 1` here, so
                    // the last requested position always sits strictly before `durationMs`.
                    val positionMs = (index.toLong() * durationMs) / frameCount
                    val bitmap = decodeFrame(retriever, positionMs)
                    if (bitmap != null) {
                        try {
                            signatures += signatureOf(positionMs, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    // A single bad frame (a corrupt GOP a few seconds in, a transient decoder
                    // hiccup) is swallowed by decodeFrame and just skips this one position -- see
                    // its doc. The rest of an otherwise-readable video should not be thrown away
                    // for one unreadable instant in the middle of it.
                }
                SampledVideo(signatures, durationMs)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // setDataSource threw, or something else here failed in a way no per-frame guard
                // caught. See the class doc: total unreadability and "read fine, nothing to
                // report" are deliberately the same outcome from this function's point of view.
                SampledVideo.EMPTY
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun readDurationMs(retriever: MediaMetadataRetriever): Long? =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

    /**
     * One low-resolution frame at [positionMs], or null if this particular frame could not be
     * decoded. Failures here are swallowed on purpose and only cost this one position -- see the
     * call site in [sample].
     */
    private fun decodeFrame(retriever: MediaMetadataRetriever, positionMs: Long): Bitmap? {
        val timeUs = positionMs * 1_000L
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    SAMPLE_EDGE_PX,
                    SAMPLE_EDGE_PX,
                )
            } else {
                // minSdk is 26; getScaledFrameAtTime does not exist below API 27. One tier of
                // devices pays, once per sampled frame, exactly the full-resolution decode cost
                // getScaledFrameAtTime exists to avoid -- still bounded overall, because
                // frameCountFor caps how many times this branch can run per video.
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val scaled = Bitmap.createScaledBitmap(full, SAMPLE_EDGE_PX, SAMPLE_EDGE_PX, false)
                    if (scaled !== full) full.recycle()
                    scaled
                }
            }
        }.getOrNull()?.ensureArgb8888()
    }

    /**
     * Some decode paths on some devices can hand back a `HARDWARE`-config bitmap, and
     * `getPixels` (used below, in [signatureOf]) throws `IllegalStateException` on one of those
     * -- see `com.fotoxplorr.app.recognition.RecognitionIndexer`'s identical guard on ITS bitmap
     * source, which is the same trap for a different Android API. Converting once, here, is
     * cheaper than that crash and simpler than teaching every caller of a decoded frame to check.
     */
    private fun Bitmap.ensureArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false).also {
            if (it !== this) recycle()
        }

    private fun signatureOf(positionMs: Long, bitmap: Bitmap): FrameSignature {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val raw = IntArray(FrameSignature.HISTOGRAM_BINS)
        var lumaSum = 0.0
        for (pixel in pixels) {
            // Rec.709 luma, matching AutoFix.analyse and RecognitionIndexer.grayscalePatch: one
            // definition of "brightness" used everywhere in this app, so this feature's
            // brightness-shift moments and the editor's exposure analysis never quietly disagree
            // about what a pixel's luma is.
            val luma = 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel)
            lumaSum += luma
            // /256, not /255: keeps a pixel at the maximum luma (255) strictly inside the last
            // bin instead of landing one past it. coerceIn below is a second, cheap backstop.
            val bin = ((luma / 256f) * FrameSignature.HISTOGRAM_BINS).toInt()
                .coerceIn(0, FrameSignature.HISTOGRAM_BINS - 1)
            raw[bin]++
        }

        val total = pixels.size.coerceAtLeast(1)
        // Scaled onto a fixed total rather than left as raw per-pixel counts, so two signatures
        // are comparable at a glance regardless of how many pixels either was built from -- see
        // FrameSignature's KDoc for why KeyMomentDetector does not actually depend on this being
        // exact.
        val normalised = IntArray(FrameSignature.HISTOGRAM_BINS) { i ->
            (raw[i].toLong() * HISTOGRAM_SCALE / total).toInt()
        }
        return FrameSignature(
            positionMs = positionMs,
            histogram = normalised,
            meanLuma = (lumaSum / total / 255.0).toFloat(),
        )
    }

    /**
     * How many frames to sample: never more than [maxFrames] regardless of video length (see the
     * class doc), and never denser than one every [MIN_SAMPLE_SPACING_MS] -- a five-second clip
     * should not burn its whole budget re-sampling frames a fraction of a second apart that could
     * not possibly disagree about which "scene" they belong to.
     */
    private fun frameCountFor(durationMs: Long, maxFrames: Int): Int =
        (durationMs / MIN_SAMPLE_SPACING_MS).toInt().coerceIn(1, maxFrames.coerceAtLeast(1))

    private companion object {
        const val MAX_FRAMES = 40
        const val MIN_SAMPLE_SPACING_MS = 500L
        const val SAMPLE_EDGE_PX = 64
        const val HISTOGRAM_SCALE = 10_000
    }
}
