package com.fotoxplorr.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fotoxplorr.app.video.EncodedTrack
import com.fotoxplorr.app.video.SampleStore
import com.fotoxplorr.app.videoeditor.VideoEditRecipe
import com.fotoxplorr.app.videoeditor.isWithinTrim
import com.fotoxplorr.app.videoeditor.resamplePcm16
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes one audio track to PCM and re-encodes it to AAC, in [MediaCodec]'s plain buffer mode —
 * no [android.view.Surface], no GL.
 *
 * Audio has none of video's format-zoo problem: every decoder on every device hands back
 * interleaved PCM via [MediaCodec.getOutputBuffer], regardless of the source codec — but NOT
 * always at the container's own declared sample rate, channel count or bit depth (P0-11): HE-AAC
 * (SBR) and parametric stereo sources in particular decode at a different rate or channel count
 * than their track's own `MediaFormat` claims, and some decoders hand back float PCM rather than
 * 16-bit. [decodeToPcm] reads the DECODER's actual output format (`INFO_OUTPUT_FORMAT_CHANGED`),
 * not the container's declared one, and converts float PCM to 16-bit before this class does
 * anything else with it.
 *
 * The whole track is decoded to PCM and spilled to disk (a [SampleStore], the same shape
 * [com.fotoxplorr.app.video.VideoTranscoder] uses for its own encoded tracks, P0-11) before
 * encoding begins.
 */
internal object AudioTranscoder {

