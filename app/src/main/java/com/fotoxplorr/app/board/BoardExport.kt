package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.editor.ExportOptions
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Why an export did not save. Each one has a sentence in [BoardCopy.problem]. */
enum class BoardExportProblem { EMPTY, PHOTOS_UNAVAILABLE, PROTECTED, TOO_BIG, NO_SPACE, SAVE_FAILED }

/** Everything an export is asked for. The [board] is a snapshot: later edits do not change a save in progress. */
class BoardExportRequest(
    val board: Board,
    val name: String,
    val format: OutputFormat = BoardFormats.defaultFor(board),
    val maxEdge: MaxEdgePreset = MaxEdgePreset.ORIGINAL,
)

/** Which file types a board can be saved as. */
object BoardFormats {
    val CHOICES: List<OutputFormat> = listOf(OutputFormat.PNG, OutputFormat.JPEG)

    /** PNG for a board with a see-through part (a JPEG has no alpha and would turn it black), otherwise JPEG. */
    fun defaultFor(board: Board): OutputFormat = if (board.needsAlpha) OutputFormat.PNG else OutputFormat.JPEG

    /** [wanted] if the board can be saved that way: a see-through board is always PNG, and anything but PNG or JPEG is JPEG. */
    fun resolve(board: Board, wanted: OutputFormat): OutputFormat =
        if (board.needsAlpha) OutputFormat.PNG else if (wanted in CHOICES) wanted else OutputFormat.JPEG

    /** Whether the person may choose: not when the file type is decided by a see-through background. */
    fun isChoosable(board: Board): Boolean = !board.needsAlpha
}

/** A saved file: where it is and what it is called. */
class BoardPublished(val uri: String, val displayName: String)

/** What an export came to. */
sealed interface BoardExportOutcome {
    class Done(val uri: String, val displayName: String, val width: Int, val height: Int, val steppedDown: Boolean) : BoardExportOutcome

    /** [layerIds] names the layers whose photos could not be used, for the cases that have them. */
    class Failed(val problem: BoardExportProblem, val layerIds: List<Long> = emptyList()) : BoardExportOutcome
}

/** A failed export as the failure of a [JobRunner] job, carrying what the screen needs to explain it. */
class BoardExportFailure(val problem: BoardExportProblem, val layerIds: List<Long>) : Exception(BoardCopy.problem(problem))

/** A photo was protected between the start of an export and its publishing; nothing was saved. */
class BoardProtectedException : IOException("a photo is protected now")

/** The phone is nearly full. */
class BoardNoSpaceException(cause: Throwable? = null) : IOException("no space left", cause)

/**
 * Where a finished picture goes. The Android one is [BoardWriter]; the tests supply one that keeps
 * what it was given.
 */
interface BoardSink {
    /**
     * Saves [bitmap] as a NEW file named from [baseName] (a number is added when the name is taken;
     * nothing already in the library is replaced). It asks [stillAllowed] right before a row exists and
     * throws [BoardProtectedException] when that says no, so a photo locked while the picture was
     * drawn never reaches the library. A cancel or any failure leaves no row and no partial file.
     * No metadata is written, so no location can come from the photos.
     */
    suspend fun publish(
        bitmap: Bitmap,
        format: OutputFormat,
        quality: Int,
        baseName: String,
        dateTakenMillis: Long,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): BoardPublished
}

/**
 * The export of a board: draw it at the size asked (under the editor's limits), then save it as a new
 * picture. Runs as a [JobRunner] job ([launch]) with progress and Cancel; [run] is the same thing for a
 * test. It never throws a failure, it returns one (a cancel still throws, as it must).
 *
 * The steps, and what protects the person's photos:
 *  1. Refuse an empty board, and refuse before drawing anything if a photo on the canvas cannot be used
 *     now (gone, locked, hidden, protected): [BoardRenderer.export] asks the photo source first.
 *  2. Draw on a background thread. The photo source is the host's, so every pixel comes through
 *     `env.decode` (which asks `mayExport` again).
 *  3. Ask again right before publishing ([BoardRenderer.missingNow]) and, inside the sink, once more
 *     before the row is made. A photo locked while a long board was drawn is therefore refused.
 *  4. A see-through board is always saved as PNG ([BoardFormats]).
 *
 * Progress: drawing is the first 80 percent, one step per layer (a photo that is much larger than the
 * rest makes the steps uneven); encoding and copying are the rest. Nothing here has run on a device.
 */
object BoardExportJob {

