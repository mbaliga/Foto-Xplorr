package com.fotoxplorr.app.animate

import kotlin.math.max
import kotlin.math.roundToInt

/** The file formats the planner can size. */
enum class AnimFormat { GIF, APNG }

/** What the frames look like, which decides how well they compress. */
enum class ContentKind {
    /** Camera pictures: noise, gradients, many colours. */
    PHOTO,

    /** Flat colour: screenshots, drawings, text. */
    GRAPHICS,
}

/** A size guess with the range it could honestly fall in. */
class SizeEstimate(val typicalBytes: Long, val lowBytes: Long, val highBytes: Long)

/** What the person (or the app on their behalf) will not exceed. */
class PlanLimits(
    val maxLongEdge: Int = 480,
    val maxFrames: Int = 120,
    val maxBytes: Long = 8L * 1024 * 1024,
    val maxDurationMs: Long = 30_000L,
    /** The planner will not shrink the picture below this long edge to save bytes. */
    val minLongEdge: Int = 120,
    /** The planner will not thin the frames below this rate to save bytes, until shrinking has run out. */
    val minFps: Double = 5.0,
) {
    init {
        require(maxLongEdge >= 1 && maxFrames >= 1 && maxBytes >= 1 && maxDurationMs >= 1 && minLongEdge >= 1 && minFps > 0.0)
    }
}

/**
 * What a job is asked to make: the source's size and the time each of its frames lasts, the format,
 * and the ceilings.
 *
 * @param changeFraction how much of the picture is expected to change from one frame to the next
 *   (0 to 1, noise counts): about 0.1 for a still camera on a moving subject, 1 for a handheld clip or
 *   unrelated stills. Only the size estimate uses it. A GIF with see-through pixels is written whole
 *   frame by frame, so pass 1 for it.
 * @param targetFps the rate to play at, or null to keep the source's own timing.
 * @param keepFrames one flag per source frame, true for a frame that is the point of the animation (a
 *   slideshow's hold, as against its fade frames): thinning drops the other frames first and a flagged
 *   one only when no other is left. Null thins every frame alike.
 */
class PlanRequest(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceDelaysMs: IntArray,
    val format: AnimFormat = AnimFormat.GIF,
    val kind: ContentKind = ContentKind.PHOTO,
    val changeFraction: Double = 0.5,
    val targetFps: Double? = null,
    val limits: PlanLimits = PlanLimits(),
    val keepFrames: BooleanArray? = null,
)

/** What the planner did to fit, in the order it did it. */
enum class PlanStep {
    /** The picture was made smaller to the long-edge cap. */
    SHRUNK,

    /** The end was cut off to fit the length cap. */
    TRIMMED,

    /** The frame rate was lowered to the target. */
    RATE_LOWERED,

    /** Frames were dropped to fit the frame cap or the size cap. */
    FRAMES_THINNED,

    /** The picture was made smaller still to fit the size cap. */
    SHRUNK_FOR_SIZE,

    /** Nothing more could be given up and the estimate is still over the size cap. */
    STILL_TOO_BIG,
}

/**
 * The answer: which source frames to use ([picks], indices into the source) and how long each is
 * shown ([delaysMs]), the size to draw them at, a size estimate and what was given up.
 * [playMs] is the real length of the file (GIF rounds delays to hundredths of a second).
 * [fitsBytes] is the estimate against the cap, which is a guess: check the real size after
 * encoding ([AnimPlanner.extrapolate] turns a measured sample into a better guess).
 */
class AnimPlan(
    val width: Int,
    val height: Int,
    val picks: IntArray,
    val delaysMs: IntArray,
    val estimate: SizeEstimate,
    val steps: List<PlanStep>,
    val playMs: Long,
    val fitsBytes: Boolean,
)

/**
 * The size and duration planner: caps the long edge, the length and the number of frames, picks
 * frames to lower the rate, and shrinks until the size estimate fits, giving up the least important
 * thing first (frames down to [PlanLimits.minFps], then pixels down to [PlanLimits.minLongEdge], then
 * frames again).
 *
 * ## The estimate is honest about what it is
 * It is a model: bytes per painted pixel for each format and [ContentKind], fitted to this
 * package's own encoders on synthetic scenes (see `AnimEstimateCalibrationTest`), with a range
 * around it (about 0.5x to 2.5x for photos, wider for graphics). Nothing has been measured on real
 * photographs or real video. The only trustworthy number is the size of the file after it is made.
 */
