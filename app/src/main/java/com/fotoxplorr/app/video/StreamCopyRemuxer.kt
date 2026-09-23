package com.fotoxplorr.app.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * The result of [remux]: either the [Container] it wrote, or a reason it could not.
 *
 * [Unsupported] deliberately carries no exception -- a codec [containerFor] cannot place, or no
 * video track to copy at all, are ordinary, expected outcomes for *some* source video, not bugs.
 * A genuine [MediaMuxer]/[MediaExtractor] failure (a corrupt file, an I/O error) is left to throw,
 * same as [remux]'s own callers already handle for every other failure in this app.
 */
sealed interface RemuxResult {
    data class Remuxed(val container: Container) : RemuxResult
    data class Unsupported(val reason: String) : RemuxResult
}

/**
 * Stream-copies [source]'s first video and first audio track (see [selectPrimaryTracks]) into
 * [target], optionally trimmed to [rangeUs] -- no decode, no re-encode, no quality loss. Pulled
 * out of `moments/ClipExporter.kt` (P0-05), which now calls this with its own clip range: a video
 * *share*, which needs the identical no-location, no-quality-loss copy but over the WHOLE video
 * rather than a clip of it, would otherwise mean a second, independently-maintained copy of this
 * exact keyframe-seek/track-selection/buffer-sizing logic.
 *
 * ## Why this is a stream copy, not a re-encode
 * Re-encoding would mean standing up a decoder, an encoder and a colour/format-matching pipeline
 * for whatever codec the source happens to use -- minutes of work on a phone CPU, plus a second
 * generation of lossy compression stacked on the first. [MediaExtractor] and [MediaMuxer] instead
 * move the already-encoded samples straight from the source container into a new one, untouched.
 *
 * ## The keyframe/GOP constraint [rangeUs] relies on -- read this before touching a seek call
 * A compressed video is not a sequence of independent pictures. Most frames (P/B-frames) are
 * stored as a DELTA against earlier frames and cannot be decoded on their own; only sync frames
 * (keyframes) stand alone. Starting the copy from the first sample AT OR AFTER `rangeUs.first`
 * could leave the opening frames as deltas against material that got trimmed away -- a player
 * would show a frozen or corrupted picture until the next keyframe, sometimes seconds later. The
 * fix is [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] (see [findClipStart]): seek to the sync sample at
 * or BEFORE the requested time, never after. The consequence, not a defect, is that the output may
 * begin up to one GOP before `rangeUs.first` -- there is no way around this without re-encoding
 * the leading partial GOP down to exactly that instant, which is precisely the cost this function
 * exists to avoid paying.
 *
 * ## Why tracks are copied one at a time, not interleaved by timestamp
 * MP4/WebM are indexed containers: [MediaMuxer] records each sample's byte offset and timestamp in
 * the file's own index, and a player seeks through that rather than reading start to finish. The
 * format only requires samples WITHIN one track to be written in non-decreasing timestamp order;
 * physically interleaving two tracks on disk only matters for progressive-download streaming,
 * which a file written to this app's own cache and then opened locally is not. Copying the whole
 * video track and then the whole audio track removes an entire class of bookkeeping bug --
 * tracking which of two tracks is "ahead" while unselecting one mid-loop.
 *
 * @param rangeUs `[startUs, endUs]` to trim to. Null copies the whole stream from its actual
 *   start, untouched -- no seek, no timestamp shift.
 * @param rotationDegrees written via [MediaMuxer.setOrientationHint] -- the container's own
 *   rotation, not baked into any pixel. Never calls [MediaMuxer.setLocation]: the entire point of
 *   the P0-05 caller that shares a video through this.
 */
