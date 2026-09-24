package com.fotoxplorr.app.videoeditor

import kotlin.math.roundToInt

/**
 * A single-clip video edit, as a photographer or casual editor describes it — trim range and
 * playback speed — kept as pure data the same way
 * [com.fotoxplorr.app.editor.EditRecipe] separates a photo edit's description from the pixels it
 * produces. [com.fotoxplorr.app.video.VideoTranscoder] is the only thing that ever turns this into
 * actual re-encoded bytes.
 *
 * Text overlay, music replacement and filters — the rest of this roadmap phase's named scope — are
 * a deliberate, named follow-up, not an oversight: each needs new pipeline capability of its own
 * (frame compositing for text, a second audio source with its own duration/looping for music, a
 * shader for filters) rather than a new field on this recipe, so none of them belong here yet. See
 * `docs/adr/ADR-008-video-transcode-pipeline.md`.
 *
 * @param trimStartUs where the exported clip begins, in microseconds into the source.
 * @param trimEndUs where the exported clip ends, or null for "the end of the source". Kept
 *   nullable rather than defaulting to the source's own duration because this type has no way to
 *   know that duration itself — it is pure data, with no file behind it — so "the end" has to be a
 *   real absence rather than a guessed number that could be wrong for a specific source.
 * @param speedFactor how much faster (>1) or slower (<1) the exported clip plays. Changes pitch
 *   along with tempo (the classic "sped-up tape" effect) rather than preserving pitch — real
 *   pitch-preserving time-stretch is signal-processing work this first pass does not build; see
 *   [resamplePcm16]'s own doc.
 */
data class VideoEditRecipe(
    val trimStartUs: Long = 0L,
    val trimEndUs: Long? = null,
    val speedFactor: Float = 1f,
) {
    init {
        require(trimStartUs >= 0L) { "trimStartUs must not be negative, was $trimStartUs" }
        require(trimEndUs == null || trimEndUs > trimStartUs) {
            "trimEndUs ($trimEndUs) must be after trimStartUs ($trimStartUs)"
        }
        require(speedFactor > 0f) { "speedFactor must be positive, was $speedFactor" }
    }

    /** True when this recipe would not actually change anything — the same "skip a no-op export"
     *  guard [com.fotoxplorr.app.editor.EditRecipe.isIdentity] gives the photo editor's Save button. */
    val isIdentity: Boolean
        get() = trimStartUs == 0L && trimEndUs == null && speedFactor == 1f
}

/** Whether the source sample at [sampleTimeUs] falls inside [recipe]'s trim window. */
internal fun isWithinTrim(sampleTimeUs: Long, recipe: VideoEditRecipe): Boolean {
    val end = recipe.trimEndUs
    return sampleTimeUs >= recipe.trimStartUs && (end == null || sampleTimeUs < end)
}

/**
 * The presentation time a source frame/sample at [originalUs] should carry in the EXPORTED clip:
 * rebased so the trimmed clip starts at time zero, then compressed or stretched by
 * [VideoEditRecipe.speedFactor] (a factor above 1 plays faster, so the SAME span of source time
 * has to claim a SHORTER span of output time — dividing, not multiplying, by the factor).
 *
 * Pulled out of [com.fotoxplorr.app.video.VideoTranscoder]'s encode loop for the same reason
 * [com.fotoxplorr.app.audio.pcmFrameCount] was pulled out of [com.fotoxplorr.app.audio.AudioTranscoder]:
 * getting a timestamp calculation wrong does not fail loudly, it silently desyncs picture and sound
 * over the length of a clip.
 */
internal fun exportedPresentationTimeUs(originalUs: Long, recipe: VideoEditRecipe): Long =
    ((originalUs - recipe.trimStartUs) / recipe.speedFactor).toLong()

/**
 * Resamples 16-bit PCM audio to [speed] — above 1 plays faster (fewer output frames for the same
 * source span), below 1 slower — via linear interpolation per channel. Changes pitch along with
 * tempo exactly the way this function's predecessor, `speedAdjustedSampleRate` (removed by this
 * same change, P0-11), used to: the samples themselves are now actually resampled, so the result
 * can be encoded at the source's own valid sample rate instead of a scaled, frequently-invalid one
 * — 1.5× used to turn 44,100 Hz into 66,150 Hz, which AAC cannot encode at all, so exports with
 * sound at most non-1× speeds most likely failed outright. See [VideoEditRecipe.speedFactor]'s own
 * doc for why this changes pitch along with tempo rather than preserving it.
 *
 * A pure function over PCM samples (no [android.media.MediaCodec]/[android.media.MediaExtractor]
 * dependency), so it is unit-testable without a real device.
 *
 * @param interleaved source PCM, one 16-bit sample per channel per frame, channels interleaved.
 *   Its length must be a multiple of [channels].
 * @param channels the channel count [interleaved] is interleaved for.
 */
internal fun resamplePcm16(interleaved: ShortArray, channels: Int, speed: Float): ShortArray {
    require(channels > 0) { "channels must be positive, was $channels" }
    require(speed > 0f) { "speed must be positive, was $speed" }
    val inFrames = interleaved.size / channels
    if (inFrames == 0) return ShortArray(0)
    val outFrames = (inFrames / speed).roundToInt().coerceAtLeast(1)
    val output = ShortArray(outFrames * channels)
    for (outFrame in 0 until outFrames) {
        val sourcePosition = outFrame * speed
        val sourceFrame = sourcePosition.toInt().coerceIn(0, inFrames - 1)
        val nextFrame = (sourceFrame + 1).coerceAtMost(inFrames - 1)
        val fraction = sourcePosition - sourceFrame
        for (channel in 0 until channels) {
            val a = interleaved[sourceFrame * channels + channel]
            val b = interleaved[nextFrame * channels + channel]
            output[outFrame * channels + channel] = (a + (b - a) * fraction).roundToInt().toShort()
        }
    }
    return output
}
