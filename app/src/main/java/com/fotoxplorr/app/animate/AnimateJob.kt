package com.fotoxplorr.app.animate

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What to make: the input, the file type, and the ceilings the planner works within. */
class AnimateRequest(
    val input: AnimateInput,
    val output: AnimOutput,
    /** The ceilings; [AnimateDefaults.limits] for the output when not given. */
    val limits: PlanLimits = AnimateDefaults.limits(output),
    /** Lower the rate to this many frames a second; null keeps the source's own timing. For a video it is the file's rate (default: a clip's own sampling rate, else 30). */
    val targetFps: Double? = null,
    /** How a picture of another shape than the first is placed on the canvas (slideshows only). */
    val fit: FitMode = FitMode.CONTAIN,
    /** What see-through pixels are drawn over, and the bars of a [FitMode.CONTAIN]. Opaque; the alpha byte is ignored. */
    val background: Int = OPAQUE_BLACK,
    /** Keep see-through pixels of an animated source (GIF and APNG only; a video and a slideshow are always opaque). */
    val keepTransparency: Boolean = false,
    /** Total plays, 0 for ever; null means for ever, except that an existing animation keeps its own. */
    val plays: Int? = null,
    /** GIF knobs; null picks them for the input (a small delta tolerance for a video clip, which helps noisy video a lot). */
    val gif: GifOptions? = null,
    val apng: ApngOptions = ApngOptions(),
) {
    companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}

/** Ceilings for each output when the person has not chosen. */
object AnimateDefaults {
    fun limits(output: AnimOutput): PlanLimits = when (output) {
        AnimOutput.GIF, AnimOutput.APNG -> PlanLimits()
        // A video is held to a size cap too, but its size follows its bitrate, not the planner's GIF/PNG rates.
        AnimOutput.VIDEO -> PlanLimits(maxLongEdge = 720, maxFrames = 600, maxBytes = 50L * 1024 * 1024, maxDurationMs = 60_000L, minLongEdge = 160)
    }
}

/** How an animation job ended. */
sealed interface AnimateOutcome {
    /**
     * The file exists at [uri]. [steppedDown] is true when it is smaller than the plan first made
     * because memory or the size cap said so; [notes] are one sentence each for what was given up
     * or what was wrong with the source, in the order it happened.
     */
    class Done(
        val uri: String,
        val output: AnimOutput,
        val width: Int,
        val height: Int,
        val frames: Int,
        val bytes: Long,
        val playMs: Long,
        val steppedDown: Boolean,
        val notes: List<String>,
    ) : AnimateOutcome {
        val message: String get() = AnimateCopy.SAVED
    }

    /** [message] is the sentence for the person. */
    class Failed(val message: String) : AnimateOutcome
}

/** A [AnimateOutcome.Failed] as the failure of a [JobRunner] job. */
class AnimateFailure(message: String) : Exception(message)

