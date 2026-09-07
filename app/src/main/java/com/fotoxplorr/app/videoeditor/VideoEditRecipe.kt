package com.fotoxplorr.app.videoeditor

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
 *   [speedAdjustedSampleRate]'s own doc.
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
 * The sample rate to LABEL a speed-changed audio track with, while feeding it the source's own
 * unmodified PCM samples — see [VideoEditRecipe.speedFactor]'s own doc on why this changes pitch.
 *
 * This is not resampling: the actual samples are untouched. Telling a decoder "these samples are
 * `originalSampleRate * speedFactor` per second" instead of their true rate is what makes a player
 * read through them faster or slower — precisely the mechanism a sped-up tape or turntable uses,
 * and it needs no DSP at all, which is the whole reason this is the first pass's approach rather
 * than a real time-stretch.
 */
internal fun speedAdjustedSampleRate(originalSampleRate: Int, speedFactor: Float): Int =
    (originalSampleRate * speedFactor).let { scaled ->
        require(scaled >= 1f) { "speedFactor $speedFactor makes $originalSampleRate Hz audio invalid" }
        scaled.toInt().coerceAtLeast(1)
    }
