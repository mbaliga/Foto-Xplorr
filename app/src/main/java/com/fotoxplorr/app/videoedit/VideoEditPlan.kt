package com.fotoxplorr.app.videoedit

/**
 * What has been done to a video, as data rather than as frames — the exact philosophy of the
 * photo editor's `EditRecipe`, applied to time-based media. Nothing here touches a decoder;
 * the plan is a small value, and pixels/samples are produced from it only at preview (ExoPlayer)
 * and export (Media3 Transformer) time. The original file is never written to: like every edit
 * in this app, "save" means "save a copy".
 *
 * Fields deliberately mirror the recipe's conventions where the concepts overlap
 * ([quarterTurns], [flipHorizontal]) so the two editors stay one mental model.
 */
data class VideoEditPlan(
    /** Total source duration, as reported by the loaded asset. The plan is meaningless without it. */
    val sourceDurationMs: Long,
    /** Trim in-point, inclusive, ms. */
    val trimStartMs: Long = 0L,
    /** Trim out-point, exclusive, ms. [sourceDurationMs] means "to the end". */
    val trimEndMs: Long = sourceDurationMs,
    /** Quarter turns clockwise, 0..3 — same convention as the photo recipe. */
    val quarterTurns: Int = 0,
    /** Mirror horizontally, applied after rotation — same convention as the photo recipe. */
    val flipHorizontal: Boolean = false,
    /** Playback-rate multiplier applied to the whole clip. One of [SPEED_CHOICES]. */
    val speed: Float = 1f,
    /** Drop the audio track entirely. */
    val muted: Boolean = false,
    /**
     * Target aspect for a centred crop, width:height, or null for the source's own frame.
     * A centred aspect crop covers the overwhelming share of real mobile crops (square posts,
     * vertical stories); a free pan-and-zoom crop is a later, separate control.
     */
    val cropAspect: CropAspect? = null,
) {
    init {
        require(sourceDurationMs > 0) { "A video plan needs the source duration." }
        require(trimStartMs in 0 until sourceDurationMs) { "Trim start is outside the video." }
        require(trimEndMs in (trimStartMs + 1)..sourceDurationMs) { "Trim end must sit after trim start, inside the video." }
        require(quarterTurns in 0..3) { "Quarter turns are 0..3." }
        require(speed in MIN_SPEED..MAX_SPEED) { "Speed $speed is outside $MIN_SPEED..$MAX_SPEED." }
    }

    /** True when exporting would reproduce the source — the "Save must be disabled" test. */
    val isIdentity: Boolean
        get() = trimStartMs == 0L && trimEndMs == sourceDurationMs &&
            quarterTurns == 0 && !flipHorizontal && speed == 1f && !muted && cropAspect == null

    /** The clip's duration after trimming, before the speed change. */
    val trimmedDurationMs: Long get() = trimEndMs - trimStartMs

    /** What the exported file will run for, after trim and speed. */
    val exportedDurationMs: Long get() = (trimmedDurationMs / speed).toLong()

    /** Whether the rotation swaps the frame's width and height. */
    val swapsDimensions: Boolean get() = quarterTurns % 2 == 1

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        /** The offered speeds: slow-motion halves and the common fast-forwards. */
        val SPEED_CHOICES = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

        /** The shortest clip the editor will export; trims collapsing below this are refused. */
        const val MIN_TRIMMED_MS = 200L
    }
}

/** Centred crop targets, in the order the strip offers them. */
enum class CropAspect(val widthOverHeight: Float, val label: String) {
    SQUARE(1f, "1:1"),
    WIDE(16f / 9f, "16:9"),
    TALL(9f / 16f, "9:16"),
    CLASSIC(4f / 3f, "4:3"),
    ;

    /**
     * The normalized device-coordinate crop box for a source frame of [sourceWidth] x
     * [sourceHeight] AFTER [rotatedQuarterTurns] has been applied — Media3's `Crop` effect
     * takes NDC edges in -1..1. Returns null when the source already has this aspect (within
     * one part in a thousand): a no-op crop must not force a re-encode of the video track's
     * geometry for nothing.
     */
    fun ndcCrop(sourceWidth: Int, sourceHeight: Int, rotatedQuarterTurns: Int): NdcCrop? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val swapped = rotatedQuarterTurns % 2 == 1
        val frameWidth = if (swapped) sourceHeight else sourceWidth
        val frameHeight = if (swapped) sourceWidth else sourceHeight
        val sourceAspect = frameWidth.toFloat() / frameHeight
        val target = widthOverHeight
        if (kotlin.math.abs(sourceAspect - target) / target < 0.001f) return null
        return if (sourceAspect > target) {
            // Source is wider than the target: crop the sides.
            val keep = target / sourceAspect // fraction of width kept, 0..1
            NdcCrop(left = -keep, right = keep, bottom = -1f, top = 1f)
        } else {
            // Source is taller: crop top and bottom.
            val keep = sourceAspect / target
            NdcCrop(left = -1f, right = 1f, bottom = -keep, top = keep)
        }
    }
}

/** A crop in normalized device coordinates, the shape Media3's Crop effect consumes. */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    init {
        require(left < right && bottom < top) { "Inverted crop box." }
        require(left >= -1f && right <= 1f && bottom >= -1f && top <= 1f) { "Crop box outside NDC." }
    }
}
