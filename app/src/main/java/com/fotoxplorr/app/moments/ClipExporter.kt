package com.fotoxplorr.app.moments

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Cuts `[startMs, endMs)` out of a video into its own playable file, WITHOUT re-encoding.
 *
 * ## Why this is a stream copy, not a re-encode
 * Re-encoding a clip would mean standing up a decoder, an encoder and a colour/format-matching
 * pipeline for whatever codec the source video happens to use -- minutes of work on a phone CPU
 * for what might be a five-second clip, plus a second generation of lossy compression stacked on
 * the first. [MediaExtractor] and [MediaMuxer] instead move the already-encoded samples straight
 * from the source container into a new one, untouched: no decode, no re-encode, no quality loss,
 * and the whole operation finishes in roughly the time it takes to read and write the bytes.
 *
 * ## The keyframe/GOP constraint -- read this before touching the seek calls below
 * A compressed video is not a sequence of independent pictures. Most frames (P/B-frames) are
 * stored as a DELTA against earlier frames and cannot be decoded on their own; only sync frames
 * (keyframes, I-frames) stand alone. If this simply started copying from the first sample AT OR
 * AFTER [exportClip]'s `startMs`, the clip's opening frames could be deltas against source
 * material that got trimmed away -- a player would show a frozen or corrupted picture, or
 * nothing at all, until the next keyframe arrives, sometimes seconds later. This is THE classic
 * bug in every naive "trim a video" implementation, and because it plays back wrong rather than
 * failing to export, it looks like a bug in the player, not in the trimmer -- which is exactly
 * what makes it so easy to ship.
 *
 * The fix is [MediaExtractor.SEEK_TO_PREVIOUS_SYNC]: seek the video track to the sync sample at
 * or BEFORE the requested time, never after (see [findClipStart]). The consequence -- and it is
 * a consequence of doing this correctly, not a defect -- is that **the exported clip may begin
 * up to one GOP's worth of time before the requested `startMs`**. A typical phone-recorded video
 * keyframes every one to a few seconds, so the clip can start perceptibly earlier than asked
 * for. There is no way around this without re-encoding the leading partial GOP down to exactly
 * `startMs`, which is precisely the cost this class exists to avoid paying.
 *
 * ## Why tracks are copied one at a time, not interleaved by timestamp
 * MP4 is an indexed container: [MediaMuxer] records each sample's byte offset and timestamp in
 * the file's `moov` metadata, and a player seeks through that index rather than reading the
 * sample data start to finish. What the format actually requires is that samples WITHIN one
 * track are written in non-decreasing timestamp order; physically interleaving two tracks on
 * disk only matters for progressive-download streaming, which a clip written to this app's own
 * cache and then opened from a local file is not. Copying the whole video track and then the
 * whole audio track removes an entire class of bookkeeping bug -- tracking which of two tracks
 * is "ahead" while unselecting one mid-loop -- for a feature whose output is at most a few
 * minutes long and is never read while it is still being written.
 */