/**
 * The Android seam of the animated-picture maker: the one function that takes a person's request
 * (stills, a stretch of video, or an existing animated file; GIF, APNG or MP4 out) and runs the pure
 * core ([AnimPlanner], [FrameScaler], [GifStreamEncoder], [ApngEncoder]) over frames read from a
 * [FrameSource], and the narrow interfaces it needs from the phone ([AnimateHost], [AnimateSink],
 * [VideoMaker]). Everything it says to the person is an [AnimateCopy] sentence; it never throws a
 * failure, it returns one.
 *
 * ## The steps
 * 1. **Refuse** fewer than two stills and any photo or video the host says no to (protected,
 *    sensitive, locked since the picker opened), before a pixel is read; and a video output on a
 *    phone with no H.264 encoder.
 * 2. **Open** the source: its natural size and the delay of each of its frames. A file that cannot be
 *    read, or is not an animation, is a sentence.
 * 3. **Plan** ([AnimPlanner.plan]): the size, the frames to use and how long each stays, within the
 *    request's ceilings.
 * 4. **Make** the file in this app's cache. GIF is written frame by frame as frames are read, so only
 *    one frame (and a few for the palette) is ever held; APNG is encoded from all frames at once, so it
 *    is held to a share of the Java heap ([AnimChoices.sequenceFits]); a video's frames are written to
 *    picture files and handed to the [VideoMaker].
 * 5. **Step down.** An [OutOfMemoryError], or a finished file over the size cap, takes the next
 *    smaller plan ([AnimLadder]) and redoes step 4, up to [AnimLadder.MAX_RUNGS] times.
 * 6. **Publish** through [AnimateSink.publish], which asks [AnimateHost] AGAIN right before it creates
 *    the file (TRAPS #33: a folder can be locked while a long job runs).
 *
 * ## What this does not know
 * The size estimate behind step 3 was fitted on synthetic scenes; the real size is only known after
 * step 4, which is why step 5 measures it. Nothing here has run on a device, so no timing on a phone is
 * known. A video clip is sampled by the platform's retriever, whose speed and rotation behaviour are
 * unmeasured ([RetrieverVideoReader]).
 *
 * ## Cancel and process death
 * Cancelling the coroutine stops the job between frames and between the encoder's stages, leaves no
 * MediaStore row (the sink makes one only after the file is complete and a final look has passed, and
 * deletes it on a cancel that arrives later) and returns by throwing [CancellationException] so
 * [JobRunner] reports it as cancelled. If the process is killed nothing has been saved: the working
 * files are in the cache and the next run's sweep ([AnimateWriter.pruneStaging]) deletes them. What
 * the person had chosen is the screen's to remember, not this job's.
 */
object AnimateJob {

    /**
     * Run the whole job. Returns [AnimateOutcome.Done] or [AnimateOutcome.Failed]; throws only
     * [CancellationException]. [onProgress] gets 0..1, rising. [video] may be null if the output is
     * never a video. [heapBytes] is the Java heap the job may reason about (a test sets it small).
     */
    suspend fun run(
        request: AnimateRequest,
        host: AnimateHost,
        sink: AnimateSink,
        video: VideoMaker? = null,
        heapBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
        onProgress: (Float) -> Unit = {},
    ): AnimateOutcome = withContext(Dispatchers.Default) {
        try {
            Steps(request, host, sink, video, heapBytes, Progress(onProgress)).go()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnimSourceProtectedException) {
            AnimateOutcome.Failed(AnimateCopy.PROTECTED)
        } catch (e: AnimSourceException) {
            AnimateOutcome.Failed(e.message ?: AnimateCopy.FAILED)
        } catch (e: AnimateProtectedException) {
            AnimateOutcome.Failed(AnimateCopy.PROTECTED)
        } catch (e: VideoMakeException) {
            AnimateOutcome.Failed(e.message ?: AnimateCopy.VIDEO_FAILED)
        } catch (e: IOException) {
            AnimateOutcome.Failed(if (isOutOfSpace(e)) AnimateCopy.NO_SPACE else AnimateCopy.SAVE_FAILED)
        } catch (e: OutOfMemoryError) {
            AnimateOutcome.Failed(AnimateCopy.OUT_OF_MEMORY)
        } catch (e: Exception) {
            AnimateOutcome.Failed(AnimateCopy.FAILED)
        }
    }

