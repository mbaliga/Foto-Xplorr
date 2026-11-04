package com.fotoxplorr.app.viewer

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Frame-by-frame stepping for the video transport, as pure arithmetic. ExoPlayer has no "next
 * frame" call, so a step is a seek to the neighbouring frame's presentation time with exact seek
 * parameters; this works out that time from the frame rate WITHOUT drift.
 *
 * Why an index and not `position + frameDuration`: at 29.97 or 59.94 fps a frame lasts 33.37 /
 * 16.68 ms, and the player reports whole milliseconds. Adding a rounded 33 ms every step falls
 * behind the real frame grid, and at 120 fps a rounded 8 ms from 8 ms can land back on the frame
 * it left. Counting frames (`index = round(position / frame)`) and converting back
 * (`round(index * frame)`) tracks the true grid and always moves, in either direction.
 *
 * What is unverified: that the decoder shows the frame at the requested time (a seek to a
 * frame time lands on a key frame's neighbours differently per codec) and that the file's declared
 * rate is its real one (variable-rate phone video). Both are device checks.
 */
object FrameStep {
    /** Used when the file declares no usable rate; [FrameClock.estimated] says so. */
    const val FALLBACK_FPS = 30f
    private const val MIN_FPS = 1f
    private const val MAX_FPS = 240f

    /** The frame clock of a clip: how long a frame lasts, and whether that is a guess. */
    data class FrameClock(val frameMs: Double, val estimated: Boolean)

    fun clockFor(declaredFps: Float?): FrameClock {
        val ok = declaredFps != null && declaredFps.isFinite() && declaredFps in MIN_FPS..MAX_FPS
        val fps = if (ok) declaredFps!! else FALLBACK_FPS
        return FrameClock(1000.0 / fps, estimated = !ok)
    }

    /** Which frame [positionMs] is on (nearest). */
    fun indexOf(positionMs: Long, clock: FrameClock): Long =
        floor(positionMs.coerceAtLeast(0L) / clock.frameMs + 0.5).toLong()

    private fun timeOf(index: Long, clock: FrameClock): Long = (index * clock.frameMs).roundToLong()

    /**
     * The time to seek to for a step of [frames] (negative = back) from [positionMs]. Clamped to
     * 0..[durationMs] (an unknown duration, 0 or less, clamps only at 0). Always different from
     * [positionMs] unless the clamp forces it (already at the start or the end).
     */
    fun targetMs(positionMs: Long, frames: Int, durationMs: Long, clock: FrameClock): Long {
        if (frames == 0) return positionMs
        val from = indexOf(positionMs, clock)
        var target = timeOf((from + frames).coerceAtLeast(0L), clock)
        // Guarantee progress in the requested direction where there is room.
        if (frames > 0 && target <= positionMs) target = positionMs + 1
        if (frames < 0 && target >= positionMs && positionMs > 0) target = positionMs - 1
        val limit = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, limit)
    }

    /** Whether a step forward can still move: false on the last frame of a known-length clip. */
    fun canStepForward(positionMs: Long, durationMs: Long, clock: FrameClock): Boolean =
        durationMs <= 0L || positionMs + clock.frameMs / 2 < durationMs

    fun canStepBack(positionMs: Long): Boolean = positionMs > 0L

    /**
     * Press-and-hold repeat: one step on the press, then the gap between steps shrinks the longer
     * the finger stays (a slow crawl first so one frame is one tap, then a quick scan). Pure:
     * [heldMs] is how long the button has been down at the moment the next step is due.
     */
    fun repeatDelayMs(heldMs: Long): Long = when {
        heldMs < HOLD_BEFORE_REPEAT_MS -> HOLD_BEFORE_REPEAT_MS - heldMs
        heldMs < 1_500L -> 140L
        heldMs < 3_000L -> 70L
        else -> 35L
    }

    /** A press shorter than this is one step; longer starts repeating. */
    const val HOLD_BEFORE_REPEAT_MS = 450L

    object Copy {
        const val STEP_BACK = "Previous frame"
        const val STEP_FORWARD = "Next frame"
        const val ESTIMATED = "This clip does not say its frame rate, so a step is about one thirtieth of a second."
    }
}
