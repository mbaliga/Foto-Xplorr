package com.fotoxplorr.app.animate

import kotlin.math.max

/**
 * What the screen can say about a file before it is made: the size it would be drawn at, how many
 * frames and how long it plays once the planner has fitted it to the ceilings, a size with the range
 * it could honestly fall in, and what the planner gave up to fit.
 *
 * The size is [AnimPlanner]'s model for a GIF or an APNG (fitted to this package's encoders on
 * synthetic scenes, never on real photographs) and a bitrate guess for a video. It is shown with
 * [AnimateCopy.SIZE_IS_A_GUESS] next to it, and the file's real size is only known after it is made.
 */
class AnimateEstimate(
    val width: Int,
    val height: Int,
    val frames: Int,
    val playMs: Long,
    val bytes: SizeEstimate,
    val steps: List<PlanStep>,
    val fitsBytes: Boolean,
    /** A GIF whose typical size is large enough that a video would be the better offer. */
    val largeGif: Boolean,
)

/** The estimate for a draft, from the same timelines, planner and ceilings the job will use. */
object AnimateEstimator {
    /** From this typical size a GIF is called large. A round figure, not a measured limit. */
    const val LARGE_GIF_BYTES = 4L * 1024 * 1024

    /** The low and high ends of a video's size, as shares of the bitrate times the length. The encoder may undershoot a still scene by a lot. */
    private const val VIDEO_LOW = 0.25
    private const val VIDEO_HIGH = 1.25

    /** Used when the library row has no size (a width or height of 0). */
    private const val FALLBACK_STILL_W = 1600
    private const val FALLBACK_STILL_H = 1200
    private const val FALLBACK_VIDEO_W = 1280
    private const val FALLBACK_VIDEO_H = 720

    /**
     * The estimate, or null when there is nothing to estimate: the draft is incomplete, or the source
     * is an existing file (its frames are only known by reading it, so the screen says the size is
     * known once it is made).
     */
    fun of(draft: AnimateDraft, library: AnimateLibrary): AnimateEstimate? {
        val output = draft.output
        val width: Int
        val height: Int
        val delays: IntArray
        val changeFraction: Double
        var clipFps: Double? = null
        when (draft.source) {
            AnimateSource.PHOTOS -> {
                if (draft.photos.size < AnimateDraft.MIN_PHOTOS) return null
                val first = library.photo(draft.photos.first().id) ?: return null
                if (draft.photos.any { library.photo(it.id) == null }) return null
                width = first.width.takeIf { it > 0 } ?: FALLBACK_STILL_W
                height = first.height.takeIf { it > 0 } ?: FALLBACK_STILL_H
                val transition = if (draft.fade) StillTransition(AnimateDraft.FADE_FRAMES, AnimateDraft.FADE_FRAME_MS, loop = draft.loop) else null
                delays = StillsTimeline.build(IntArray(draft.photos.size) { draft.effectiveHoldMs(draft.photos[it]) }, transition).delaysMs
                // Unrelated pictures: nearly every pixel differs from the one before (as StillsFrameSource says).
                changeFraction = 1.0
            }
            AnimateSource.CLIP -> {
                val video = draft.clipId?.let(library::video) ?: return null
                width = video.width.takeIf { it > 0 } ?: FALLBACK_VIDEO_W
                height = video.height.takeIf { it > 0 } ?: FALLBACK_VIDEO_H
                val end = if (video.durationMillis > 0) minOf(draft.clipEndMs, video.durationMillis) else draft.clipEndMs
                if (end <= draft.clipStartMs) return null
                delays = ClipTimeline.build(draft.clipStartMs, end, draft.clipFps.toDouble()).delaysMs
                changeFraction = CLIP_CHANGE
                clipFps = draft.clipFps.toDouble()
            }
            AnimateSource.FILE -> return null
        }
        val base = AnimateDefaults.limits(output)
        // As the job does: a video's size follows its bitrate, so the planner's GIF/PNG rates must not thin its frames.
        val limits = if (output.isVideo) PlanLimits(base.maxLongEdge, base.maxFrames, Long.MAX_VALUE / 4, base.maxDurationMs, base.minLongEdge, base.minFps) else base
        val plan = AnimPlanner.plan(
            PlanRequest(width, height, delays, output.planFormat, ContentKind.PHOTO, changeFraction, targetFps = null, limits = limits),
        )
        if (!output.isVideo) {
            val large = output == AnimOutput.GIF && (plan.estimate.typicalBytes >= LARGE_GIF_BYTES || !plan.fitsBytes)
            return AnimateEstimate(plan.width, plan.height, plan.picks.size, plan.playMs, plan.estimate, plan.steps, plan.fitsBytes, large)
        }
        val (w, h) = VideoTimeline.evenSize(plan.width, plan.height)
        val fps = VideoTimeline.fpsFor(clipFps)
        val typical = VideoTimeline.bitrate(w, h, fps) / 8.0 * (plan.playMs / 1000.0)
        val bytes = SizeEstimate(typical.toLong(), (typical * VIDEO_LOW).toLong(), (typical * VIDEO_HIGH).toLong())
        val fits = bytes.typicalBytes <= base.maxBytes
        val steps = if (fits) plan.steps else plan.steps + PlanStep.STILL_TOO_BIG
        return AnimateEstimate(w, h, plan.picks.size, plan.playMs, bytes, steps, fits, largeGif = false)
    }

    /** The share of the picture a video clip is taken to change from one frame to the next, as [VideoFrameSource] reports it. */
    private const val CLIP_CHANGE = 0.6

    /** How many frames a part of a video of [lengthMs] makes at [fps] a second, before the planner thins it. */
    fun clipFrames(lengthMs: Long, fps: Int): Int = max(1, ClipTimeline.build(0L, max(1L, lengthMs), fps.toDouble()).delaysMs.size)
}
