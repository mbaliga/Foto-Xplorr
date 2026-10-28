package com.fotoxplorr.app.reel

import androidx.media3.common.util.UnstableApi
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What the person asked for. */
class ReelRequest(
    val project: ReelProject,
    /** Keep the sound of video clips. Off by default: a clip is muted. Ignored when [withoutSound]. */
    val keepClipSound: Boolean = false,
    /** The frame's short side to start from: 1080, 720 or 480. The job goes down from here when the phone cannot do it. */
    val startShortSide: Int = ReelAttempts.DEFAULT_SHORT_SIDE,
    /** False for plain cuts everywhere (a hand-over chosen in the project is then not drawn). */
    val transitions: Boolean = true,
    /** The person agreed to a reel with no sound at all, after being told the sound could not be used. */
    val withoutSound: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** The shape of the picture a staged file must have, see [ReelHost.stagePhoto]. */
class StageSpec(val frame: ReelFrame, val fit: ReelFit, val motion: ReelMotion)

/** What the job learned about the soundtrack file before using it. */
sealed interface SoundtrackProbe {
    /** The file has a sound track this phone can decode; [durationMs] is 0 when the file does not say. */
    class Usable(val durationMs: Long) : SoundtrackProbe

    /** Missing, no longer allowed, not sound, or a format this phone cannot decode. */
    data object Unusable : SoundtrackProbe
}

/**
 * What the reel job needs from the phone's photo library, and nothing more. The real one is
 * [ReelEnvHost] (every pixel of a photo goes through the Experimental host's `decode`, which asks
 * `mayExport` first, TRAPS #33 and #34); the tests supply fakes that write placeholder files.
 *
 * None of these throws for a missing or protected file: they answer null or false, and the job turns that
 * into a sentence. An [OutOfMemoryError] is let through; the job steps down on it.
 */
interface ReelHost {
    /** The library item with this id if the gallery shows it right now (the browsable list), else null. */
    fun asset(mediaId: Long): MediaAsset?

    /** Whether this photo's pixels may be read and exported right now. Asked often; cheap. */
    fun mayUsePhoto(asset: MediaAsset): Boolean

    /** Whether this video may be read and exported right now. A video is not a photo to the host's own check. */
    fun mayUseVideo(asset: MediaAsset): Boolean

    /** The upright width over height of a photo or video, or null when it cannot be read. */
    suspend fun shapeOf(asset: MediaAsset): Float?

    /**
     * Write the photo, upright, as an image file at [into] shaped for [spec] (large enough for the motion's
     * closest window, and the decode never enlarges; for [ReelFit.CONTAIN] already placed whole, as large as it
     * fits, on a black frame), and say its size. Null when it may not be read or cannot be.
     */
    suspend fun stagePhoto(asset: MediaAsset, spec: StageSpec, into: File): StagedPicture?

    /** Write a title card as an image file at [into], the size of [frame]. */
    suspend fun stageCard(text: String, backgroundArgb: Int, frame: ReelFrame, into: File): StagedPicture

    /** Whether the soundtrack file at [uri] has a sound track this phone can decode. Reads the file; never plays it. */
    suspend fun probeSoundtrack(uri: String): SoundtrackProbe
}

/** How a reel job ended. */
sealed interface ReelOutcome {
    /**
     * The reel exists at [uri]. [steppedDown] is true when it is smaller than first asked; [cutsOnly] when the
     * project's fades and slides were drawn as cuts because the phone could not do them; [notes] are one
     * sentence each for what was given up.
     */
    class Done(
        val uri: String,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val hasSound: Boolean,
        val steppedDown: Boolean,
        val cutsOnly: Boolean,
        val notes: List<String>,
    ) : ReelOutcome {
        val message: String get() = ReelCopy.SAVED
    }

    /** [message] is the sentence for the person. */
    class Failed(val message: String) : ReelOutcome

    /**
     * The sound could not be used. Nothing was saved. [message] says why; the person decides whether to run
     * again with [ReelRequest.withoutSound]. The job never drops the sound by itself.
     */
    class AskSilent(val message: String) : ReelOutcome
}

/** A [ReelOutcome.Failed] as the failure of a [JobRunner] job. */
class ReelFailure(message: String) : Exception(message)

/** A [ReelOutcome.AskSilent] as the failure of a [JobRunner] job; the screen asks the person and may run again. */
class ReelNeedsAnswer(message: String) : Exception(message)

/**
 * The Android seam of the reel maker's export: takes a [ReelRequest] and renders the project into a new
 * MP4 in the library, through the narrow interfaces it needs from the phone ([ReelHost], [ReelEncoder],
 * [ReelSink]). Everything it says to the person is a [ReelCopy] sentence; it never throws a failure, it
 * returns one.
 *
 * ## The steps
 * 1. **Refuse** a plan the check ([ReelPlan]) blocks, and any item the host says is gone or protected
 *    (protected, hidden, locked since the editor opened), before a pixel is read; and a phone with no
 *    H.264 or AAC encoder.
 * 2. **Look at the sound file**, if there is one: can this phone decode it, and how long is it. When it
 *    cannot, the job stops with [ReelOutcome.AskSilent] and saves nothing; it never drops the sound by itself.
 * 3. **Stage** the pictures at the size the first rung needs: each still decoded upright through the host and
 *    written to a file in this app's cache, each title card drawn and written. A photo protected while this
 *    runs is named in a sentence, not skipped.
 * 4. **Render** down the ladder ([ReelAttempts]): 1080 with real hand-overs, 720, 720 with cuts, 480 with cuts.
 *    An out-of-memory error or a codec/compositor failure takes the next rung; a sound failure asks the person
 *    (step 2's question); an unreadable source or a full phone stops.
 * 5. **Look again** at the finished file for a recorded place ([ReelMp4Check]) and at the library for protection
 *    (TRAPS #33: a folder can be locked while a long job runs), then **publish** through [ReelSink.publish],
 *    which asks once more right before it makes the row.
 *
 * ## What this does not know
 * Nothing here has run on a device: the layered render (two video tracks and the compositor) is the part
 * most likely to need work, the rungs are guesses about what phones manage, and how long an export takes is
 * unmeasured. A video clip's frames are read by the platform's codecs inside the export library.
 *
 * ## Cancel and process death
 * Cancelling the coroutine stops the export, deletes the working files and returns by throwing
 * [CancellationException] so [JobRunner] reports it as cancelled. No MediaStore row exists before the file is
 * complete and checked, and the sink deletes one on a cancel that arrives later. If the process is killed
 * nothing has been saved: the working files stay in a `job-*` folder of the cache until [ReelSink.sweepStaging]
 * removes it, which the Make panel does when it opens and a later save does too, once the folder is an hour
 * old ([ReelWriter.pruneStaging]); until then they are decoded copies of the person's photos. What the person had in the project is the screen's to save, not this job's.
 */
object ReelJob {
    private const val STAGE_END = 0.2f
    private const val ENCODE_END = 0.93f
    private const val PROGRESS_STEP = 0.004f

    /** Run the whole job. Returns a [ReelOutcome]; throws only a cancellation. [onProgress] gets 0..1, rising. */
    // ReelFailures sits in the Media3-facing file; the out-of-space check used here touches none of it.
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun run(
        request: ReelRequest,
        host: ReelHost,
        encoder: ReelEncoder,
        sink: ReelSink,
        onProgress: (Float) -> Unit = {},
    ): ReelOutcome = withContext(Dispatchers.Default) {
        try {
            Steps(request, host, encoder, sink, Progress(onProgress)).go()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReelProtectedException) {
            ReelOutcome.Failed(ReelCopy.PROTECTED)
        } catch (e: IOException) {
            ReelOutcome.Failed(if (ReelFailures.isOutOfSpace(e)) ReelCopy.NO_SPACE else ReelCopy.SAVE_FAILED)
        } catch (e: OutOfMemoryError) {
            ReelOutcome.Failed(ReelCopy.OUT_OF_MEMORY)
        } catch (e: Exception) {
            ReelOutcome.Failed(ReelCopy.FAILED)
        }
    }

    /**
     * Start [run] as a [JobRunner] job (progress, cancel, the activity shade). The outcome arrives on
     * [JobRunner.outcomes] as a [ReelOutcome.Done] result, as a [ReelFailure] carrying the sentence for the
     * person, as a [ReelNeedsAnswer] when the person must decide about the sound, or as a cancellation.
     */
    fun launch(runner: JobRunner, request: ReelRequest, host: ReelHost, encoder: ReelEncoder, sink: ReelSink): JobHandle =
        runner.launch(ReelCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, host, encoder, sink) { onProgress(it) }) {
                is ReelOutcome.Done -> Result.success(outcome)
                is ReelOutcome.Failed -> Result.failure(ReelFailure(outcome.message))
                is ReelOutcome.AskSilent -> Result.failure(ReelNeedsAnswer(outcome.message))
            }
        }

    /** The bar moves in steps of at least this much, so a worker thread's tiny updates do not flood the shade. */
    private class Progress(private val report: (Float) -> Unit) {
        private var last = 0f

        fun at(value: Float) {
            val v = value.coerceIn(0f, 1f)
            if (v >= last + PROGRESS_STEP || (v >= 1f && last < 1f)) {
                last = v
                report(v)
            }
        }
    }

    private class Resolved(val item: ReelItem, val asset: MediaAsset?)

    private class Steps(
        val request: ReelRequest,
        val host: ReelHost,
        val encoder: ReelEncoder,
        val sink: ReelSink,
        val progress: Progress,
    ) {
        private val project = request.project
        private val fit = ReelFit.of(project.aspect)

        suspend fun go(): ReelOutcome {
            // 1. Refuse.
            ReelPlan.validate(project).firstOrNull { it.blocking }?.let { return ReelOutcome.Failed(ReelCopy.issue(it)) }
            if (!encoder.canEncode()) return ReelOutcome.Failed(ReelCopy.NO_ENCODER)
            val resolved = ArrayList<Resolved>(project.items.size)
            project.items.forEachIndexed { index, item ->
                val asset = when (item) {
                    is TitleItem -> null
                    is StillItem -> host.asset(item.mediaId)
                    is ClipItem -> host.asset(item.mediaId)
                }
                if (item !is TitleItem) {
                    if (asset == null) return ReelOutcome.Failed(ReelCopy.noLongerAvailable(index + 1))
                    if (asset.isVideo != (item is ClipItem)) return ReelOutcome.Failed(ReelCopy.cannotRead(index + 1))
                    if (!mayUse(asset)) return ReelOutcome.Failed(ReelCopy.PROTECTED)
                }
                resolved += Resolved(item, asset)
            }
            currentCoroutineContext().ensureActive()

            // 2. The sound file.
            val withSound = project.soundtrack != null && !request.withoutSound
            var sound: SoundtrackFacts? = null
            if (withSound) {
                val track = checkNotNull(project.soundtrack)
                when (val probe = host.probeSoundtrack(track.uri)) {
                    SoundtrackProbe.Unusable -> return ReelOutcome.AskSilent(ReelCopy.SOUND_UNREADABLE)
                    is SoundtrackProbe.Usable -> {
                        if (probe.durationMs > 0L && track.trimStartMs >= probe.durationMs) return ReelOutcome.AskSilent(ReelCopy.SOUND_STARTS_LATE)
                        sound = SoundtrackFacts(probe.durationMs)
                    }
                }
            }
            val keepClipSound = request.keepClipSound && !request.withoutSound
            val firstRatio = if (project.aspect == ReelAspect.ORIGINAL) firstShape(resolved) else null

            // 3 and 4. Stage, then render down the ladder.
            val work = File(sink.stagingDirectory(), "job-${System.nanoTime()}").apply { mkdirs() }
            try {
                val attempts = ReelAttempts.from(request.startShortSide, request.transitions)
                var staged: ReelStaged? = null
                var stagedShort = -1
                var last: ReelOutcome.Failed? = null
                for ((rung, attempt) in attempts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!allowed(resolved)) return ReelOutcome.Failed(ReelCopy.PROTECTED)
                    val frame = ReelFrame.of(project.aspect, attempt.shortSide, firstRatio)
                    var failure: ReelFailureKind? = null
                    if (staged == null || stagedShort != attempt.shortSide) {
                        work.listFiles()?.forEach { it.delete() }
                        staged = null
                        try {
                            staged = stage(resolved, frame, work)
                            stagedShort = attempt.shortSide
                        } catch (e: OutOfMemoryError) {
                            failure = ReelFailureKind.MEMORY
                        } catch (e: StageFailure) {
                            return ReelOutcome.Failed(e.message ?: ReelCopy.FAILED)
                        }
                    }
                    if (failure == null && staged != null) {
                        val plan = ReelRenderPlan.build(project, attempt, frame, fit, sound, keepClipSound)
                        val output = File(work, "reel-$rung.mp4")
                        try {
                            encoder.encode(plan, staged, output) { progress.at(STAGE_END + it.coerceIn(0f, 1f) * (ENCODE_END - STAGE_END)) }
                            return finish(plan, output, resolved, attempts.first())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: OutOfMemoryError) {
                            failure = ReelFailureKind.MEMORY
                        } catch (e: ReelEncodeException) {
                            failure = e.kind
                            if (failure == ReelFailureKind.AUDIO && !plan.hasAudio) failure = ReelFailureKind.STEP_DOWN
                            // The export does not say which file it could not parse: with a sound file laid, ask about it; else a clip is all that is left.
                            if (failure == ReelFailureKind.PARSE) failure = if (plan.audio != null) ReelFailureKind.AUDIO else ReelFailureKind.SOURCE
                        } finally {
                            // The file is complete and published (or dropped) by the time this runs; it is never kept.
                            output.delete()
                        }
                    }
                    when (failure) {
                        ReelFailureKind.AUDIO -> return ReelOutcome.AskSilent(ReelCopy.SOUND_FAILED)
                        ReelFailureKind.SOURCE -> return ReelOutcome.Failed(ReelCopy.SOURCE_UNREADABLE)
                        ReelFailureKind.STORAGE -> return ReelOutcome.Failed(if (lowSpace(work)) ReelCopy.NO_SPACE else ReelCopy.SAVE_FAILED)
                        ReelFailureKind.MEMORY -> last = ReelOutcome.Failed(ReelCopy.OUT_OF_MEMORY)
                        else -> last = ReelOutcome.Failed(ReelCopy.FAILED)
                    }
                }
                return last ?: ReelOutcome.Failed(ReelCopy.FAILED)
            } finally {
                work.deleteRecursively()
            }
        }

        private suspend fun finish(plan: ReelRenderPlan, output: File, resolved: List<Resolved>, firstAttempt: ReelAttempt): ReelOutcome {
            if (!output.isFile || output.length() <= 0L) return ReelOutcome.Failed(ReelCopy.FAILED)
            progress.at(ENCODE_END)
            // 5. Look again: a recorded place, then protection, then the sink's own last look.
            if (ReelMp4Check.carriesPlace(output)) return ReelOutcome.Failed(ReelCopy.PLACE_LEFT_IN)
            currentCoroutineContext().ensureActive()
            if (!allowed(resolved)) return ReelOutcome.Failed(ReelCopy.PROTECTED)
            val taken = resolved.firstNotNullOfOrNull { it.asset?.dateTakenMillis?.takeIf { d -> d > 0L } } ?: System.currentTimeMillis()
            val stem = reelStem(project.name, taken, request.zone)
            val uri = sink.publish(output, stem, taken, { allowed(resolved) }) { progress.at(ENCODE_END + it.coerceIn(0f, 1f) * (1f - ENCODE_END)) }
            progress.at(1f)
            val smaller = plan.attempt.shortSide < firstAttempt.shortSide
            val hadHandOvers = (0 until project.items.size).any { project.overlapAfterMs(it) > 0L }
            val cuts = hadHandOvers && request.transitions && !plan.attempt.transitions
            val notes = buildList {
                if (smaller) add(ReelCopy.SAVED_SMALLER)
                if (cuts) add(ReelCopy.SAVED_CUTS)
                if (request.withoutSound && (project.soundtrack != null || request.keepClipSound)) add(ReelCopy.SAVED_SILENT)
            }
            return ReelOutcome.Done(uri, plan.frame.width, plan.frame.height, plan.totalMs, plan.hasAudio, smaller, cuts, notes)
        }

        private class StageFailure(message: String) : Exception(message)

        private suspend fun stage(resolved: List<Resolved>, frame: ReelFrame, work: File): ReelStaged {
            val pictures = HashMap<String, StagedPicture>()
            val clips = HashMap<String, String>()
            resolved.forEachIndexed { index, r ->
                currentCoroutineContext().ensureActive()
                when (val item = r.item) {
                    is TitleItem -> pictures[item.id] = host.stageCard(item.text, item.backgroundArgb, frame, File(work, "card-${item.id}.png"))
                    is ClipItem -> clips[item.id] = checkNotNull(r.asset).contentUriString
                    is StillItem -> {
                        val asset = checkNotNull(r.asset)
                        // The check before any pixel is read; the host asks again inside its decode.
                        if (!host.mayUsePhoto(asset)) throw StageFailure(ReelCopy.noLongerAvailable(index + 1))
                        val picture = host.stagePhoto(asset, StageSpec(frame, fit, item.motion), File(work, "still-${item.id}.jpg"))
                            ?: throw StageFailure(if (host.mayUsePhoto(asset)) ReelCopy.cannotRead(index + 1) else ReelCopy.noLongerAvailable(index + 1))
                        pictures[item.id] = picture
                    }
                }
                progress.at(STAGE_END * (index + 1) / resolved.size)
            }
            return ReelStaged(pictures, clips)
        }

        private suspend fun firstShape(resolved: List<Resolved>): Float? {
            val first = resolved.firstOrNull { it.asset != null } ?: return null
            return host.shapeOf(checkNotNull(first.asset))
        }

        private fun mayUse(asset: MediaAsset): Boolean = if (asset.isVideo) host.mayUseVideo(asset) else host.mayUsePhoto(asset)

        private fun allowed(resolved: List<Resolved>): Boolean = resolved.all { r -> r.asset == null || mayUse(r.asset) }

        private fun lowSpace(dir: File): Boolean = runCatching { dir.usableSpace < ReelWriter.LOW_SPACE_BYTES }.getOrDefault(false)
    }
}