    suspend fun transcodeTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        recipe: VideoEditRecipe,
        cacheDir: File,
    ): EncodedTrack {
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val decoded = decodeToPcm(extractor, trackIndex, inputFormat, recipe)
        // Trim already rebased the PCM itself to start at the trim point (decodeToPcm only keeps
        // chunks inside the window), so encodeFromPcm's own frame counter naturally produces a
        // zero-based timeline with no separate rebasing step needed here.
        val pcmChunks = if (recipe.speedFactor == 1f) {
            decoded.chunks
        } else {
            // Speed now actually resamples the PCM (P0-11), rather than relabelling the sample
            // rate the old speedAdjustedSampleRate did -- see resamplePcm16's own doc for why that
            // used to produce AAC-invalid sample rates. Flattened to one array first: a MediaCodec
            // output buffer boundary is not guaranteed to land on a whole PCM frame across
            // consecutive chunks, only within each one, so resampling chunk-by-chunk could read a
            // channel's samples out of phase at a boundary.
            val flattened = concatenate(decoded.chunks).toShortArray()
            listOf(resamplePcm16(flattened, decoded.channelCount, recipe.speedFactor).toByteArray())
        }
        return encodeFromPcm(pcmChunks, decoded.sampleRate, decoded.channelCount, cacheDir)
    }

    /** [decodeToPcm]'s own result: the decoded PCM plus the sample rate/channel count the DECODER
     *  actually produced (P0-11) -- may differ from the source track's own declared format. */
    private data class DecodedPcm(
        val chunks: List<ByteArray>,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private suspend fun decodeToPcm(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
        recipe: VideoEditRecipe,
    ): DecodedPcm {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME type")
        val decoder = MediaCodec.createDecoderByType(mime)
        val chunks = mutableListOf<ByteArray>()
        // Seeded from the container's own declared format, in case the decoder never reports
        // INFO_OUTPUT_FORMAT_CHANGED at all (some decoders don't, for a format that never
        // actually changes from what configure() was given) -- overwritten below the moment the
        // decoder DOES report its real output format.
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.selectTrack(trackIndex)
            // Every AAC access unit is independently decodable, so this lands very close to the
            // true start on its own -- the per-chunk isWithinTrim check below is what makes the
            // boundary exact rather than "whichever frame the seek happened to land on".
            if (recipe.trimStartUs > 0L) extractor.seekTo(recipe.trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()

                if (!sawInputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Decoder offered input buffer $inputIndex with no backing buffer")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        val pastTrimEnd = recipe.trimEndUs != null &&
                            sampleSize >= 0 && extractor.sampleTime >= recipe.trimEndUs
                        if (sampleSize < 0 || pastTrimEnd) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder's own actual output format (P0-11) -- may disagree with the
                        // container's declared one (HE-AAC/SBR, parametric stereo), and this is the
                        // only place that disagreement is ever visible.
                        val outputFormat = decoder.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        val isWanted = bufferInfo.size > 0 && isWithinTrim(bufferInfo.presentationTimeUs, recipe)
                        if (isWanted) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("Decoder offered output buffer $outputIndex with no backing buffer")
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(chunk)
                            // Every downstream consumer (resamplePcm16, encodeFromPcm's own AAC
                            // encoder input) assumes 16-bit PCM -- converted here, once, rather
                            // than threading the encoding through every later step (P0-11).
                            chunks += if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                floatPcmToInt16(chunk)
                            } else {
                                chunk
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
        return DecodedPcm(chunks, sampleRate, channelCount)
    }

    private suspend fun encodeFromPcm(
        pcmChunks: List<ByteArray>,
        sampleRate: Int,
        channelCount: Int,
        cacheDir: File,
    ): EncodedTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val sampleStore = SampleStore.create(cacheDir)
        try {
            var outputFormat: MediaFormat? = null
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                // A cursor into pcmChunks rather than one flattened ByteArray: a long clip's whole
                // PCM track copied into one contiguous array would double its peak memory for no
                // reason the encoder's own input-buffer-sized reads need.
                var chunkIndex = 0
                var chunkOffset = 0
                var framesEncoded = 0L
                var sawInputEos = false
                var sawOutputEos = false
                val bufferInfo = MediaCodec.BufferInfo()

                while (!sawOutputEos) {
                    currentCoroutineContext().ensureActive()

                    if (!sawInputEos) {
                        val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                                ?: error("Encoder offered input buffer $inputIndex with no backing buffer")
                            inputBuffer.clear()
                            var written = 0
                            while (chunkIndex < pcmChunks.size && written < inputBuffer.capacity()) {
                                val chunk = pcmChunks[chunkIndex]
                                val toCopy = minOf(chunk.size - chunkOffset, inputBuffer.capacity() - written)
                                inputBuffer.put(chunk, chunkOffset, toCopy)
                                written += toCopy
                                chunkOffset += toCopy
                                if (chunkOffset >= chunk.size) {
                                    chunkIndex++
                                    chunkOffset = 0
                                }
                            }
                            val atEnd = chunkIndex >= pcmChunks.size
                            val presentationTimeUs = presentationTimeUsFor(framesEncoded, sampleRate)
                            encoder.queueInputBuffer(
                                inputIndex, 0, written, presentationTimeUs,
                                if (atEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                            framesEncoded += pcmFrameCount(written, channelCount)
                            if (atEnd) sawInputEos = true
                        }
                    }

                    when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                        else -> if (outputIndex >= 0) {
                            // The codec-config buffer (the AAC decoder-specific-info) is carried by
                            // the MediaFormat handed to MediaMuxer.addTrack, not written as a
                            // sample -- writing it again here would hand the muxer a bogus
                            // zero-length "frame".
                            val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isCodecConfig && bufferInfo.size > 0) {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                    ?: error("Encoder offered output buffer $outputIndex with no backing buffer")
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(data)
                                // No keyframe bit: every AAC frame decodes independently, so audio
                                // has no sync-sample concept for a muxer to need flagged.
                                sampleStore.append(data, bufferInfo.presentationTimeUs, 0)
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                    }
                }
            } finally {
                runCatching { encoder.stop() }
                encoder.release()
            }
            return EncodedTrack(checkNotNull(outputFormat) { "The AAC encoder never reported an output format" }, sampleStore)
        } catch (t: Throwable) {
            sampleStore.close()
            throw t
        }
    }

    private const val CODEC_TIMEOUT_US = 10_000L

    /** 128 kbps: transparent for spoken/ambient phone-clip audio at a fraction of typical source
     *  bitrates, without inflating a short clip's export size. */
    private const val AAC_BIT_RATE = 128_000
}

/** Concatenates every chunk into one array -- see [AudioTranscoder.transcodeTrack]'s own comment
 *  on why speed resampling needs one contiguous buffer rather than chunk-by-chunk conversion. */
private fun concatenate(chunks: List<ByteArray>): ByteArray {
    val result = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}

private fun ByteArray.toShortArray(): ShortArray {
    val shorts = ShortArray(size / 2)
    ByteBuffer.wrap(this).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
    return shorts
}

private fun ShortArray.toByteArray(): ByteArray {
    val bytes = ByteArray(size * 2)
    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(this)
    return bytes
}

/**
 * Converts interleaved 32-bit float PCM (nominally in [-1, 1]) to interleaved 16-bit PCM (P0-11) —
 * some decoders hand back [android.media.AudioFormat.ENCODING_PCM_FLOAT] rather than 16-bit, and
 * every downstream step ([com.fotoxplorr.app.videoeditor.resamplePcm16], the AAC encoder's own
 * input) assumes 16-bit. Clamped rather than wrapped: a decoder can produce samples fractionally
 * outside [-1, 1] after internal processing, and wrapping those would produce an audible click
 * where clamping produces an imperceptible, momentary ceiling.
 */
internal fun floatPcmToInt16(floatBytes: ByteArray): ByteArray {
    val floatBuffer = ByteBuffer.wrap(floatBytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val out = ByteArray(floatBuffer.remaining() * 2)
    val outBuffer = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).asShortBuffer()
    while (floatBuffer.hasRemaining()) {
        val sample = floatBuffer.get().coerceIn(-1f, 1f)
        outBuffer.put((sample * Short.MAX_VALUE).toInt().toShort())
    }
    return out
}