    /**
     * Start [run] as a [JobRunner] job (progress, cancel, the activity shade). The outcome arrives on
     * [JobRunner.outcomes] as an [AnimateOutcome.Done] result, or as an [AnimateFailure] carrying the
     * sentence for the person, or as a cancellation.
     */
    fun launch(runner: JobRunner, request: AnimateRequest, host: AnimateHost, sink: AnimateSink, video: VideoMaker? = null): JobHandle =
        runner.launch(AnimateCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, host, sink, video, onProgress = { onProgress(it) })) {
                is AnimateOutcome.Done -> Result.success(outcome)
                is AnimateOutcome.Failed -> Result.failure(AnimateFailure(outcome.message))
            }
        }

    /**
     * True when [failure] or something it was caused by is the phone running out of space: the
     * sink's own [AnimateNoSpaceException], or an I/O error whose text is the system's
     * `ENOSPC (No space left on device)`. Anything else is a plain save failure.
     */
    internal fun isOutOfSpace(failure: Throwable?): Boolean {
        var t = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is AnimateNoSpaceException) return true
            val text = t.message.orEmpty()
            if (t is IOException && (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true))) return true
            t = t.cause
        }
        return false
    }

    // Where each stage ends on the 0..1 progress bar.
    private const val OPEN_END = 0.04f
    private const val PUBLISH_START = 0.92f

    /** The bar moves in steps of at least this much, so a fast loop does not flood the shade. */
    private const val PROGRESS_STEP = 0.004f

    /** A rising progress report; values below the last one reported (a retry on a smaller plan) are held. */
    private class Progress(private val sink: (Float) -> Unit) {
        private var last = -1f

        @Synchronized
        fun report(value: Float) {
            val v = value.coerceIn(0f, 1f)
            if (v >= last + PROGRESS_STEP || (v >= 1f && last < 1f) || last < 0f) {
                last = v
                sink(v)
            }
        }
    }

    /** The cache files one attempt makes, so every exit can remove them. */
    private class Staging(val directory: File) {
        var file: File? = null
        var frames: File? = null

        fun clear() {
            file?.delete()
            file = null
            frames?.deleteRecursively()
            frames = null
        }
    }

    private class Steps(
        val request: AnimateRequest,
        val host: AnimateHost,
        val sink: AnimateSink,
        val video: VideoMaker?,
        val heapBytes: () -> Long,
        val progress: Progress,
    ) {
        private val input = request.input
        private val output = request.output
        private val background = request.background or (0xFF shl 24)

        private fun allowed(): Boolean = input.photos.all(host::mayExport) && input.videos.all(host::mayUseVideo)

        private fun sourceFor(): FrameSource = when (input) {
            is AnimateInput.Stills -> StillsFrameSource(host, input, request.fit, background, request.limits.maxLongEdge)
            is AnimateInput.Clip -> VideoFrameSource(host, input)
            is AnimateInput.Existing -> AnimatedFileSource(host, input.asset)
        }

        /** Everything that happens to the planned frames once the plan is set. */
        private class Made(val file: File, val width: Int, val height: Int, val frames: Int, val playMs: Long)

        suspend fun go(): AnimateOutcome {
            progress.report(0f)
            if (input is AnimateInput.Stills && input.items.size < 2) return AnimateOutcome.Failed(AnimateCopy.NEED_PICTURES)
            if (!allowed()) return AnimateOutcome.Failed(AnimateCopy.PROTECTED)
            if (output.isVideo && (video == null || !host.canEncodeVideo())) return AnimateOutcome.Failed(AnimateCopy.NO_VIDEO_ENCODER)
            val source = sourceFor()
            val staging = Staging(sink.stagingDirectory())
            try {
                val info = source.open()
                progress.report(OPEN_END)
                return plan(source, info, staging)
            } finally {
                source.close()
                staging.clear()
            }
        }

        private suspend fun plan(source: FrameSource, info: SourceInfo, staging: Staging): AnimateOutcome {
            val notes = ArrayList<String>()
            AnimateCopy.decodeProblem(info.problem)?.let { notes.add(it) }
            val keepAlpha = request.keepTransparency && !output.isVideo && info.transparency != Transparency.NONE
            val gifClear = keepAlpha && output == AnimOutput.GIF && info.transparency == Transparency.CLEAR
            var limits = request.limits
            var rung = 0
            var stepped = false
            var firstPlanSteps: List<PlanStep>? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val plan = AnimPlanner.plan(
                    PlanRequest(
                        sourceWidth = info.width,
                        sourceHeight = info.height,
                        sourceDelaysMs = info.delaysMs,
                        format = output.planFormat,
                        kind = info.kind,
                        // A GIF with clear pixels is written whole, frame by frame: nothing is "unchanged".
                        changeFraction = if (gifClear) 1.0 else info.changeFraction,
                        targetFps = request.targetFps,
                        // A video's size follows its bitrate; the planner's GIF/PNG rates must not thin its frames.
                        limits = if (output.isVideo) withBytes(limits, Long.MAX_VALUE / 4) else limits,
                    ),
                )
                if (firstPlanSteps == null) {
                    firstPlanSteps = plan.steps
                    plan.steps.filter { it != PlanStep.STILL_TOO_BIG }.mapTo(notes) { AnimateCopy.planStep(it) }
                }
                val (w, h) = if (output.isVideo) VideoTimeline.evenSize(plan.width, plan.height) else plan.width to plan.height

                // An APNG is encoded from every frame at once: if they would not fit the heap, go smaller before trying.
                if (output == AnimOutput.APNG && !AnimChoices.sequenceFits(plan.picks.size, w, h, heapBytes())) {
                    limits = AnimLadder.next(limits, plan, null, rung) ?: return AnimateOutcome.Failed(AnimateCopy.OUT_OF_MEMORY)
                    stepped = true
                    rung++
                    notes.addStep(AnimateCopy.MEMORY_STEP)
                    continue
                }

                val made = try {
                    make(source, info, plan, w, h, gifClear, keepAlpha, staging)
                } catch (e: OutOfMemoryError) {
                    staging.clear()
                    if (rung >= AnimLadder.MAX_RUNGS) return AnimateOutcome.Failed(AnimateCopy.OUT_OF_MEMORY)
                    limits = AnimLadder.next(limits, plan, null, rung) ?: return AnimateOutcome.Failed(AnimateCopy.OUT_OF_MEMORY)
                    stepped = true
                    rung++
                    notes.addStep(AnimateCopy.MEMORY_STEP)
                    continue
                }

                val bytes = made.file.length()
                if (bytes > request.limits.maxBytes) {
                    staging.clear()
                    if (rung >= AnimLadder.MAX_RUNGS) return AnimateOutcome.Failed(AnimateCopy.STILL_OVER_LIMIT)
                    limits = AnimLadder.next(limits, plan, bytes, rung) ?: return AnimateOutcome.Failed(AnimateCopy.STILL_OVER_LIMIT)
                    stepped = true
                    rung++
                    notes.addStep(AnimateCopy.SIZE_STEP)
                    continue
                }

                currentCoroutineContext().ensureActive()
                if (!allowed()) return AnimateOutcome.Failed(AnimateCopy.PROTECTED)
                val uri = sink.publish(made.file, output, info.dateTakenMillis, info.name, ::allowed) { progress.report(PUBLISH_START + (1f - PUBLISH_START) * it) }
                progress.report(1f)
                return AnimateOutcome.Done(uri, output, made.width, made.height, made.frames, bytes, made.playMs, stepped, notes)
            }
        }

        private fun MutableList<String>.addStep(note: String) {
            if (note !in this) add(note)
        }

        private fun withBytes(l: PlanLimits, bytes: Long) =
            PlanLimits(l.maxLongEdge, l.maxFrames, bytes, l.maxDurationMs, l.minLongEdge, l.minFps)

        /** One frame of the plan, read, placed on the canvas and drawn opaque unless transparency is kept. */
        private suspend fun read(source: FrameSource, plan: AnimPlan, k: Int, w: Int, h: Int, keepAlpha: Boolean): IntArray {
            currentCoroutineContext().ensureActive()
            val px = source.frame(plan.picks[k], w, h)
            return if (keepAlpha) px else FrameOps.flatten(px, background)
        }

        private suspend fun make(
            source: FrameSource,
            info: SourceInfo,
            plan: AnimPlan,
            w: Int,
            h: Int,
            gifClear: Boolean,
            keepAlpha: Boolean,
            staging: Staging,
        ): Made {
            val n = plan.picks.size
            val plays = request.plays ?: (info.plays.takeIf { input is AnimateInput.Existing } ?: 0)
            val dir = staging.directory
            return when (output) {
                AnimOutput.GIF -> {
                    val file = File.createTempFile("anim-", ".gif", dir).also { staging.file = it }
                    val options = request.gif ?: GifOptions(deltaTolerance = if (input is AnimateInput.Clip) CLIP_DELTA_TOLERANCE else 0)
                    // A file palette from a few frames spread over the animation, read once and reused when the loop reaches them.
                    val samples = HashMap<Int, IntArray>()
                    if (options.paletteMode != PaletteMode.LOCAL) {
                        for (k in AnimChoices.paletteSample(n)) samples[k] = read(source, plan, k, w, h, keepAlpha)
                    }
                    FileOutputStream(file).buffered().use { out ->
                        val enc = GifStreamEncoder(w, h, plays, gifClear, options, out, samples.toSortedMap().values.toList())
                        for (k in 0 until n) {
                            val px = samples.remove(k) ?: read(source, plan, k, w, h, keepAlpha)
                            enc.addFrame(px, plan.delaysMs[k])
                            progress.report(OPEN_END + (PUBLISH_START - OPEN_END) * (k + 1) / n)
                        }
                        enc.finish()
                    }
                    Made(file, w, h, n, FrameTiming.gifDelaysCs(plan.delaysMs).sumOf { it.toLong() } * 10)
                }
                AnimOutput.APNG -> {
                    val frames = ArrayList<AnimFrame>(n)
                    for (k in 0 until n) {
                        frames.add(AnimFrame(read(source, plan, k, w, h, keepAlpha), plan.delaysMs[k]))
                        progress.report(OPEN_END + (APNG_FRAMES_END - OPEN_END) * (k + 1) / n)
                    }
                    val job = currentCoroutineContext()[Job]
                    val bytes = ApngEncoder.encode(AnimSequence(w, h, frames, plays), request.apng) { done, total ->
                        progress.report(APNG_FRAMES_END + (PUBLISH_START - APNG_FRAMES_END) * done / total)
                        // A cancel while encoding is noticed at the next frame the encoder reports.
                        job?.ensureActive()
                    }
                    val file = File.createTempFile("anim-", ".png", dir).also { staging.file = it }
                    FileOutputStream(file).use { it.write(bytes) }
                    Made(file, w, h, n, plan.delaysMs.sumOf { maxOf(it, ApngEncoder.MIN_DELAY_MS).toLong() })
                }
                AnimOutput.VIDEO -> {
                    val maker = checkNotNull(video)
                    val fps = VideoTimeline.fpsFor(request.targetFps ?: (input as? AnimateInput.Clip)?.fps)
                    val frameDir = File(dir, "frames-${System.nanoTime()}").also { staging.frames = it }
                    if (!frameDir.mkdirs() && !frameDir.isDirectory) throw IOException("Could not prepare animation storage")
                    val ticks = VideoTimeline.ticks(plan.delaysMs, fps)
                    val files = ArrayList<VideoFrameFile>(n)
                    for (k in 0 until n) {
                        val px = read(source, plan, k, w, h, keepAlpha = false)
                        val f = File(frameDir, "f%05d.jpg".format(k))
                        maker.writeFrame(px, w, h, f)
                        files.add(VideoFrameFile(f, ticks[k]))
                        progress.report(OPEN_END + (VIDEO_FRAMES_END - OPEN_END) * (k + 1) / n)
                    }
                    val file = File.createTempFile("anim-", ".mp4", dir).also { staging.file = it }
                    file.delete()
                    maker.make(files, w, h, fps, VideoTimeline.bitrate(w, h, fps), file) {
                        progress.report(VIDEO_FRAMES_END + (PUBLISH_START - VIDEO_FRAMES_END) * it)
                    }
                    // The frame pictures are only needed until the file exists.
                    frameDir.deleteRecursively()
                    staging.frames = null
                    Made(file, w, h, n, ticks.sumOf { it.toLong() } * 1000L / fps)
                }
            }
        }
    }

    /** The delta tolerance given to a video clip's GIF: video noise is what makes a GIF big (see [GifOptions]). */
    private const val CLIP_DELTA_TOLERANCE = 6

    private const val APNG_FRAMES_END = 0.60f
    private const val VIDEO_FRAMES_END = 0.45f
}
