package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.editor.exportEdgeLadder
import com.fotoxplorr.app.editor.renderDownTheLadder
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** What to save: [asset] recoloured toward [colours] as [settings] say. */
internal class RecolourRequest(val asset: MediaAsset, val colours: List<Int>, val settings: RecolourSettings)

/** Where the finished picture goes. The editor's writer is [EditorRecolourSink]; tests use a fake. */
internal interface RecolourSink {
    /** Writes [bitmap] as a NEW photo made from [source]; the reference of the new file, or why it failed. */
    suspend fun save(source: MediaAsset, bitmap: Bitmap, onSteppedDown: () -> Unit): Result<String>

    /** Removes the file [ref] names; best effort, for a cancel that arrived after the file was made. */
    suspend fun discard(ref: String)
}

/** How a recolour job ended. */
internal sealed interface RecolourOutcome {
    /** The copy exists at [ref]; [smaller] when it is below the original's size (the cap, or memory). */
    class Done(val ref: String, val smaller: Boolean) : RecolourOutcome {
        val message: String get() = if (smaller) PaletteBuilderCopy.COPIED_SMALLER else PaletteBuilderCopy.COPIED
    }

    class Failed(val message: String) : RecolourOutcome
}

/** A [RecolourOutcome.Failed] as the failure of a [JobRunner] job. */
internal class RecolourFailure(message: String) : Exception(message)

/** The phone has no room for the copy. */
internal class RecolourNoSpaceException(cause: Throwable? = null) : IOException("no space left for the recoloured copy", cause)

/**
 * "Recolour a photo to this palette", saved as a NEW file. It never writes to the original or replaces any file.
 *
 * ## The steps
 * 1. **Refuse** a video, or a photo the host says may not be copied, before a pixel is read.
 * 2. **Decode** upright (TRAPS #34) at no more than [MAX_EDGE] px on the long edge and [MAX_PIXELS] in all, and
 *    **recolour in bands** of whole rows ([PaletteRecolour.bands]) into a fresh bitmap, so the pixels held as
 *    ints at once are one band, not the picture. A run out of memory takes the next smaller edge of the
 *    editor's ladder ([exportEdgeLadder]) and the result says it is smaller.
 * 3. **Ask the host again**, then hand the bitmap to the sink, which stages a JPEG and only then makes the
 *    MediaStore row (so a failure or cancel before that leaves nothing).
 * 4. A cancel that arrives while the sink is working has the file it made [RecolourSink.discard]ed.
 *
 * ## The size cap
 * A recolour does Lab maths on every pixel. The cap ([MAX_EDGE] 4096, [MAX_PIXELS] about 12.6 MP) is a guess at
 * "a few seconds, not a minute" on a phone; nothing has been timed on one. A bigger original is saved smaller
 * and the person is told so.
 *
 * ## What the copy carries
 * The sink is the editor's own writer, so the copy has what an edited copy has: the original's EXIF, XMP and
 * capture date, upright. That includes the place a photo was taken, exactly as the editor's Save does for a copy
 * kept in this library (see [EditorRecolourSink]); it is not the stripped export.
 *
 * ## Cancel and process death
 * Cancelling stops the job between bands and before the file is made. If the process is killed nothing is saved:
 * the copy only exists once the sink finishes, and a half-written one is a pending row Android hides (Android 10
 * and later; on 8 and 9 there is no pending state). The palette and the photo chosen are the screen's to remember.
 *
 * Nothing here has run on a device or on a real photograph.
 */
internal object RecolourJob {
    const val MAX_EDGE = 4096
    const val MAX_PIXELS = 12_582_912L

    private const val DECODED = 0.1f
    private const val RECOLOURED = 0.85f

