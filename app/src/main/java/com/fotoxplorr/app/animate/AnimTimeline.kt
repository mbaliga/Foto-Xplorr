package com.fotoxplorr.app.animate

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One entry of a slideshow's timeline: still [from] held (when [to] is -1) or mixed [mix] of the way
 * to still [to].
 */
internal class StillStep(val from: Int, val to: Int, val mix: Double)

/** The timeline of a slideshow with optional cross-fades, as pure data. */
internal class StillsTimeline(val steps: List<StillStep>, val delaysMs: IntArray) {
    companion object {
        /**
         * [holdMs] is how long each still stays up. With [transition] each hold is followed by
         * [StillTransition.frames] in-betweens to the next still (and, with `loop`, the last still
         * fades back to the first), each [StillTransition.frameMs] long. A still is ONE frame with a
         * long delay, never repeated. Mixes are the eased fractions [CrossFade.between] uses.
         */
        fun build(holdMs: IntArray, transition: StillTransition?): StillsTimeline {
            require(holdMs.isNotEmpty()) { "Nothing to show" }
            val steps = ArrayList<StillStep>()
            val delays = ArrayList<Int>()
            val n = holdMs.size
            val frames = transition?.frames ?: 0
            for (i in 0 until n) {
                steps.add(StillStep(i, -1, 0.0))
                delays.add(holdMs[i])
                val last = i == n - 1
                if (frames == 0 || transition == null) continue
                if (last && !(transition.loop && n > 1)) continue
                val next = (i + 1) % n
                for (k in 1..frames) {
                    steps.add(StillStep(i, next, transition.easing.apply(k / (frames + 1.0))))
                    delays.add(transition.frameMs)
                }
            }
            return StillsTimeline(steps, delays.toIntArray())
        }
    }
}

/** How a video clip is sampled: [fps] ticks a second over [startMs] to [endMs]. */
internal class ClipTimeline(val startMs: Long, val delaysMs: IntArray, private val offsetsMs: LongArray) {
    /** The time in the video to read for tick [index]: the start of the tick. */
    fun timeMs(index: Int): Long = startMs + offsetsMs[index]

    companion object {
        /** More ticks than this in one clip is cut off; the planner thins to far fewer anyway. */
        const val MAX_TICKS = 3_600

        fun build(startMs: Long, endMs: Long, fps: Double): ClipTimeline {
            require(endMs > startMs && fps > 0.0)
            val length = endMs - startMs
            // One "source frame" the length of the clip, cut into ticks with the exact tick times (no drift).
            val ticks = FrameTiming.resample(intArrayOf(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), fps, collapse = false)
            val count = min(ticks.size, MAX_TICKS)
            val delays = ticks.delaysMs.copyOf(count)
            val offsets = LongArray(count)
            var t = 0L
            for (i in 0 until count) {
                offsets[i] = t
                t += delays[i]
            }
            return ClipTimeline(startMs, delays, offsets)
        }
    }
}

/**
 * The shape of a video made from frames: each planned frame becomes one image item that lasts a
 * whole number of ticks of the file's rate, so the file has exactly the frames it should.
 */
internal object VideoTimeline {
    const val DEFAULT_FPS = 30
    const val MIN_FPS = 1
    const val MAX_FPS = 60

    /** The whole frames-a-second the video is written at: [target] rounded and kept in range, or the default. */
    fun fpsFor(target: Double?): Int = (target ?: DEFAULT_FPS.toDouble()).roundToInt().coerceIn(MIN_FPS, MAX_FPS)

    /**
     * How many ticks of [fps] each of [delaysMs] lasts: the cumulative end time rounded to a tick, so
     * the total stays within one tick of the real length however the delays round, and never fewer
     * than one (an item must make a frame).
     */
    fun ticks(delaysMs: IntArray, fps: Int): IntArray {
        val out = IntArray(delaysMs.size)
        var elapsedMs = 0L
        var spent = 0L
        for (i in delaysMs.indices) {
            elapsedMs += delaysMs[i]
            val end = Math.round(elapsedMs * fps / 1000.0)
            out[i] = max(1L, end - spent).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            spent += out[i]
        }
        return out
    }

    /**
     * Microseconds a run of [ticks] ticks of [fps] lasts, rounded UP: Transformer makes
     * `duration x rate` frames for an image and a figure a hair under a whole tick must not lose one.
     */
    fun durationUs(ticks: Int, fps: Int): Long = (ticks * 1_000_000L + fps - 1) / fps