object AnimPlanner {
    /** Fixed cost of a file: header, palette, trailer, and per frame its small headers. */
    private const val FILE_OVERHEAD = 800L
    private const val GIF_FRAME_OVERHEAD = 40L
    private const val APNG_FRAME_OVERHEAD = 80L

    /**
     * Bytes per painted pixel: [first] for a frame drawn whole, [delta] for the changed pixels of a
     * later frame; [low] and [high] scale the typical figure into the range it could fall in. The
     * PHOTO rates sit a little above what the synthetic scenes measured (a real photograph has more
     * fine detail than seeded noise over a gradient), the GRAPHICS rates sit at the flat synthetic
     * bars and are the least trustworthy: antialiased text or UI will be several times bigger, which
     * is why that range reaches [high] = 8.
     */
    private class Rate(val first: Double, val delta: Double, val low: Double, val high: Double)

    private fun rate(format: AnimFormat, kind: ContentKind): Rate = when (format) {
        AnimFormat.GIF -> if (kind == ContentKind.PHOTO) Rate(0.65, 0.60, 0.5, 2.5) else Rate(0.02, 0.025, 0.3, 8.0)
        AnimFormat.APNG -> if (kind == ContentKind.PHOTO) Rate(2.0, 2.0, 0.5, 2.5) else Rate(0.03, 0.04, 0.3, 8.0)
    }

    /**
     * The size a file of [frames] frames of [width] x [height] could come to. [changeFraction] is the
     * share of pixels that differ from one frame to the next (0 to 1; noise counts as change).
     * A GIF with see-through pixels is written frame by frame without deltas, so for one pass 1.
     */
    fun estimate(format: AnimFormat, kind: ContentKind, width: Int, height: Int, frames: Int, changeFraction: Double): SizeEstimate {
        require(width > 0 && height > 0 && frames >= 1)
        val r = rate(format, kind)
        val px = width.toLong() * height
        val perFrame = if (format == AnimFormat.GIF) GIF_FRAME_OVERHEAD else APNG_FRAME_OVERHEAD
        val typical = FILE_OVERHEAD + frames * perFrame + px * r.first +
            (frames - 1) * px * changeFraction.coerceIn(0.0, 1.0) * r.delta
        return SizeEstimate(typical.toLong(), (typical * r.low).toLong(), (typical * r.high).toLong())
    }

    /**
     * A better guess from a measured sample: [sampleBytes] is what the first [sampleFrames] frames
     * took (including the file's fixed cost), [totalFrames] is how many there will be. The first
     * frame is the expensive one, so it is counted whole and the rest by their average.
     */
    fun extrapolate(sampleBytes: Long, sampleFrames: Int, totalFrames: Int, firstFrameBytes: Long): Long {
        require(sampleFrames >= 2 && totalFrames >= sampleFrames) { "Needs a sample of at least two frames" }
        val laterAvg = (sampleBytes - firstFrameBytes).toDouble() / (sampleFrames - 1)
        return (firstFrameBytes + laterAvg * (totalFrames - 1)).toLong()
    }

    private class Timeline(val picks: IntArray, val startsMs: LongArray, val totalMs: Long, val important: BooleanArray? = null) {
        val size: Int get() = picks.size
        fun delays(): IntArray = IntArray(size) { i ->
            val end = if (i + 1 < size) startsMs[i + 1] else totalMs
            (end - startsMs[i]).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /** Keep [n] frames, the important ones first and the rest spread evenly; each stays up until the next kept one starts. */
        fun thin(n: Int): Timeline {
            val keep = if (important == null) FrameTiming.evenPicks(size, n) else FrameTiming.evenPicksKeeping(important, n)
            return Timeline(
                IntArray(keep.size) { picks[keep[it]] },
                LongArray(keep.size) { startsMs[keep[it]] },
                totalMs,
                important?.let { flags -> BooleanArray(keep.size) { flags[keep[it]] } },
            )
        }
    }

