package com.fotoxplorr.app.audio

/**
 * How many complete 16-bit PCM frames [bytesWritten] bytes of interleaved audio makes up, for
 * [channelCount] channels — pulled out of [AudioTranscoder]'s encode loop because getting this
 * wrong (rounding a partial frame away, or double-counting bytes as frames) does not fail loudly
 * anywhere; it silently drifts audio out of sync with picture over the length of a clip.
 *
 * 16-bit is not a guess: `MediaCodec`'s audio decoders always hand back `ENCODING_PCM_16BIT`
 * output regardless of the source codec — see [AudioTranscoder]'s own class doc on why that
 * uniformity is the whole reason this pipeline needs no format-specific handling the way video
 * does.
 */
internal fun pcmFrameCount(bytesWritten: Int, channelCount: Int): Int {
    require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
    return bytesWritten / (PCM_16BIT_BYTES_PER_SAMPLE * channelCount)
}

/** The presentation time, in microseconds, that [frameCount] PCM frames at [sampleRate] spans. */
internal fun presentationTimeUsFor(frameCount: Long, sampleRate: Int): Long {
    require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    return frameCount * 1_000_000L / sampleRate
}

private const val PCM_16BIT_BYTES_PER_SAMPLE = 2
