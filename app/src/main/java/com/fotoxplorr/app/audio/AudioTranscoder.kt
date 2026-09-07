package com.fotoxplorr.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fotoxplorr.app.video.EncodedSample
import com.fotoxplorr.app.video.EncodedTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes one audio track to PCM and re-encodes it to AAC, in [MediaCodec]'s plain buffer mode —
 * no [android.view.Surface], no GL.
 *
 * Audio has none of video's format-zoo problem: every decoder on every device hands back the same
 * interleaved 16-bit PCM shape via [MediaCodec.getOutputBuffer], regardless of the source codec.
 * That is exactly why this pipeline needs none of [com.fotoxplorr.app.video.gl]'s Surface bridge —
 * that machinery exists solely to work around video decoders NOT agreeing on a buffer-mode pixel
 * layout, a problem audio simply does not have.
 *
 * The whole track is decoded to PCM and held in memory before encoding begins, the same
 * buffer-the-whole-track choice [EncodedTrack] makes for the same reason: PCM for a single clip's
 * audio track is small (a stereo 48kHz track is ~192 KB/second — a ten-minute clip is still only
 * ~115 MB, far under [com.fotoxplorr.app.video.VideoTranscoder]'s own duration cap for the much
 * larger video track it runs alongside), so there is nothing to gain from a streaming decode here.
 */
internal object AudioTranscoder {

    suspend fun transcodeTrack(extractor: MediaExtractor, trackIndex: Int): EncodedTrack {
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val pcmChunks = decodeToPcm(extractor, trackIndex, inputFormat)
        return encodeFromPcm(pcmChunks, sampleRate, channelCount)
    }

    private suspend fun decodeToPcm(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
    ): List<ByteArray> {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME type")
        val decoder = MediaCodec.createDecoderByType(mime)
        val chunks = mutableListOf<ByteArray>()
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.selectTrack(trackIndex)

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
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            ?: error("Decoder offered output buffer $outputIndex with no backing buffer")
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(chunk)
                        chunks += chunk
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
        return chunks
    }

    private suspend fun encodeFromPcm(
        pcmChunks: List<ByteArray>,
        sampleRate: Int,
        channelCount: Int,
    ): EncodedTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // A cursor into pcmChunks rather than one flattened ByteArray: a long clip's whole PCM
            // track copied into one contiguous array would double its peak memory for no reason
            // the encoder's own input-buffer-sized reads need.
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
                        // the MediaFormat handed to MediaMuxer.addTrack, not written as a sample —
                        // writing it again here would hand the muxer a bogus zero-length "frame".
                        val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isCodecConfig && bufferInfo.size > 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                ?: error("Encoder offered output buffer $outputIndex with no backing buffer")
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)
                            // No keyframe bit: every AAC frame decodes independently, so audio has
                            // no sync-sample concept for a muxer to need flagged.
                            samples += EncodedSample(data, bufferInfo.presentationTimeUs, 0)
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
        return EncodedTrack(
            checkNotNull(outputFormat) { "The AAC encoder never reported an output format" },
            samples,
        )
    }

    private const val CODEC_TIMEOUT_US = 10_000L

    /** 128 kbps: transparent for spoken/ambient phone-clip audio at a fraction of typical source
     *  bitrates, without inflating a short clip's export size. */
    private const val AAC_BIT_RATE = 128_000
}