    /** Runs the job. Returns [RecolourOutcome.Done] or [RecolourOutcome.Failed]; throws only [CancellationException]. */
    suspend fun run(
        request: RecolourRequest,
        host: PaletteHost,
        sink: RecolourSink,
        onProgress: (Float) -> Unit = {},
    ): RecolourOutcome = withContext(Dispatchers.Default) {
        try {
            go(request, host, sink, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RecolourNoSpaceException) {
            RecolourOutcome.Failed(PaletteBuilderCopy.COPY_NO_SPACE)
        } catch (e: OutOfMemoryError) {
            RecolourOutcome.Failed(PaletteBuilderCopy.COPY_OUT_OF_MEMORY)
        } catch (e: Exception) {
            RecolourOutcome.Failed(PaletteBuilderCopy.COPY_FAILED)
        }
    }

    /** Starts [run] as a [JobRunner] job (progress, cancel, the activity shade). Its outcome arrives on [JobRunner.outcomes]. */
    fun launch(runner: JobRunner, request: RecolourRequest, host: PaletteHost, sink: RecolourSink): JobHandle =
        runner.launch(PaletteBuilderCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, host, sink) { onProgress(it) }) {
                is RecolourOutcome.Done -> Result.success(outcome)
                is RecolourOutcome.Failed -> Result.failure(RecolourFailure(outcome.message))
            }
        }

    private suspend fun go(request: RecolourRequest, host: PaletteHost, sink: RecolourSink, onProgress: (Float) -> Unit): RecolourOutcome {
        val asset = request.asset
        if (asset.isVideo || !host.mayExport(asset)) return RecolourOutcome.Failed(PaletteBuilderCopy.COPY_PROTECTED)
        if (request.colours.size < Palette.MIN_COLORS) return RecolourOutcome.Failed(PaletteBuilderCopy.COPY_FAILED)
        val recolour = PaletteRecolour(
            colors = request.colours,
            amount = request.settings.amount,
            keepLightness = request.settings.keepLightness,
        )
        onProgress(0f)

        var unreadable = false
        var downscaled = false
        val made = renderDownTheLadder(exportEdgeLadder(MAX_EDGE)) { edge ->
            val decoded = host.decode(asset, DecodeLimits(maxLongEdge = edge, maxPixels = MAX_PIXELS))
            if (decoded == null) {
                unreadable = true
                return@renderDownTheLadder null
            }
            downscaled = decoded.downscaled
            onProgress(DECODED)
            try {
                recolourBitmap(decoded.bitmap, recolour) { onProgress(DECODED + (RECOLOURED - DECODED) * it) }
            } finally {
                decoded.bitmap.recycle()
            }
        }
        if (made == null) {
            return RecolourOutcome.Failed(if (unreadable) PaletteBuilderCopy.COPY_UNREADABLE else PaletteBuilderCopy.COPY_OUT_OF_MEMORY)
        }
        val painted = made.value
        var smaller = made.steppedDown || downscaled
        try {
            currentCoroutineContext().ensureActive()
            if (!host.mayExport(asset)) return RecolourOutcome.Failed(PaletteBuilderCopy.COPY_PROTECTED)
            onProgress(RECOLOURED)
            val saved = sink.save(asset, painted) { smaller = true }
            val ref = saved.getOrElse { error ->
                if (error is CancellationException) throw error
                return RecolourOutcome.Failed(
                    if (error is RecolourNoSpaceException) PaletteBuilderCopy.COPY_NO_SPACE else PaletteBuilderCopy.COPY_FAILED,
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
            return RecolourOutcome.Done(ref, smaller)
        } finally {
            painted.recycle()
        }
    }

    /**
     * A new bitmap the size of [source] with every pixel run through [recolour], one band of whole rows at a time.
     * Stops between bands if the job is cancelled. [source] is left as it was; the caller recycles it.
     */
    internal suspend fun recolourBitmap(source: Bitmap, recolour: PaletteRecolour, onProgress: (Float) -> Unit = {}): Bitmap {
        val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val width = readable.width
        val height = readable.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val bands = PaletteRecolour.bands(width, height)
            val widest = bands.maxOf { (it.last - it.first + 1) * width }
            val inBand = IntArray(widest)
            val outBand = IntArray(widest)
            bands.forEachIndexed { index, rows ->
                currentCoroutineContext().ensureActive()
                val count = rows.last - rows.first + 1
                readable.getPixels(inBand, 0, width, 0, rows.first, width, count)
                recolour.mapInto(inBand, outBand, count * width)
                out.setPixels(outBand, 0, width, 0, rows.first, width, count)
                onProgress((index + 1f) / bands.size)
            }
            return out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            if (readable !== source) readable.recycle()
        }
    }
}
