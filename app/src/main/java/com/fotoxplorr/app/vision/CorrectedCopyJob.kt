package com.fotoxplorr.app.vision

import android.graphics.Bitmap
import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.exportEdgeLadder
import com.fotoxplorr.app.editor.renderDownTheLadder
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** What to save: [asset] seen through [key] (the exact filter, not the live preview). */
internal class CorrectedCopyRequest(val asset: MediaAsset, val key: FilterKey)

/** What the job reads through. The viewer's is [com.fotoxplorr.app.vision.ViewerCopyHost]; tests use a fake. */
internal interface CorrectedCopyHost {
    /**
     * Whether [asset]'s pixels may be read and copied right now. Asked before the first read and
     * again right before the new file is made, because a long job can outlive the reason it was allowed.
     */
    fun mayCopy(asset: MediaAsset): Boolean

    /** [asset] upright and within [limits] (TRAPS #34), or null when it cannot be read. */
    suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap?
}

/** Where the finished picture goes. The viewer's is [com.fotoxplorr.app.vision.EditorCopySink]; tests use a fake. */
internal interface CorrectedCopySink {
    /** Writes [bitmap] as a NEW photo made from [source]; the reference of the new file, or why it failed. */
    suspend fun save(source: MediaAsset, bitmap: Bitmap, onSteppedDown: () -> Unit): Result<String>

    /** Removes the file [ref] names; best effort, for a cancel that arrived after the file was made. */
    suspend fun discard(ref: String)
}

/** How a corrected-copy job ended. */
internal sealed interface CorrectedCopyOutcome {
    /** The copy exists at [ref]; [smaller] when it had to be saved below the original's size to fit memory. */
    class Done(val ref: String, val smaller: Boolean) : CorrectedCopyOutcome {
        val message: String get() = if (smaller) VisionCopy.COPY_SAVED_SMALLER else VisionCopy.COPY_SAVED
    }

    class Failed(val message: String) : CorrectedCopyOutcome
}

/** A [CorrectedCopyOutcome.Failed] as the failure of a [JobRunner] job. */
internal class CorrectedCopyFailure(message: String) : Exception(message)

/** The phone has no room for the copy. */
internal class CorrectedCopyNoSpaceException(cause: Throwable? = null) : IOException("no space left for the copy", cause)

/**
 * The corrected copy: the photo seen through the viewer's current filter, written as a NEW file
 * beside the original. It never writes to the original or replaces any file.
 *
 * ## The steps
 * 1. **Refuse** a photo the host says may not be copied, before a pixel is read.
 * 2. **Decode** upright ([DecodeLimits] from the editor's own export ceiling, [MAX_EXPORT_EDGE] and
 *    [MAX_EXPORT_PIXELS]) and **filter** into a fresh bitmap by the exact path
 *    ([ColourVisionRenderer.filter]). If either runs out of memory the next smaller edge of the
 *    editor's ladder ([exportEdgeLadder]) is tried, and the result says it is smaller.
 * 3. **Ask the host again**, then hand the bitmap to the sink, which encodes a JPEG into a staging
 *    file first and only then makes the MediaStore row (so a failure or cancel before that leaves
 *    nothing), and makes it visible when the bytes are in.
 * 4. If the job was cancelled while the sink was working, the file it made is [CorrectedCopySink.discard]ed.
 *
 * ## What the copy carries
 * The sink is the editor's own writer ([com.fotoxplorr.app.editor.EditedCopyWriter.save]), so the
 * copy has what an edited copy has: the original's EXIF, XMP and capture date, orientation reset to
 * upright. That includes the place a photo was taken, exactly as the editor's Save does for a copy
 * kept in this library; it is NOT the stripped export of `saveTo`. The name is the original's stem
 * plus `-edited`, because that writer has one marker.
 *
 * ## Cancel and process death
 * Cancelling stops the job between bands of rows and before the file is made. Nothing is saved if the
 * process is killed first: the copy only exists once the sink finishes, and a half-written one is a
 * pending row Android hides (Android 10 and later; on 8 and 9 there is no pending state). What the
 * person had chosen in the viewer is not this job's to remember.
 *
 * Nothing here has run on a device or on a real photograph.
 */