class ClipExporter(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.files"

    suspend fun exportClip(asset: MediaAsset, startMs: Long, endMs: Long): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(asset.isVideo) { "${asset.displayName} is not a video" }
                require(endMs > startMs) { "Clip end ($endMs) must be after its start ($startMs)" }
                val start = startMs.coerceAtLeast(0L)
                val end = endMs.coerceAtLeast(start + 1)

                val directory = MomentExportStorage.prepare(appContext)
                val target = File(directory, "${UUID.randomUUID()}.mp4")
                try {
                    mux(asset, start, end, target)
                    FileProvider.getUriForFile(appContext, authority, target)
                } catch (error: Throwable) {
                    // A half-written or invalid clip left in the export cache is worse than
                    // nothing: it would sit there as a file some later share picks up.
                    target.delete()
                    throw error
                }
            }
        }

    private suspend fun mux(asset: MediaAsset, startMs: Long, endMs: Long, target: File) {
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L

        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(appContext, asset.contentUri, null)

            val tracks = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> TrackPlan(index, muxer.addTrack(format))
                    // "Copy the audio track too when present" -- present is the operative word:
                    // a silent source video simply produces zero audio TrackPlans, and the loop
                    // below never notices the difference.
                    mime.startsWith("audio/") -> TrackPlan(index, muxer.addTrack(format))
                    else -> null // a subtitle/metadata track: not part of "a clip of the video"
                }
            }
            check(tracks.isNotEmpty()) { "${asset.displayName} has no video or audio track to copy" }

            // Rotation lives in the container's `tkhd` display matrix, not in any one track's
            // MediaFormat as read back from MediaExtractor, so it has to be fetched separately --
            // see findRotationDegrees. Must be set before start(); MediaMuxer ignores it after.
            muxer.setOrientationHint(findRotationDegrees(asset))
            muxer.start()

            // Whichever track's SEEK_TO_PREVIOUS_SYNC sample lands earliest becomes time zero for
            // EVERY track, not just its own -- see findClipStart's doc for why shifting each
            // track to zero independently would desync audio from picture.
            val baseUs = findClipStart(extractor, tracks, startUs)

            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            for (track in tracks) {
                currentCoroutineContext().ensureActive()
                extractor.selectOnly(track.sourceIndex, tracks)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                copyTrack(extractor, muxer, track.muxerIndex, endUs, baseUs, buffer, bufferInfo)
            }

            // Deliberately NOT inside the finally block below: a stop() failure means the file
            // muxer just wrote is not a valid clip (e.g. a track that ended up with zero samples
            // because [startMs, endMs) fell entirely outside the video), and that has to surface
            // as this export's failure, not be silently swallowed the way a cleanup step should
            // be.
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Seeks every track to its `SEEK_TO_PREVIOUS_SYNC` sample at [startUs] and returns the
     * EARLIEST resulting sample time across all of them.
     *
     * That earliest time becomes the single origin every track's timestamps are shifted against
     * in [copyTrack]. The video track's sync sample is very often earlier than [startUs] (see the
     * class doc); the audio track's is normally within a sample or two of it, since virtually
     * every AAC sample is its own sync point. Subtracting the SAME base from both keeps whatever
     * relative offset they started with -- which is what audio/video sync actually is -- instead
     * of zeroing each track against its own start time and throwing that offset away.
     */
    private fun findClipStart(extractor: MediaExtractor, tracks: List<TrackPlan>, startUs: Long): Long {
        var baseUs = Long.MAX_VALUE
        for (track in tracks) {
            extractor.selectOnly(track.sourceIndex, tracks)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstSampleUs = extractor.sampleTime
            if (firstSampleUs in 0 until baseUs) baseUs = firstSampleUs
        }
        // Nothing decodable in range on any track: fall back to the requested start so the copy
        // loop below runs (and immediately finds no samples, and writes none) instead of this
        // function returning a sentinel that would corrupt every timestamp downstream.
        return if (baseUs == Long.MAX_VALUE) startUs else baseUs
    }

    private suspend fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrackIndex: Int,
        endUs: Long,
        baseUs: Long,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0 || sampleTimeUs > endUs) break // end of stream, or past the clip
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufferInfo.set(0, size, (sampleTimeUs - baseUs).coerceAtLeast(0L), extractor.sampleFlags)
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    /**
     * Reads the container-level rotation via a second, throwaway [MediaMetadataRetriever] rather
     * than anything off [MediaExtractor]'s per-track [MediaFormat] -- see the class doc's note on
     * where rotation actually lives. Any failure here (source unreadable, tag absent) just means
     * "assume no rotation": an un-rotated clip is a cosmetic problem, not a reason to fail an
     * otherwise-successful export.
     */
    private fun findRotationDegrees(asset: MediaAsset): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, asset.contentUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (error: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Selects exactly [index] among this extractor's tracks, unselecting every other track this
     * export cares about. [MediaExtractor] has no "select only this track" call, and getting this
     * wrong -- leaving a stale track selected from a previous pass -- is exactly how samples from
     * two tracks would end up interleaved in [MediaExtractor.getSampleTrackIndex] order when
     * [copyTrack] assumes exactly one track is selected.
     */
    private fun MediaExtractor.selectOnly(index: Int, tracks: List<TrackPlan>) {
        tracks.forEach { track ->
            if (track.sourceIndex == index) selectTrack(index) else runCatching { unselectTrack(track.sourceIndex) }
        }
    }

    private data class TrackPlan(val sourceIndex: Int, val muxerIndex: Int)

    private companion object {
        /** Comfortably larger than one compressed video frame; re-used across every sample of
         *  every track rather than re-allocated per sample. */
        const val COPY_BUFFER_BYTES = 1 shl 20 // 1 MiB
    }
}
