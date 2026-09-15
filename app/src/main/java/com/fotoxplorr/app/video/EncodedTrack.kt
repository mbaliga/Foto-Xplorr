package com.fotoxplorr.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * One already-compressed sample, held in memory rather than written straight to a
 * [MediaMuxer] — see [EncodedTrack]'s own doc for why.
 */
internal data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

/**
 * A whole track's worth of compressed samples plus the [MediaFormat] a [MediaMuxer] needs to add
 * that track — the currency both [VideoTranscoder] (H.264, via the GL bridge) and
 * [com.fotoxplorr.app.audio.AudioTranscoder] (AAC, via buffers) produce, and a plain stream-copy
 * of an already-AAC track produces too.
 *
 * ## Why buffered in memory rather than muxed as each sample is produced
 * [MediaMuxer.start] cannot be called until EVERY track has been added, and a track can only be
 * added once its final [MediaFormat] is known — which for an encoder means waiting for
 * `INFO_OUTPUT_FORMAT_CHANGED`, an event that arrives only after encoding has begun. With two
 * tracks (video and audio) encoding independently, muxing samples as they come out would mean
 * either running both encoders in lockstep on separate threads just to keep both "waiting to
 * start" at the same time, or samples from the track that finished priming first backing up
 * somewhere while the other catches up — which is a queue by another name.
 *
 * This buffers each track's ENTIRE compressed output instead, mirroring
 * [com.fotoxplorr.app.moments.ClipExporter]'s own established precedent of copying "the whole
 * video track and then the whole audio track" rather than interleaving them: MP4 is an indexed
 * container, so writing one track fully before the next is exactly as valid a file as
 * interleaving them, and only interleaving would matter for progressive-download streaming, which
 * a file this app just wrote and is about to read locally is not (see that class's own doc for
 * the argument in full).
 *
 * The cost is real and worth naming rather than hiding: this holds a whole clip's compressed
 * bytes in memory at once, which is why [VideoTranscoder] caps how long a source it will accept —
 * see `MAX_TRANSCODE_DURATION_MS`. A streaming mux (start as soon as both formats are known, write
 * incrementally after that) is the natural next step if that cap ever proves too tight in
 * practice; it is not built now because it needs the two encoders to genuinely run concurrently,
 * which this first pass does not attempt.
 */
internal data class EncodedTrack(
    val format: MediaFormat,
    val samples: List<EncodedSample>,
)

/**
 * Writes every sample of [track] under [muxerTrackIndex] — the index [MediaMuxer.addTrack]
 * returned for it. Deliberately separate from adding the track: every track this muxer will ever
 * carry must be added, and the muxer started, BEFORE any sample can be written at all (see
 * [EncodedTrack]'s own doc), so a caller with more than one track calls [MediaMuxer.addTrack] for
 * each first, then [MediaMuxer.start] once, then this function per track.
 */
internal fun MediaMuxer.writeSamples(muxerTrackIndex: Int, track: EncodedTrack) {
    val bufferInfo = MediaCodec.BufferInfo()
    for (sample in track.samples) {
        val buffer = java.nio.ByteBuffer.wrap(sample.data)
        bufferInfo.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
        writeSampleData(muxerTrackIndex, buffer, bufferInfo)
    }
}