internal object CorrectedCopyJob {
    private const val DECODED = 0.15f
    private const val FILTERED = 0.8f

    /** Runs the job. Returns [CorrectedCopyOutcome.Done] or [CorrectedCopyOutcome.Failed]; throws only [CancellationException]. */
    suspend fun run(
        request: CorrectedCopyRequest,
        host: CorrectedCopyHost,
        sink: CorrectedCopySink,
        onProgress: (Float) -> Unit = {},
    ): CorrectedCopyOutcome = withContext(Dispatchers.Default) {
        try {
            go(request, host, sink, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CorrectedCopyNoSpaceException) {
            CorrectedCopyOutcome.Failed(VisionCopy.COPY_NO_SPACE)
        } catch (e: OutOfMemoryError) {
            CorrectedCopyOutcome.Failed(VisionCopy.COPY_OUT_OF_MEMORY)
        } catch (e: Exception) {
            CorrectedCopyOutcome.Failed(VisionCopy.COPY_FAILED)
        }
    }

    /** Starts [run] as a [JobRunner] job (progress, cancel, the activity shade). Its outcome arrives on [JobRunner.outcomes]. */
    fun launch(runner: JobRunner, request: CorrectedCopyRequest, host: CorrectedCopyHost, sink: CorrectedCopySink): JobHandle =
        runner.launch(VisionCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, host, sink) { onProgress(it) }) {
                is CorrectedCopyOutcome.Done -> Result.success(outcome)
                is CorrectedCopyOutcome.Failed -> Result.failure(CorrectedCopyFailure(outcome.message))
            }
        }

    private suspend fun go(
        request: CorrectedCopyRequest,
        host: CorrectedCopyHost,
        sink: CorrectedCopySink,
        onProgress: (Float) -> Unit,
    ): CorrectedCopyOutcome {
        val asset = request.asset
        if (asset.isVideo || !host.mayCopy(asset)) return CorrectedCopyOutcome.Failed(VisionCopy.COPY_PROTECTED)
        onProgress(0f)

        var unreadable = false
        val made = renderDownTheLadder(exportEdgeLadder(MAX_EXPORT_EDGE)) { edge ->
            val decoded = host.decode(asset, DecodeLimits(maxLongEdge = edge, maxPixels = MAX_EXPORT_PIXELS))
            if (decoded == null) {
                unreadable = true
                return@renderDownTheLadder null
            }
            onProgress(DECODED)
            try {
                ColourVisionRenderer.filter(decoded.bitmap, request.key) { onProgress(DECODED + (FILTERED - DECODED) * it) }
            } finally {
                decoded.bitmap.recycle()
            }
        }
        if (made == null) {
            return CorrectedCopyOutcome.Failed(if (unreadable) VisionCopy.COPY_CANNOT_READ else VisionCopy.COPY_OUT_OF_MEMORY)
        }
        val filtered = made.value
        var smaller = made.steppedDown
        try {
            currentCoroutineContext().ensureActive()
            if (!host.mayCopy(asset)) return CorrectedCopyOutcome.Failed(VisionCopy.COPY_PROTECTED)
            onProgress(FILTERED)
            val saved = sink.save(asset, filtered) { smaller = true }
            val ref = saved.getOrElse { error ->
                if (error is CancellationException) throw error
                return CorrectedCopyOutcome.Failed(
                    if (error is CorrectedCopyNoSpaceException) VisionCopy.COPY_NO_SPACE else VisionCopy.COPY_FAILED,
                )
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                // The file exists but the person cancelled: "cancelled" never has a copy behind it.
                withContext(NonCancellable) { sink.discard(ref) }
                throw e
            }
            onProgress(1f)
            return CorrectedCopyOutcome.Done(ref, smaller)
        } finally {
            filtered.recycle()
        }
    }
}