    private const val DRAWN_SHARE = 0.8f
    private const val ENCODE_START = 0.85f

    suspend fun run(
        request: BoardExportRequest,
        photos: BoardPhotoSource,
        sink: BoardSink,
        clock: () -> Long = System::currentTimeMillis,
        onProgress: (Float?) -> Unit = {},
    ): BoardExportOutcome {
        val board = request.board
        if (board.layers.isEmpty()) return BoardExportOutcome.Failed(BoardExportProblem.EMPTY)
        val format = BoardFormats.resolve(board, request.format)
        val options = ExportOptions(format = format, maxEdge = request.maxEdge)
        val renderer = BoardRenderer(photos)

        onProgress(null)
        val rendered = try {
            withContext(Dispatchers.Default) {
                renderer.export(board, options) { done, total -> onProgress(DRAWN_SHARE * done / total.coerceAtLeast(1)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return BoardExportOutcome.Failed(BoardExportProblem.TOO_BIG)
        } catch (e: Exception) {
            return BoardExportOutcome.Failed(BoardExportProblem.SAVE_FAILED)
        }

        val done = when (rendered) {
            is BoardRender.Refused -> return BoardExportOutcome.Failed(BoardExportProblem.PHOTOS_UNAVAILABLE, rendered.missing)
            BoardRender.OutOfMemory -> return BoardExportOutcome.Failed(BoardExportProblem.TOO_BIG)
            is BoardRender.Done -> rendered
        }
        val bitmap = done.bitmap
        try {
            currentCoroutineContext().ensureActive()
            val stillMissing = renderer.missingNow(board)
            if (stillMissing.isNotEmpty()) return BoardExportOutcome.Failed(BoardExportProblem.PROTECTED, stillMissing)
            onProgress(ENCODE_START)
            val width = bitmap.width
            val height = bitmap.height
            val published = sink.publish(
                bitmap = bitmap,
                format = format,
                quality = options.resolvedQuality(format),
                baseName = request.name,
                dateTakenMillis = clock(),
                stillAllowed = { renderer.missingNow(board).isEmpty() },
                onProgress = { onProgress(ENCODE_START + (1f - ENCODE_START) * it.coerceIn(0f, 1f)) },
            )
            return BoardExportOutcome.Done(published.uri, published.displayName, width, height, done.steppedDown)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BoardProtectedException) {
            return BoardExportOutcome.Failed(BoardExportProblem.PROTECTED)
        } catch (e: BoardNoSpaceException) {
            return BoardExportOutcome.Failed(BoardExportProblem.NO_SPACE)
        } catch (e: OutOfMemoryError) {
            return BoardExportOutcome.Failed(BoardExportProblem.TOO_BIG)
        } catch (e: Exception) {
            return BoardExportOutcome.Failed(BoardExportProblem.SAVE_FAILED)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Start [run] as a [JobRunner] job (progress, Cancel, the activity shade). The outcome arrives on
     * [JobRunner.outcomes] as a [BoardExportOutcome.Done] result, a [BoardExportFailure], or a cancellation.
     */
    fun launch(runner: JobRunner, request: BoardExportRequest, photos: BoardPhotoSource, sink: BoardSink): JobHandle =
        runner.launch(BoardCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, photos, sink) { onProgress(it) }) {
                is BoardExportOutcome.Done -> Result.success(outcome)
                is BoardExportOutcome.Failed -> Result.failure(BoardExportFailure(outcome.problem, outcome.layerIds))
            }
        }

    /**
     * The file name of a saved board: the board's name (path separators and the characters a file
     * system refuses become underscores), the extension of [format]; if [taken] says the name exists,
     * `-2`, `-3` and so on, and past a thousand a name nobody else can have.
     */
    fun fileName(baseName: String, format: OutputFormat, taken: (String) -> Boolean): String {
        val cleaned = baseName.filter { !it.isISOControl() }.trim()
            .map { if (it in FORBIDDEN) '_' else it }.joinToString("").trim().trim('.')
            .take(MAX_STEM).trim().ifBlank { BoardCopy.DEFAULT_NAME }
        val ext = format.extension
        var candidate = "$cleaned.$ext"
        var number = 2
        while (taken(candidate)) {
            if (number > MAX_NUMBER) return "$cleaned-${System.nanoTime()}.$ext"
            candidate = "$cleaned-$number.$ext"
            number++
        }
        return candidate
    }

    private const val MAX_STEM = 80
    private const val MAX_NUMBER = 1000
    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
