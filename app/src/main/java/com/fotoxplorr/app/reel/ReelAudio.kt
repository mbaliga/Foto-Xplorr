package com.fotoxplorr.app.reel

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Lays [ReelAudioPlan.gainAtUs] over the soundtrack's samples: the volume, and the fall to silence at
 * the end of the reel. It runs inside the export on the music's own item, so its clock starts at the
 * music's first sample (after the trim) and restarts if the pipeline is flushed. Nothing is played.
 *
 * Only 16-bit PCM is handled, which is what the platform decoders give the export pipeline; anything
 * else is refused with [UnhandledAudioFormatException], and the export reports an audio problem (the
 * person is then asked whether to make the reel without sound). Samples past the plan's length are
 * written as silence.
 */
@UnstableApi
class ReelFadeProcessor(private val plan: ReelAudioPlan) : BaseAudioProcessor() {
    private var framesSeen = 0L

    @Throws(UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val out = replaceOutputBuffer(size)
        val frames = size / (2 * channels)
        for (f in 0 until frames) {
            val gain = plan.gainAtUs(framesSeen * 1_000_000L / rate)
            framesSeen++
            for (c in 0 until channels) {
                val sample = inputBuffer.short.toInt()
                out.putShort((sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // A trailing part of a frame cannot happen with 16-bit PCM; if it did, it is dropped, not guessed at.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        framesSeen = 0L
    }

    override fun onReset() {
        framesSeen = 0L
    }
}
