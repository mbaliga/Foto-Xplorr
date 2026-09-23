package com.fotoxplorr.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * A whole track's worth of compressed samples, spilled to a temp file under
 * `cacheDir/transcode-spill/` rather than held in memory (P0-11) — see
 * `docs/adr/ADR-008-video-transcode-pipeline.md`'s "Spill instead of memory" addendum for why, and
 * for what changed from this class's original all-in-memory design.
 *
 * Only four primitive values per sample — byte offset, size, presentation time, flags — live in
 * memory, in four parallel, growable primitive arrays (the same doubling-`ArrayList` growth shape
 * a real `ArrayList<T>` uses, just without the boxing four separate object arrays would cost). The
 * sample bytes themselves are written to the spill file exactly once, as [append] is called, and
 * read back only by [forEachSample], through ONE reusable direct [ByteBuffer] sized to the largest
 * sample ever appended — never one allocation per sample, and never the whole track's bytes held
 * twice (once on disk, once in memory) at any point.
 *
 * [close] deletes the spill file. A caller must call it exactly once a track is no longer needed,
 * on every path including failure or cancellation — see [com.fotoxplorr.app.video.VideoTranscoder]'s
 * own `finally`/`catch` blocks for the shape this requires.
 */
internal class SampleStore private constructor(
    private val spillFile: File,
    private val output: FileOutputStream,
) : AutoCloseable {
    private var offsets = LongArray(INITIAL_CAPACITY)
    private var sizes = IntArray(INITIAL_CAPACITY)
    private var presentationTimesUs = LongArray(INITIAL_CAPACITY)
    private var sampleFlags = IntArray(INITIAL_CAPACITY)
    private var count = 0
    private var writtenBytes = 0L

    var maxSampleSize = 0
        private set
    val sampleCount: Int get() = count

    fun append(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        ensureCapacity()
        output.write(data)
        offsets[count] = writtenBytes
        sizes[count] = data.size
        presentationTimesUs[count] = presentationTimeUs
        sampleFlags[count] = flags
        writtenBytes += data.size
        if (data.size > maxSampleSize) maxSampleSize = data.size
        count++
    }

    private fun ensureCapacity() {
        if (count < offsets.size) return
        val newCapacity = offsets.size * 2
        offsets = offsets.copyOf(newCapacity)
        sizes = sizes.copyOf(newCapacity)
        presentationTimesUs = presentationTimesUs.copyOf(newCapacity)
        sampleFlags = sampleFlags.copyOf(newCapacity)
    }

    /**
     * Reads every appended sample back, in append order, into one reusable direct [ByteBuffer]
     * sized to [maxSampleSize], and invokes [action] once per sample with that buffer (rewound,
     * limited to exactly that sample's own size), its presentation time and its flags. [action]
     * must not retain the buffer past its own call — the next iteration overwrites it in place.
     *
     * A no-op when nothing was ever [append]ed, so an empty (e.g. silent-source, audio-track-less)
     * track never allocates a zero-sized buffer or opens the spill file at all.
     */
    fun forEachSample(action: (buffer: ByteBuffer, presentationTimeUs: Long, flags: Int) -> Unit) {
        if (count == 0) return
        output.flush()
        val buffer = ByteBuffer.allocateDirect(maxSampleSize)
        RandomAccessFile(spillFile, "r").use { randomAccessFile ->
            val channel = randomAccessFile.channel
            for (index in 0 until count) {
                buffer.clear()
                buffer.limit(sizes[index])
                channel.position(offsets[index])
                while (buffer.hasRemaining()) {
                    check(channel.read(buffer) >= 0) { "Unexpected end of transcode spill file" }
                }
                buffer.flip()
                action(buffer, presentationTimesUs[index], sampleFlags[index])
            }
        }
    }

    override fun close() {
        runCatching { output.close() }
        spillFile.delete()
    }

    companion object {
        private const val INITIAL_CAPACITY = 256
        private const val SPILL_DIRECTORY_NAME = "transcode-spill"
        private const val SPILL_FILE_PREFIX = "sample-"
        private const val SPILL_FILE_SUFFIX = ".spill"

        /** [cacheDir] is this app's own `Context.cacheDir` — the spill directory is created (if
         *  absent) under it, matching the same `cacheDir/<feature>-staging/` idiom
         *  [com.fotoxplorr.app.editor.EditedCopyWriter]/[com.fotoxplorr.app.metadata.MetadataWriter]
         *  already use for their own temp-then-verify writes. */
        fun create(cacheDir: File): SampleStore {
            val spillDir = File(cacheDir, SPILL_DIRECTORY_NAME).apply { mkdirs() }
            val spillFile = File.createTempFile(SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX, spillDir)
            return SampleStore(spillFile, FileOutputStream(spillFile))
        }
    }
}

/**
 * A [SampleStore] plus the [MediaFormat] a [MediaMuxer] needs to add that track — the currency
 * both [VideoTranscoder] (H.264, via the GL bridge) and [com.fotoxplorr.app.audio.AudioTranscoder]
 * (AAC, via buffers) produce, and a plain stream-copy of an already-AAC track produces too.
 *
 * Tracks are not muxed as each sample is produced, even though nothing is held in memory any more
 * (P0-11): [MediaMuxer.start] cannot be called until EVERY track has been added, and a track can
 * only be added once its final [MediaFormat] is known — an event that, for an encoder, arrives only
 * after encoding has begun (`INFO_OUTPUT_FORMAT_CHANGED`). With two tracks (video and audio)
 * encoding independently, muxing samples as they come out would mean either running both encoders
 * genuinely concurrently just to keep both "waiting to start" in step, or samples from whichever
 * track finishes priming first backing up somewhere while the other catches up — a queue by another
 * name. This spills each track's samples to its own file instead, mirroring
 * [com.fotoxplorr.app.moments.ClipExporter]'s own established precedent of writing "the whole video
 * track and then the whole audio track" rather than interleaving them: MP4 is an indexed container,
 * so one track fully written before the next is exactly as valid a file as interleaving, and only
 * interleaving would matter for progressive-download streaming, which a file this app just wrote
 * and is about to read locally is not.
 *
 * [close] delegates to [samples]' own close — see [SampleStore]'s own doc for why a track's spill
 * file must be deleted exactly once, on every path.
 */
internal class EncodedTrack(
    val format: MediaFormat,
    val samples: SampleStore,
) : AutoCloseable {
    override fun close() = samples.close()
}

/**
 * Writes every sample of [track] under [muxerTrackIndex] — the index [MediaMuxer.addTrack]
 * returned for it. Deliberately separate from adding the track: every track this muxer will ever
 * carry must be added, and the muxer started, BEFORE any sample can be written at all (see
 * [EncodedTrack]'s own doc), so a caller with more than one track calls [MediaMuxer.addTrack] for
 * each first, then [MediaMuxer.start] once, then this function per track.
 */
internal fun MediaMuxer.writeSamples(muxerTrackIndex: Int, track: EncodedTrack) {
    val bufferInfo = MediaCodec.BufferInfo()
    track.samples.forEachSample { buffer, presentationTimeUs, flags ->
        bufferInfo.set(0, buffer.remaining(), presentationTimeUs, flags)
        writeSampleData(muxerTrackIndex, buffer, bufferInfo)
    }
}
