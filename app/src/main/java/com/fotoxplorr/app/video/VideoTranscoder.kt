package com.fotoxplorr.app.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import com.fotoxplorr.app.audio.AudioTranscoder
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.video.gl.DecoderOutputSurface
import com.fotoxplorr.app.video.gl.EglCore
import com.fotoxplorr.app.video.gl.InputSurface
import com.fotoxplorr.app.video.gl.TextureRenderer
import com.fotoxplorr.app.videoeditor.VideoEditRecipe
import com.fotoxplorr.app.videoeditor.exportedPresentationTimeUs
import com.fotoxplorr.app.videoeditor.isWithinTrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Re-encodes a video's picture track to H.264/AVC (and its audio track to AAC, copying it
 * byte-for-byte instead when it already is AAC) into a fresh MP4 — Save As for video, the
 * counterpart to [com.fotoxplorr.app.editor.EditedCopyWriter]'s format choice for still photos,
 * and a direct answer to the request this whole roadmap traces back to: "if I open a file I
 * should be able to save it as another format."
 *
 * ## Why H.264/AAC/MP4 specifically, not a chosen target
 * Every API 26+ device Android's own compatibility definition covers is REQUIRED to carry an
 * H.264 encoder and an AAC encoder — nothing else (VP8/VP9/HEVC encode) is guaranteed at all.
 * Offering a format picker the way [com.fotoxplorr.app.editor.OutputFormat] does for photos would
 * mean discovering, per device, which of several targets are even available, plus handling
 * containers (WebM) whose OWN audio requirement (Vorbis/Opus, not AAC) is a second, independent
 * axis of the same problem. This narrows Phase 3 to the one target every device can actually
 * reach — which is also what a "make this play everywhere" conversion is FOR. Additional targets
 * are a real, named follow-up, not an oversight; see [deviceSupportsH264AacEncoding] for the
 * (rare) case a device genuinely lacks even this one.
 *
 * ## Why a GL bridge, not a straight buffer copy, for the pixels
 * A decoder and an encoder each speak buffers or a [android.view.Surface] — never each other
 * directly. There is no OS call that connects a decoder's output surface to an encoder's input
 * surface; rendering each decoded frame as a GL texture onto the encoder's input surface is the
 * bridge every MediaCodec-based transcoder uses for exactly this reason, this pipeline's
 * pass-through shader included — see [TextureRenderer].
 *
 * ## What [VideoEditRecipe] adds, and what still does NOT exist here
 * Trim and speed change ([VideoEditRecipe]) are threaded through the same decode/encode loop —
 * trim by only rendering frames inside the window (feeding the decoder from the previous keyframe
 * for correct GOP state, same as [com.fotoxplorr.app.moments.ClipExporter]'s own seek, but
 * discarding frames before the exact requested start rather than snapping to it), speed by
 * scaling presentation timestamps ([exportedPresentationTimeUs]) and, for audio, actually
 * resampling the decoded PCM ([com.fotoxplorr.app.videoeditor.resamplePcm16]).
 * No pixel processing exists yet — a future filters tool would replace [TextureRenderer]'s shader,
 * not this orchestration — and no text overlay or music replacement either; see
 * [VideoEditRecipe]'s own doc for why each is its own, separately-scoped follow-up rather than a
 * new field on that recipe.
 *
 * ## A known limitation, stated rather than hidden
 * [EncodedTrack] spills a whole track's compressed output to a temp file rather than holding it in
 * memory (P0-11, see [SampleStore]'s own doc) — the OOM risk that motivated [MAX_TRANSCODE_DURATION_MS]
 * originally is gone, but the cap is kept anyway: "single-clip" is this whole roadmap phase's own
 * stated scope, and a multi-hour transcode is still a real amount of wall-clock time and disk churn
 * this first pass was never meant to promise. [checkFreeSpace] refuses up front, before any
 * decoding starts, when there is not enough room for that spill.
 * This pipeline has NOT been exercised against a real device or emulator — the environment this
 * was built in has neither — and must be verified there before it ships to production. Every piece
 * follows the long-stable, extensively documented `EGL14`/`GLES20`/`MediaCodec` Surface-transcode
 * pattern used by Android's own reference samples, but that is a claim about the design, not a
 * substitute for actually running it.
 */
class VideoTranscoder(context: Context) {
    private val appContext = context.applicationContext

    suspend fun transcode(
        asset: MediaAsset,
        outputFd: FileDescriptor,
        recipe: VideoEditRecipe = VideoEditRecipe(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(asset.isVideo) { "${asset.displayName} is not a video" }
            check(deviceSupportsH264AacEncoding()) { "This device has no H.264/AAC encoder to convert into" }
            runTranscode(asset, outputFd, recipe)
        }
    }

    private suspend fun runTranscode(asset: MediaAsset, outputFd: FileDescriptor, recipe: VideoEditRecipe) {
        val extractor = MediaExtractor().apply { setDataSource(appContext, asset.contentUri, null) }
        var videoTrack: EncodedTrack? = null
        var audioTrack: EncodedTrack? = null
        try {
            val videoTrackIndex = findTrack(extractor, "video/") ?: error("${asset.displayName} has no video track")
            val audioTrackIndex = findTrack(extractor, "audio/") // null: a silent source video

            // Read off the video track's OWN declared duration rather than trusting
            // asset.durationMillis alone -- that value comes from this app's MediaStore scan and
            // is not always populated for every file MediaExtractor can otherwise open, which
            // would wrongly refuse a genuinely convertible video. Falls back to asset.durationMillis
            // only when the track itself declares nothing either. Checked against the SOURCE span
            // (pre-trim, pre-speed): the cap exists to bound how much this app decodes at once,
            // which trimming or speeding up the OUTPUT does nothing to reduce.
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val trackDurationMs = videoFormat
                .let { format -> if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000L else null }
                ?: asset.durationMillis
            check(trackDurationMs in 1..MAX_TRANSCODE_DURATION_MS) {
                "${asset.displayName} is too long to convert in this build " +
                    "(${trackDurationMs / 1_000}s, limit ${MAX_TRANSCODE_DURATION_MS / 1_000}s)"
            }

            // Every pre-flight check below runs BEFORE any decoding starts (P0-11): a doomed
            // transcode must never get as far as spending EGL/decoder/encoder setup, let alone
            // spilling a single sample, on a source this device can never actually finish.
            runPreflightChecks(videoFormat)
            checkFreeSpace(trackDurationMs)

            videoTrack = encodeVideoTrack(extractor, videoTrackIndex, recipe)
            audioTrack = audioTrackIndex?.let { index ->
                resetToStart(extractor, index)
                val audioFormat = extractor.getTrackFormat(index)
                val alreadyAac = audioFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
                if (alreadyAac && recipe.speedFactor == 1f) {
                    // Already the target codec and no speed change: copy the compressed bytes
                    // as-is (trimmed and rebased, but otherwise untouched), exactly ClipExporter's
                    // own stream-copy, rather than paying a decode+re-encode's cost and a second
                    // generation of lossy compression for no format change. A speed change cannot
                    // take this path: it needs the actual PCM samples resampled (resamplePcm16's
                    // own doc), which a byte-for-byte copy has no way to do.
                    copyCompressedTrack(extractor, index, audioFormat, recipe)
                } else {
                    AudioTranscoder.transcodeTrack(extractor, index, recipe, appContext.cacheDir)
                }
            }

            // P0-11: the container's own rotation, written via MediaMuxer.setOrientationHint
            // inside muxTracks -- see StreamCopyRemuxer's own identical precedent (P0-05).
            val rotationDegrees = findRotationDegrees(appContext, asset.contentUri)
            muxTracks(outputFd, videoTrack, audioTrack, rotationDegrees)
        } finally {
            runCatching { extractor.release() }
            videoTrack?.close()
            audioTrack?.close()
        }
    }

    /** HDR-source and unsupported-size refusals (P0-11) -- both before any decoding starts, so a
     *  doomed transcode never spends EGL/decoder/encoder setup on a source this device or this
     *  app's fixed SDR target can never actually finish. */
    private fun runPreflightChecks(videoFormat: MediaFormat) {
        val colorTransfer = videoFormat.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val profile = videoFormat.intOrNull(MediaFormat.KEY_PROFILE)
        hdrRefusalReason(colorTransfer, profile)?.let { error(it) }

        val width = evenSize(videoFormat.getInteger(MediaFormat.KEY_WIDTH))
        val height = evenSize(videoFormat.getInteger(MediaFormat.KEY_HEIGHT))
        check(avcEncoderSupportsSize(width, height)) {
            "This device's H.264 encoder can't produce video at ${width}x$height"
        }
    }

    /** [android.os.StatFs] against [appContext]'s own `cacheDir` -- where [SampleStore] spills
     *  both tracks' samples (P0-11) -- refusing before any of it is written rather than failing
     *  partway through with a bare `IOException`. */
    private fun checkFreeSpace(durationMs: Long) {
        val availableBytes = StatFs(appContext.cacheDir.path).availableBytes
        val requiredBytes = requiredSpillBytes(MAX_VIDEO_BIT_RATE, durationMs)
        check(availableBytes >= requiredBytes) {
            "Not enough free space to convert this video " +
                "(need about ${requiredBytes / 1_000_000} MB, ${availableBytes / 1_000_000} MB available)"
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return null
    }

    /**
     * Selects exactly [trackIndex] and rewinds to the start — needed between tracks because a
     * shared [MediaExtractor] otherwise carries over both a stale track SELECTION (from whichever
     * track was processed before this one) and a stale READ POSITION (left at end-of-stream by
     * that track's own consuming loop). Safe to call before the very first track too: unselecting
     * a track that was never selected is a documented no-op.
     */
    private fun resetToStart(extractor: MediaExtractor, trackIndex: Int) {
        for (index in 0 until extractor.trackCount) runCatching { extractor.unselectTrack(index) }
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    private suspend fun copyCompressedTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
        recipe: VideoEditRecipe,
    ): EncodedTrack {
        // Every AAC access unit is independently decodable (no P/B-frame chain the way video
        // has), so the extractor lands very close to trimStartUs on its own -- the per-sample
        // isWithinTrim check below is still what makes the boundary EXACT rather than
        // "whichever frame the seek happened to land on".
        if (recipe.trimStartUs > 0L) extractor.seekTo(recipe.trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val declaredMax = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else {
            0
        }
        val buffer = ByteBuffer.allocateDirect(declaredMax.coerceIn(MIN_COPY_BUFFER_BYTES, MAX_COPY_BUFFER_BYTES))
        val sampleStore = SampleStore.create(appContext.cacheDir)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (recipe.trimEndUs != null && sampleTimeUs >= recipe.trimEndUs) break
                if (isWithinTrim(sampleTimeUs, recipe)) {
                    val data = ByteArray(size)
                    buffer.limit(size)
                    buffer.position(0)
                    buffer.get(data)
                    val exportedTimeUs = exportedPresentationTimeUs(sampleTimeUs, recipe)
                    sampleStore.append(data, exportedTimeUs, muxerBufferFlagsFor(extractor.sampleFlags))
                }
                extractor.advance()
            }
            return EncodedTrack(format, sampleStore)
        } catch (t: Throwable) {
            sampleStore.close()
            throw t
        }
    }

    /**
     * Decodes [trackIndex] to a [DecoderOutputSurface], renders each frame through
     * [TextureRenderer] onto an H.264 encoder's [InputSurface], and drains the encoder's compressed
     * output — see this class's own doc for why a GL bridge is the only way to connect the two.
     *
     * Driven synchronously (dequeue/feed/drain in a single loop on this coroutine's own thread)
     * rather than via `MediaCodec`'s async callback API: a single-clip, non-realtime transcode has
     * no deadline the async API's extra complexity would actually buy anything against, and a
     * synchronous loop is the shape every reference sample this class follows already uses.
     */
    private suspend fun encodeVideoTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        recipe: VideoEditRecipe,
    ): EncodedTrack {
        resetToStart(extractor, trackIndex)
        // The PREVIOUS sync point at or before the trim start, not the exact time -- a decoder
        // needs every frame back to the last keyframe to correctly reconstruct the ones after it.
        // Frames decoded between this seek point and the true trim start are still decoded (for
        // correct GOP state) but never rendered to the encoder -- see isWithinTrim below.
        if (recipe.trimStartUs > 0L) extractor.seekTo(recipe.trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val sourceFormat = extractor.getTrackFormat(trackIndex)
        // Rounded down to even (P0-11): an AVC encoder needs even dimensions, and this is the
        // SAME rounding runPreflightChecks already probed avcEncoderSupportsSize against, so an
        // odd source that only became convertible because of this rounding is actually encoded at
        // the size that was checked, not the original odd one. The decoder below still decodes at
        // the source's own true (possibly odd) resolution -- only the encoder's target and the
        // render step need the even size.
        val width = evenSize(sourceFormat.getInteger(MediaFormat.KEY_WIDTH))
        val height = evenSize(sourceFormat.getInteger(MediaFormat.KEY_HEIGHT))
        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Video track has no MIME type")
        val frameRate = if (sourceFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(DEFAULT_FRAME_RATE)
        } else {
            DEFAULT_FRAME_RATE
        }

        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitRate(width, height, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL_SECONDS)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Order matters from here: the encoder's input surface must exist before an EGL window
        // surface can wrap it, an EGL context must be CURRENT before any GL object (the decoder's
        // texture, the renderer's shader program) can be created, and the decoder must not start
        // until the surface it will render into (the texture) exists.
        val egl = EglCore()
        val inputSurface = InputSurface(egl, encoder.createInputSurface())
        inputSurface.makeCurrent()
        val decoderOutputSurface = DecoderOutputSurface()
        val textureRenderer = TextureRenderer()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(sourceFormat, decoderOutputSurface.surface, null, 0)
        decoder.start()

        val sampleStore = SampleStore.create(appContext.cacheDir)
        try {
            var outputFormat: MediaFormat? = null
            try {
                var decoderInputDone = false
                var decoderOutputDone = false
                var encoderDone = false
                val decoderBufferInfo = MediaCodec.BufferInfo()
                val encoderBufferInfo = MediaCodec.BufferInfo()
                val transformMatrix = FloatArray(16)

                while (!encoderDone) {
                    currentCoroutineContext().ensureActive()

                    if (!decoderInputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("Decoder offered input buffer $inputIndex with no backing buffer")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val pastTrimEnd = recipe.trimEndUs != null &&
                                sampleSize >= 0 && extractor.sampleTime >= recipe.trimEndUs
                            if (sampleSize < 0 || pastTrimEnd) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderInputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    if (!decoderOutputDone) {
                        val outputIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, CODEC_TIMEOUT_US)
                        if (outputIndex >= 0) {
                            val isEos = decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            // A decoded frame this app actually wants to keep -- as opposed to one
                            // decoded only to give the decoder correct GOP state on the way to the
                            // trim start (see the SEEK_TO_PREVIOUS_SYNC comment above), which must
                            // still be released (to free the buffer) but never rendered or encoded.
                            val isWantedFrame = decoderBufferInfo.size > 0 &&
                                isWithinTrim(decoderBufferInfo.presentationTimeUs, recipe)
                            decoder.releaseOutputBuffer(outputIndex, isWantedFrame)
                            if (isWantedFrame) {
                                // Rendering a frame is a GPU-side, asynchronous consequence of
                                // releaseOutputBuffer(render = true) above -- awaitNewImage blocks
                                // until it has actually landed. See DecoderOutputSurface's own doc.
                                decoderOutputSurface.awaitNewImage()
                                inputSurface.makeCurrent()
                                decoderOutputSurface.transformMatrix(transformMatrix)
                                textureRenderer.draw(decoderOutputSurface.textureId, transformMatrix, width, height)
                                val exportedTimeUs = exportedPresentationTimeUs(decoderBufferInfo.presentationTimeUs, recipe)
                                inputSurface.setPresentationTime(exportedTimeUs * 1_000L)
                                inputSurface.swapBuffers()
                            }
                            if (isEos) {
                                decoderOutputDone = true
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }

                    val encoderOutputIndex = encoder.dequeueOutputBuffer(encoderBufferInfo, CODEC_TIMEOUT_US)
                    when {
                        encoderOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                        encoderOutputIndex >= 0 -> {
                            // The codec-config buffer (SPS/PPS) is carried by the MediaFormat handed
                            // to MediaMuxer.addTrack, not written as a sample.
                            val isCodecConfig = encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isCodecConfig && encoderBufferInfo.size > 0) {
                                val outputBuffer = encoder.getOutputBuffer(encoderOutputIndex)
                                    ?: error("Encoder offered output buffer $encoderOutputIndex with no backing buffer")
                                val data = ByteArray(encoderBufferInfo.size)
                                outputBuffer.position(encoderBufferInfo.offset)
                                outputBuffer.get(data)
                                val muxerFlags = encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                                sampleStore.append(data, encoderBufferInfo.presentationTimeUs, muxerFlags)
                            }
                            encoder.releaseOutputBuffer(encoderOutputIndex, false)
                            if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                        }
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
                runCatching { encoder.stop() }
                encoder.release()
                textureRenderer.release()
                decoderOutputSurface.release()
                inputSurface.release()
                egl.release()
            }
            return EncodedTrack(checkNotNull(outputFormat) { "The H.264 encoder never reported an output format" }, sampleStore)
        } catch (t: Throwable) {
            sampleStore.close()
            throw t
        }
    }

    private fun muxTracks(outputFd: FileDescriptor, videoTrack: EncodedTrack, audioTrack: EncodedTrack?, rotationDegrees: Int) {
        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoMuxerIndex = muxer.addTrack(videoTrack.format)
            val audioMuxerIndex = audioTrack?.let { muxer.addTrack(it.format) }
            // Must be set before start(); MediaMuxer ignores it after (P0-11) -- matches
            // StreamCopyRemuxer's own precedent exactly.
            muxer.setOrientationHint(rotationDegrees)
            muxer.start()
            muxer.writeSamples(videoMuxerIndex, videoTrack)
            if (audioTrack != null && audioMuxerIndex != null) muxer.writeSamples(audioMuxerIndex, audioTrack)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** A bitrate scaled to resolution and frame rate rather than one fixed number — a 4K target
     *  at 480p's bitrate is an artifact festival, and a 480p target at 4K's bitrate wastes the
     *  export's whole size budget on nothing visible. ~0.1 bits per pixel per frame is a
     *  widely-used rule of thumb for a visually-transparent H.264 encode. */
    private fun targetVideoBitRate(width: Int, height: Int, frameRate: Int): Int {
        val bitsPerSecond = width.toLong() * height.toLong() * frameRate * VIDEO_BITS_PER_PIXEL_PER_FRAME
        return bitsPerSecond.toInt().coerceIn(MIN_VIDEO_BIT_RATE, MAX_VIDEO_BIT_RATE)
    }

    private companion object {
        /**
         * [EncodedTrack] spills a whole track's compressed bytes to disk rather than memory
         * (P0-11, see [SampleStore]'s own doc) — the OOM risk this cap originally guarded against
         * is gone, but it is kept anyway: "single-clip" is this whole roadmap phase's own stated
         * scope, and a multi-hour transcode is still real wall-clock time and disk churn this
         * first pass was never meant to promise, not an arbitrary number picked to make a demo
         * work.
         */
        const val MAX_TRANSCODE_DURATION_MS = 10 * 60 * 1_000L

        const val CODEC_TIMEOUT_US = 10_000L
        const val DEFAULT_FRAME_RATE = 30
        const val VIDEO_I_FRAME_INTERVAL_SECONDS = 2
        const val VIDEO_BITS_PER_PIXEL_PER_FRAME = 0.1
        const val MIN_VIDEO_BIT_RATE = 1_000_000
        const val MAX_VIDEO_BIT_RATE = 20_000_000

        const val MIN_COPY_BUFFER_BYTES = 1 shl 20 // 1 MiB
        const val MAX_COPY_BUFFER_BYTES = 32 shl 20 // 32 MiB
    }
}

/**
 * Bytes [VideoTranscoder.checkFreeSpace] requires to be free before starting a transcode of
 * [durationMs] at up to [maxBitRateBitsPerSecond] (P0-11) — twice the estimated spill size (the
 * video track's own spill plus, generously, room for the audio track's alongside it, both present
 * on disk at once until [VideoTranscoder.muxTracks] finishes and both are deleted) plus a fixed
 * margin for unrelated disk activity during a multi-minute transcode.
 *
 * A pure function over already-known values (no [android.os.StatFs] call itself), so it is unit
 * testable without a real filesystem.
 */
internal fun requiredSpillBytes(maxBitRateBitsPerSecond: Int, durationMs: Long): Long =
    2L * (maxBitRateBitsPerSecond / 8) * (durationMs / 1_000) + FREE_SPACE_MARGIN_BYTES

private const val FREE_SPACE_MARGIN_BYTES = 100_000_000L