    /** The size rounded DOWN to even numbers (H.264 wants even sides), at least 2. */
    fun evenSize(width: Int, height: Int): Pair<Int, Int> = max(2, width and 1.inv()) to max(2, height and 1.inv())

    /** A bitrate for a [width] x [height] picture at [fps], about 0.15 bit a pixel, at least 400 kbit/s. A guess, not a tuned figure. */
    fun bitrate(width: Int, height: Int, fps: Int): Int =
        (width.toLong() * height * fps * 15 / 100).coerceIn(400_000L, 20_000_000L).toInt()
}

/**
 * The way down when a plan does not fit: memory ran out, or the finished file came out over the
 * size cap. Each rung makes the picture smaller and, after the first, thins the frames, until
 * nothing more can be given up.
 */
internal object AnimLadder {
    const val MIN_EDGE = 64
    const val MAX_RUNGS = 6

    private const val SHRINK = 0.8
    private const val THIN = 0.75
    private const val FIT_MARGIN = 0.92
    private const val BEST_SHRINK = 0.5

    /**
     * The limits for the next, smaller attempt, or null when this one is as small as it goes.
     * [plan] is the attempt that failed. [measuredBytes] is the size it came out at when the trouble
     * was size (null when it was memory): the picture is then shrunk by about the square root of how
     * far over it was (bytes follow area, roughly), with a margin. From the second rung on the
     * frames are thinned too.
     */
    fun next(limits: PlanLimits, plan: AnimPlan, measuredBytes: Long?, rung: Int): PlanLimits? {
        val edge = max(plan.width, plan.height)
        val frames = plan.picks.size
        val edgeFactor = if (measuredBytes != null && measuredBytes > limits.maxBytes) {
            (sqrt(limits.maxBytes.toDouble() / measuredBytes) * FIT_MARGIN).coerceIn(BEST_SHRINK, SHRINK)
        } else {
            SHRINK
        }
        val newEdge = max(MIN_EDGE, (edge * edgeFactor).toInt())
        val thin = measuredBytes == null || rung >= 1
        val newFrames = if (thin) max(2, (frames * THIN).toInt()) else frames
        val shrinks = newEdge < edge
        val thins = newFrames < frames
        if (!shrinks && !thins) return null
        return PlanLimits(
            maxLongEdge = min(newEdge, limits.maxLongEdge),
            maxFrames = min(newFrames, limits.maxFrames),
            maxBytes = limits.maxBytes,
            maxDurationMs = limits.maxDurationMs,
            minLongEdge = limits.minLongEdge,
            minFps = limits.minFps,
        )
    }
}

/** Small pure choices the job makes. */
internal object AnimChoices {

    /**
     * Which of [count] picked frames to read first and keep, to build a file palette from: up to
     * [max] spread evenly, first and last included. The job keeps them so each is read once.
     */
    fun paletteSample(count: Int, max: Int = 8): IntArray = FrameTiming.evenPicks(count, min(count, max))

    /**
     * The long edge a still is read at so a canvas of [canvasW] x [canvasH] can be filled from it:
     * the canvas's own long edge for [FitMode.CONTAIN] and [FitMode.STRETCH], twice that for
     * [FitMode.COVER] (a different shape may need more of it), and more again when [cropFraction]
     * (the share of the picture a saved edit keeps, 0 to 1) cuts the picture down. Never above [CEILING].
     */
    fun stillEdge(canvasW: Int, canvasH: Int, mode: FitMode, cropFraction: Double = 1.0): Int {
        val base = max(canvasW, canvasH) * (if (mode == FitMode.COVER) 2 else 1)
        val widened = base / cropFraction.coerceIn(0.1, 1.0)
        return min(CEILING.toDouble(), widened).toInt().coerceAtLeast(1)
    }

    /** The largest edge a still is read at, the editor's own preview ceiling. */
    const val CEILING = 2048

    /**
     * Whether the frames of an APNG ([frames] of [width] x [height], 4 bytes a pixel) fit in the
     * share of the Java heap ([heapBytes]) the job allows itself, [HEAP_SHARE]. An APNG is encoded
     * from the whole sequence, unlike a GIF, so this is what limits it.
     */
    fun sequenceFits(frames: Int, width: Int, height: Int, heapBytes: Long): Boolean =
        frames.toLong() * width * height * 4 <= heapBytes * HEAP_SHARE

    /** The share of the heap the held frames may take; the rest is the encoder's buffers, the app and the collector. A guess. */
    const val HEAP_SHARE = 0.3
}