    fun plan(req: PlanRequest): AnimPlan {
        require(req.sourceWidth > 0 && req.sourceHeight > 0) { "The source has no size" }
        require(req.sourceDelaysMs.isNotEmpty()) { "The source has no frames" }
        val lim = req.limits
        val steps = ArrayList<PlanStep>()

        var (w, h) = FrameScaler.capLongEdge(req.sourceWidth, req.sourceHeight, lim.maxLongEdge)
        if (w != req.sourceWidth || h != req.sourceHeight) steps.add(PlanStep.SHRUNK)

        // The source's own timeline, trimmed to the length cap.
        var delays = req.sourceDelaysMs
        var total = delays.sumOf { it.toLong() }
        if (total > lim.maxDurationMs) {
            val kept = ArrayList<Int>()
            var t = 0L
            for (d in delays) {
                if (t >= lim.maxDurationMs) break
                kept.add(if (t + d > lim.maxDurationMs) (lim.maxDurationMs - t).toInt() else d)
                t += d
            }
            delays = kept.toIntArray()
            total = delays.sumOf { it.toLong() }
            steps.add(PlanStep.TRIMMED)
        }

        var timeline: Timeline = run {
            val target = req.targetFps
            if (target != null) {
                val rs = FrameTiming.resample(delays, target, collapse = true)
                if (rs.size < delays.size) steps.add(PlanStep.RATE_LOWERED)
                timelineOf(rs, req.keepFrames)
            } else {
                val starts = LongArray(delays.size)
                var t = 0L
                for (i in delays.indices) {
                    starts[i] = t
                    t += delays[i]
                }
                Timeline(IntArray(delays.size) { it }, starts, total, req.keepFrames?.let { keep -> BooleanArray(delays.size) { keep.getOrElse(it) { false } } })
            }
        }
        if (timeline.size > lim.maxFrames) {
            timeline = timeline.thin(lim.maxFrames)
            steps.add(PlanStep.FRAMES_THINNED)
        }

        // Fit the size cap: frames first (down to the rate floor), then pixels, then frames again.
        fun est() = estimate(req.format, req.kind, w, h, timeline.size, req.changeFraction)
        val floorFrames = max(2, (timeline.totalMs / 1000.0 * lim.minFps).roundToInt())
        var guard = 0
        var shrunkForSize = false
        var thinned = false
        while (est().typicalBytes > lim.maxBytes && guard++ < 200) {
            if (timeline.size > floorFrames) {
                timeline = timeline.thin(max(floorFrames, (timeline.size * 0.8).toInt()))
                thinned = true
            } else if (max(w, h) > lim.minLongEdge) {
                val (nw, nh) = FrameScaler.capLongEdge(w, h, max(lim.minLongEdge, (max(w, h) * 0.85).toInt()))
                if (nw == w && nh == h) break
                w = nw
                h = nh
                shrunkForSize = true
            } else if (timeline.size > 2) {
                timeline = timeline.thin(max(2, (timeline.size * 0.8).toInt()))
                thinned = true
            } else {
                break
            }
        }
        if (thinned && PlanStep.FRAMES_THINNED !in steps) steps.add(PlanStep.FRAMES_THINNED)
        if (shrunkForSize) steps.add(PlanStep.SHRUNK_FOR_SIZE)
        val finalEst = est()
        val fits = finalEst.typicalBytes <= lim.maxBytes
        if (!fits) steps.add(PlanStep.STILL_TOO_BIG)

        val outDelays = timeline.delays()
        val playMs = if (req.format == AnimFormat.GIF) FrameTiming.gifDelaysCs(outDelays).sumOf { it.toLong() } * 10
        else outDelays.sumOf { max(it, ApngEncoder.MIN_DELAY_MS).toLong() }
        return AnimPlan(w, h, timeline.picks, outDelays, finalEst, steps, playMs, fits)
    }

    private fun timelineOf(rs: Resampled, keepFrames: BooleanArray?): Timeline {
        val starts = LongArray(rs.size)
        var t = 0L
        for (i in 0 until rs.size) {
            starts[i] = t
            t += rs.delaysMs[i]
        }
        return Timeline(rs.picks, starts, t, keepFrames?.let { keep -> BooleanArray(rs.size) { keep.getOrElse(rs.picks[it]) { false } } })
    }
}
