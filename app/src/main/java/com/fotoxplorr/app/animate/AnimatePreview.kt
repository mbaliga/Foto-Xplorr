package com.fotoxplorr.app.animate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One picture of a preview: straight ARGB at the preview's size, and how long it stays up. */
class PreviewFrame(val argb: IntArray, val delayMs: Int)

/** A preview: [frames] all [width] x [height], opaque, with the timing of the real animation (thinned, never sped up). */
class PreviewSet(val width: Int, val height: Int, val frames: List<PreviewFrame>) {
    init {
        require(frames.isNotEmpty()) { "a preview has a frame" }
        require(frames.all { it.argb.size == width * height }) { "every preview frame is $width x $height" }
    }

    val delaysMs: IntArray get() = IntArray(frames.size) { frames[it].delayMs }
}

/** How a preview ended. */
sealed interface PreviewResult {
    class Ready(val set: PreviewSet) : PreviewResult

    /** [message] is the sentence for the person. */
    class Failed(val message: String) : PreviewResult
}

/**
 * The preview: the animation the request describes, made small and with few frames, so the person can
 * see timing, order and fades before the real file is made. It runs the same frame sources the job
 * does, so what it shows is what the file will be built from: a photo that is protected, locked or
 * unreadable ends it with the job's own sentence, and a photo is only ever read through the host
 * (TRAPS #33).
 *
 * What it is not: the file. The edge is [EDGE] and there are at most [MAX_FRAMES] frames (the
 * planner thins a long animation and each kept frame stays up until the next kept one starts, so the
 * length is right and the motion is coarser), colours are not reduced to a GIF palette, and nothing
 * is encoded. The screen says so.
 *
 * Reads are bounded: at most [MAX_FRAMES] frames of at most [EDGE] pixels are held, about 4 MB.
 */
object AnimatePreview {
    /** The long edge the preview is drawn at. */
    const val EDGE = 240

    /** The most frames a preview holds. */
    const val MAX_FRAMES = 24

    /** Returns a [PreviewResult]; throws only [CancellationException]. */
    suspend fun make(request: AnimateRequest, host: AnimateHost): PreviewResult {
        val input = request.input
        if (!input.photos.all(host::mayExport) || !input.videos.all(host::mayUseVideo)) {
            return PreviewResult.Failed(AnimateCopy.PROTECTED)
        }
        if (input is AnimateInput.Stills && input.items.size < 2) return PreviewResult.Failed(AnimateCopy.NEED_PICTURES)
        val background = request.background or (0xFF shl 24)
        val source: FrameSource = when (input) {
            is AnimateInput.Stills -> StillsFrameSource(host, input, request.fit, background, EDGE)
            is AnimateInput.Clip -> VideoFrameSource(host, input)
            is AnimateInput.Existing -> AnimatedFileSource(host, input.asset)
        }
        return try {
            val info = source.open()
            val plan = AnimPlanner.plan(
                PlanRequest(
                    sourceWidth = info.width,
                    sourceHeight = info.height,
                    sourceDelaysMs = info.delaysMs,
                    format = AnimFormat.GIF,
                    kind = info.kind,
                    changeFraction = info.changeFraction,
                    limits = PlanLimits(
                        maxLongEdge = EDGE,
                        maxFrames = MAX_FRAMES,
                        maxBytes = Long.MAX_VALUE / 4,
                        maxDurationMs = request.limits.maxDurationMs,
                        minLongEdge = 1,
                    ),
                    keepFrames = info.keepFrames,
                ),
            )
            val frames = ArrayList<PreviewFrame>(plan.picks.size)
            for (k in plan.picks.indices) {
                currentCoroutineContext().ensureActive()
                val px = source.frame(plan.picks[k], plan.width, plan.height)
                frames.add(PreviewFrame(FrameOps.flatten(px, background), plan.delaysMs[k].coerceAtLeast(1)))
            }
            PreviewResult.Ready(PreviewSet(plan.width, plan.height, frames))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnimSourceException) {
            PreviewResult.Failed(e.message ?: AnimateScreenCopy.PREVIEW_FAILED)
        } catch (e: OutOfMemoryError) {
            PreviewResult.Failed(AnimateCopy.OUT_OF_MEMORY)
        } catch (e: Exception) {
            PreviewResult.Failed(AnimateScreenCopy.PREVIEW_FAILED)
        } finally {
            source.close()
        }
    }
}

/** Which frame of a looping preview is up at a moment. Pure, so the player's timing can be tested without a screen. */
object PreviewClock {
    /** The index of the frame showing [elapsedMs] into a loop of [delaysMs], wrapping round for ever. */
    fun frameAt(delaysMs: IntArray, elapsedMs: Long): Int {
        require(delaysMs.isNotEmpty()) { "no frames" }
        val total = delaysMs.sumOf { it.coerceAtLeast(1).toLong() }
        var t = ((elapsedMs % total) + total) % total
        for (i in delaysMs.indices) {
            val d = delaysMs[i].coerceAtLeast(1)
            if (t < d) return i
            t -= d
        }
        return delaysMs.lastIndex
    }

    /** How long one loop lasts. */
    fun loopMs(delaysMs: IntArray): Long = delaysMs.sumOf { it.coerceAtLeast(1).toLong() }

    /** [elapsedMs] advanced by a frame that took [frameNanos], where a long gap (the app was away) counts as at most [MAX_STEP_MS]. */
    fun advance(elapsedMs: Long, frameNanos: Long): Long = elapsedMs + (frameNanos / 1_000_000L).coerceIn(0L, MAX_STEP_MS)

    /** The longest single step: a stalled frame must not skip the preview ahead. */
    const val MAX_STEP_MS = 250L
}