suspend fun remux(
    context: Context,
    source: Uri,
    target: File,
    rangeUs: LongRange?,
    rotationDegrees: Int,
): RemuxResult = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, source, null)
        val trackMimeTypes = (0 until extractor.trackCount).map {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
        }
        val selection = selectPrimaryTracks(trackMimeTypes)
            ?: return@withContext RemuxResult.Unsupported("No video track to copy")
        val videoMime = trackMimeTypes[selection.videoTrackIndex]
        val audioMime = selection.audioTrackIndex?.let { trackMimeTypes[it] }
        val container = containerFor(videoMime, audioMime)
            ?: return@withContext RemuxResult.Unsupported("$videoMime is not a codec this app can share directly")

        val muxer = MediaMuxer(target.absolutePath, container.muxerOutputFormat)
        try {
            val trackIndices = listOfNotNull(selection.videoTrackIndex, selection.audioTrackIndex)
            val tracks = trackIndices.map { index -> TrackPlan(index, muxer.addTrack(extractor.getTrackFormat(index))) }

            // Must be set before start(); MediaMuxer ignores it after.
            muxer.setOrientationHint(rotationDegrees)
            muxer.start()

            val baseUs = if (rangeUs != null) findClipStart(extractor, tracks, rangeUs.first) else 0L
            val endUs = rangeUs?.last ?: Long.MAX_VALUE

            val buffer = ByteBuffer.allocateDirect(copyBufferBytesFor(extractor, tracks))
            val bufferInfo = MediaCodec.BufferInfo()
            for (track in tracks) {
                currentCoroutineContext().ensureActive()
                extractor.selectOnly(track.sourceIndex, tracks)
                if (rangeUs != null) extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                copyTrack(extractor, muxer, track.muxerIndex, endUs, baseUs, buffer, bufferInfo)
            }

            // Deliberately outside the finally block below: a stop() failure means the file muxer
            // just wrote is not valid, and that has to surface as this call's own failure, not be
            // silently swallowed the way a cleanup step should be.
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        RemuxResult.Remuxed(container)
    } finally {
        runCatching { extractor.release() }
    }
}

/**
 * Seeks every track to its `SEEK_TO_PREVIOUS_SYNC` sample at [startUs] and returns the EARLIEST
 * resulting sample time across all of them -- see `ClipExporter`'s own class doc for why every
 * track shifts against this one shared origin rather than each zeroing against its own start.
 */
internal fun findClipStart(extractor: MediaExtractor, tracks: List<TrackPlan>, startUs: Long): Long {
    var baseUs = Long.MAX_VALUE
    for (track in tracks) {
        extractor.selectOnly(track.sourceIndex, tracks)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val firstSampleUs = extractor.sampleTime
        if (firstSampleUs in 0 until baseUs) baseUs = firstSampleUs
    }
    // Nothing decodable in range on any track: fall back to the requested start so the copy loop
    // runs (and immediately finds no samples, and writes none) instead of this function returning
    // a sentinel that would corrupt every timestamp downstream.
    return if (baseUs == Long.MAX_VALUE) startUs else baseUs
}

internal suspend fun copyTrack(
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
        bufferInfo.set(0, size, (sampleTimeUs - baseUs).coerceAtLeast(0L), muxerBufferFlagsFor(extractor.sampleFlags))
        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
        extractor.advance()
    }
}

/** Selects exactly [index] among [tracks], unselecting every other track this copy cares about --
 * see `ClipExporter`'s own note on why a stale selection from a previous pass is exactly how two
 * tracks' samples would end up interleaved. */
internal fun MediaExtractor.selectOnly(index: Int, tracks: List<TrackPlan>) {
    tracks.forEach { track ->
        if (track.sourceIndex == index) selectTrack(index) else runCatching { unselectTrack(track.sourceIndex) }
    }
}

/**
 * How big the sample-copy buffer has to be: the largest [MediaFormat.KEY_MAX_INPUT_SIZE] any
 * copied track declares, floored and capped -- see `ClipExporter`'s own note on why this is asked
 * rather than assumed (a fixed buffer sized for a typical frame fails on the first 4K keyframe).
 */
internal fun copyBufferBytesFor(extractor: MediaExtractor, tracks: List<TrackPlan>): Int {
    val declared = tracks.maxOfOrNull { track ->
        val format = extractor.getTrackFormat(track.sourceIndex)
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else {
            0
        }
    } ?: 0
    return declared.coerceIn(MIN_COPY_BUFFER_BYTES, MAX_COPY_BUFFER_BYTES)
}

internal data class TrackPlan(val sourceIndex: Int, val muxerIndex: Int)

/** Floor for the copy buffer, for a track that declares no maximum input size. */
private const val MIN_COPY_BUFFER_BYTES = 1 shl 20 // 1 MiB

/** Ceiling, so a corrupt declared size cannot turn into a huge direct allocation. */
private const val MAX_COPY_BUFFER_BYTES = 32 shl 20 // 32 MiB
