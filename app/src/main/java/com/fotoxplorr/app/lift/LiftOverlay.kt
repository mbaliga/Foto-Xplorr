package com.fotoxplorr.app.lift

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.viewer.fittedImageRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The "lift a subject" editor over a photo, entered from a long press or the actions room's
 * "Lift subject" row.
 *
 * ## What the person can do
 * Tap the subject, or scribble over it, and the selection grows to the edges
 * ([GeodesicSegmenter]). Scribble with the Background mode to push the selection back off
 * something it swallowed; switch to Refine to paint pixels in or out directly; Undo / Redo walk
 * the history ([SelectionSession]); Preview shows the cut-out on a checkerboard; Done opens the
 * result, which can be copied, shared or saved as a sticker (a 2048px PNG with real alpha, in
 * Pictures/Fotoz/Stickers). Nothing is written anywhere until one of those buttons is pressed.
 *
 * ## Gesture precedence -- read this before changing [com.fotoxplorr.app.viewer.ViewerScreen]
 * This composable renders NOTHING at all -- no `Box`, no `pointerInput`, nothing hit-testable --
 * whenever [active] is false. That single `if (!active) return` at the top is the entire strategy
 * for not breaking the viewer's existing gestures, and it is deliberately the same strategy
 * [com.fotoxplorr.app.viewer.LiveTextOverlay] already uses ("empty renders nothing at all, so a
 * photo with no text carries no invisible tap targets"). The alternative -- keeping this
 * composed all the time and toggling some internal "am I armed" flag before deciding whether to
 * consume a tap -- would still occupy a `pointerInput` node in the tree permanently, and Compose's
 * gesture dispatch has no reliable way for ViewerScreen's own tap/pinch/page detectors to tell
 * "an armed-but-currently-inert sibling node" apart from "an armed-and-about-to-consume one" ahead
 * of time. Not being composed at all removes the ambiguity by construction instead of resolving it
 * by ordering, which is exactly the class of bug this file's header comment on the THREE-detector
 * rewrite already paid for once.
 *
 * When this IS composed, its pointer handlers sit as CHILDREN of ViewerScreen's photo `Box`, at
 * the same tree depth as [com.fotoxplorr.app.viewer.LiveTextOverlay] -- children are hit-tested
 * before their ancestors, so a touch that lands here is consumed here and never reaches the outer
 * chrome-toggle / double-tap-zoom / page-swipe detectors. Every touch on the canvas and on the
 * tool panels is consumed on purpose, including the ones that do nothing (a touch on the
 * letterbox): nothing else on the photo should react while a selection is being made.
 *
 * ## The editor draws the photo itself
 * It paints its own opaque backdrop and the decoded bitmap, fitted by that bitmap's OWN
 * dimensions ([imagePointToNormalized]), instead of relying on the viewer's image underneath. The
 * mask overlay, the checkerboard preview and every touch mapping then share one coordinate frame
 * by construction, whatever the viewer's zoom or the asset's reported size.
 */
@Composable
fun LiftOverlay(
    asset: MediaAsset,
    active: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Declared before the early return and keyed on `active`, so Back is intercepted exactly while
    // a lift is on screen and a later re-entry starts from a clean slate. Without this the system
    // Back gesture closed the whole viewer (or backed out of a room) underneath a half-finished
    // lift, which reads as "the app threw my selection away".
    var showingResult by remember(asset.id, active) { mutableStateOf(false) }
    BackHandler(enabled = active && !asset.isVideo) {
        if (showingResult) showingResult = false else onDismiss()
    }

    if (!active || asset.isVideo) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember(asset.id) { LiftEditorState() }

    // Decoded once per photo, lazily -- this LaunchedEffect only exists in the composition at all
    // once `active` is true, so a photo nobody ever lifts never pays for this decode.
    //
    // Every outcome ends in a state the person can see. The decode used to run bare: a null
    // result left a "Tap the subject" banner over a tap handler that was never installed (every
    // tap silently ignored, forever), and an exception from the EXIF read -- which sits outside
    // decodeUpright's own runCatching -- escaped the LaunchedEffect and took the composition down.
    LaunchedEffect(asset.id) {
        val loaded = catchingResult {
            val decoded = decodeUpright(context, asset.contentUri, LIFT_DECODE_LIMITS)?.bitmap
                ?: throw IOException("the photo could not be decoded")
            withContext(Dispatchers.Default) { buildWorkspace(decoded) }
        }
        editor.load = loaded.fold(
            onSuccess = { LoadState.Ready(it) },
            onFailure = { LoadState.Failed(classifyLiftFailure(it)) },
        )
    }

    // Copy and share each write a fresh PNG to the private cache; sweep the old ones whenever the
    // editor opens so that directory cannot grow for ever. Best-effort: a failure here is nothing
    // the person needs to hear about.
    LaunchedEffect(Unit) {
        catchingResult { StickerExporter(context).pruneCache() }
    }

    // The selection is DERIVED from the history, never edited in place: whenever the session
    // changes the whole thing is replayed. Keyed on the session so a newer stroke cancels a solve
    // that is still running (the solver polls `isActive` and throws), and so Undo is just "replay
    // one operation fewer" with no snapshots to hold.
    val workspace = (editor.load as? LoadState.Ready)?.workspace
    LaunchedEffect(workspace, editor.session) {
        val ws = workspace ?: return@LaunchedEffect
        val session = editor.session
        if (session.isEmpty) {
            editor.visuals = null
            editor.solveFailure = null
            editor.solving = false
            // Nothing left to preview; a lingering flag would show the Preview chip selected, and
            // the banner claim a preview, over an empty selection.
            editor.previewCutout = false
            return@LaunchedEffect
        }
        editor.solving = true
        val outcome = catchingResult {
            withContext(Dispatchers.Default) {
                val mask = SelectionOps.replay(ws.pixels, ws.width, ws.height, session.ops, shouldStop = { !isActive })
                MaskVisuals(
                    mask = mask,
                    overlay = LiftRenderer.selectionOverlay(mask, SELECTION_RGB, SELECTION_MAX_ALPHA).asImageBitmap(),
                    hasSelection = SubjectSegmentation.boundingBox(mask) != null,
                )
            }
        }
        editor.solving = false
        outcome.fold(
            onSuccess = {
                editor.visuals = it
                editor.solveFailure = if (!it.hasSelection && session.hasForeground) LiftFailure.NoSubject else null
            },
            onFailure = { editor.solveFailure = classifyLiftFailure(it) },
        )
    }

    // The checkerboard preview of the cut-out inside the editor: rebuilt only while Preview is on.
    LaunchedEffect(workspace, editor.visuals, editor.previewCutout) {
        val ws = workspace
        val visuals = editor.visuals
        if (ws == null || visuals == null || !editor.previewCutout) {
            editor.cutPreview = null
            return@LaunchedEffect
        }
        if (!visuals.hasSelection) {
            // The Preview chip is disabled with no selection, so the flag must not outlive one.
            editor.previewCutout = false
            editor.cutPreview = null
            return@LaunchedEffect
        }
        editor.cutPreview = catchingResult {
            withContext(Dispatchers.Default) { LiftRenderer.maskedPreview(ws.display, visuals.mask).asImageBitmap() }
        }.getOrNull()
    }

    // Save/share/copy feedback, self-contained rather than routed through the host Activity's
    // message channel: ViewerScreen has no `onMessage` callback today, and adding one would mean
    // widening its public API for a two-line toast. Auto-clears; nothing here needs to be
    // remembered past the moment the person reads it.
    LaunchedEffect(editor.statusMessage) {
        if (editor.statusMessage != null) {
            delay(STATUS_MESSAGE_MS)
            editor.statusMessage = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Only a failed LOAD is fatal. A failed solve (say, the overlay bitmap hit memory pressure
        // on stroke N) leaves the editor up with the message in the banner: the session is intact,
        // Undo / Start over stay reachable, and the next successful solve clears the failure.
        // Replacing the editor here would leave nothing but Close and throw away all of that work.
        val fatal = (editor.load as? LoadState.Failed)?.failure
        when {
            editor.load is LoadState.Loading -> LiftLoadingChrome(onCancel = onDismiss)
            fatal != null -> LiftFailureChrome(message = fatal.userMessage(), onClose = onDismiss)
            workspace != null && showingResult -> LiftResultScreen(
                preview = rememberResultPreview(workspace, editor.visuals?.mask),
                statusMessage = editor.statusMessage,
                busy = editor.exporting,
                onCopy = {
                    exportThen(scope, editor, context, asset, workspace) { exporter, bitmap ->
                        editor.statusMessage = exporter.copyToClipboard(bitmap).fold(
                            onSuccess = { "Copied to clipboard" },
                            onFailure = { it.message ?: "Could not copy the sticker" },
                        )
                    }
                },
                onShare = {
                    exportThen(scope, editor, context, asset, workspace) { exporter, bitmap ->
                        exporter.prepareForShare(bitmap).fold(
                            onSuccess = { uri ->
                                runCatching {
                                    context.startActivity(Intent.createChooser(exporter.shareIntent(uri), "Send sticker"))
                                }.onFailure { editor.statusMessage = "No compatible app was found" }
                            },
                            onFailure = { editor.statusMessage = it.message ?: "Could not prepare the sticker" },
                        )
                    }
                },
                onSave = {
                    exportThen(scope, editor, context, asset, workspace) { exporter, bitmap ->
                        editor.statusMessage = exporter.saveToGallery(bitmap, asset.displayName).fold(
                            onSuccess = { "Saved to Pictures/Fotoz/Stickers" },
                            onFailure = { it.message ?: "Could not save the sticker" },
                        )
                    }
                },
                onEditSelection = { showingResult = false },
                onClose = onDismiss,
            )
            workspace != null -> {
                LiftCanvas(editor = editor, workspace = workspace)
                LiftEditorChrome(
                    editor = editor,
                    onCancel = onDismiss,
                    onDone = { showingResult = true },
                )
            }
        }
    }
}

/**
 * Build (or reuse) the full-size sticker for the current selection and hand it to [action].
 *
 * The export is decoded again at [StickerExporter.EXPORT_LONG_EDGE_PX] rather than reusing the
 * 1024px editing bitmap, with the 512px mask resampled up to it, so a sticker is not capped at
 * the resolution the interactive solve needed. It is cached per selection (Copy then Share then
 * Save must not decode three times); if the big decode fails, say for memory, the editing-size
 * cut-out is exported instead of nothing.
 */
private fun exportThen(
    scope: kotlinx.coroutines.CoroutineScope,
    editor: LiftEditorState,
    context: android.content.Context,
    asset: MediaAsset,
    workspace: Workspace,
    action: suspend (StickerExporter, Bitmap) -> Unit,
) {
    if (editor.exporting) return
    val mask = editor.visuals?.mask ?: return
    val session = editor.session
    editor.exporting = true
    scope.launch {
        try {
            // The WHOLE body is guarded, not just the big decode: the editing-size fallback below
            // is reached precisely after that decode failed (usually memory pressure), so it is the
            // likeliest place for the next OutOfMemoryError, and one escaping scope.launch ends the
            // process rather than the export.
            val outcome = catchingResult {
                val cached = editor.exportCache?.takeIf { it.first === session }?.second
                val bitmap = cached ?: withContext(Dispatchers.Default) {
                    catchingResult {
                        val full = decodeUpright(context, asset.contentUri, EXPORT_DECODE_LIMITS)?.bitmap
                        full?.let { LiftRenderer.cutOut(it, mask)?.bitmap }
                    }.getOrNull() ?: LiftRenderer.cutOut(workspace.display, mask)?.bitmap
                }
                if (bitmap == null) {
                    editor.statusMessage = LiftFailure.NoSubject.userMessage()
                } else {
                    editor.exportCache = session to bitmap
                    action(StickerExporter(context), bitmap)
                }
            }
            outcome.onFailure { editor.statusMessage = classifyLiftFailure(it).userMessage() }
        } finally {
            editor.exporting = false
        }
    }
}

/** The editing-size cut-out shown in the result card; null while it is being built. */
@Composable
private fun rememberResultPreview(workspace: Workspace, mask: SubjectSegmentation.Mask?): ImageBitmap? {
    val preview = remember(workspace, mask) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(workspace, mask) {
        preview.value = if (mask == null) null else catchingResult {
            withContext(Dispatchers.Default) { LiftRenderer.cutOut(workspace.display, mask)?.bitmap?.asImageBitmap() }
        }.getOrNull()
    }
    return preview.value
}

// ---- state --------------------------------------------------------------------------------

internal enum class LiftTool { Select, Refine }

internal sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val failure: LiftFailure) : LoadState
    data class Ready(val workspace: Workspace) : LoadState
}

/** The decoded photo (for display and export) and its downscaled pixels (for the solver). */
internal class Workspace(
    val display: Bitmap,
    val displayImage: ImageBitmap,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

/** One solved selection with the pieces the editor draws from it. */
internal class MaskVisuals(
    val mask: SubjectSegmentation.Mask,
    val overlay: ImageBitmap,
    val hasSelection: Boolean,
)

private fun buildWorkspace(display: Bitmap): Workspace {
    val working = LiftRenderer.scaledToLongEdge(display, WORKING_LONG_EDGE_PX)
    val pixels = IntArray(working.width * working.height)
    working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
    val width = working.width
    val height = working.height
    if (working !== display) working.recycle()
    return Workspace(display, display.asImageBitmap(), pixels, width, height)
}

/**
 * Everything the editor remembers. A plain holder of Compose state rather than a pile of
 * `remember`ed locals so the chrome, the canvas and the export code all read and write one thing.
 * Not saved across process death or rotation: the history is cheap to redo and the alternative is
 * persisting a selection per photo, which nobody asked for.
 */
@Stable
internal class LiftEditorState {
    var load by mutableStateOf<LoadState>(LoadState.Loading)
    var session by mutableStateOf(SelectionSession())
    var visuals by mutableStateOf<MaskVisuals?>(null)
    var cutPreview by mutableStateOf<ImageBitmap?>(null)
    var solving by mutableStateOf(false)
    var solveFailure by mutableStateOf<LiftFailure?>(null)

    var tool by mutableStateOf(LiftTool.Select)
    var markSubject by mutableStateOf(true)
    var paintAdd by mutableStateOf(true)
    var brushRadius by mutableFloatStateOf(DEFAULT_REFINE_RADIUS)
    var previewCutout by mutableStateOf(false)

    var statusMessage by mutableStateOf<String?>(null)
    var exporting by mutableStateOf(false)
    var exportCache: Pair<SelectionSession, Bitmap>? = null

    /** The stroke being drawn right now, in canvas pixels. Cleared the moment the finger lifts. */
    val liveStroke = mutableStateListOf<Offset>()

    fun apply(op: SelectionOp) {
        session = session.apply(op)
    }
}

// ---- the canvas -----------------------------------------------------------------------------

@Composable
private fun LiftCanvas(editor: LiftEditorState, workspace: Workspace) {
    val checker = rememberCheckerBrush()
    val imageWidth = workspace.display.width
    val imageHeight = workspace.display.height

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // Fixed on purpose (semantic): an opaque near-black stage, so the editor reads the same
            // over any photo and on either theme, and the checkerboard is the only transparency cue.
            .background(Color(0xFF0B0B0B))
            .pointerInput(workspace) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val container = Size(size.width.toFloat(), size.height.toFloat())
                    val first = imagePointToNormalized(down.position, container, imageWidth, imageHeight, clamp = false)

                    val points = ArrayList<Offset>()
                    points.add(down.position)
                    var moved = false
                    if (first != null) {
                        editor.liveStroke.clear()
                        editor.liveStroke.add(down.position)
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val p = change.position
                            if (!moved && (p - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved && first != null && (p - points.last()).getDistance() >= MIN_POINT_SPACING_PX) {
                                points.add(p)
                                editor.liveStroke.add(p)
                            }
                        }
                    } finally {
                        editor.liveStroke.clear()
                    }

                    // A touch that began on the letterbox is swallowed and ignored: dismissing on
                    // it (the old behaviour) threw away work on a stray tap.
                    if (first == null) return@awaitEachGesture
                    val path = if (moved) {
                        points.mapNotNull { imagePointToNormalized(it, container, imageWidth, imageHeight, clamp = true) }
                    } else {
                        listOf(first)
                    }
                    if (path.isEmpty()) return@awaitEachGesture
                    editor.apply(
                        when (editor.tool) {
                            LiftTool.Select ->
                                SelectionOp.Hint(editor.markSubject, BrushStroke(path, SCRIBBLE_RADIUS))
                            LiftTool.Refine ->
                                SelectionOp.Paint(editor.paintAdd, BrushStroke(path, editor.brushRadius))
                        },
                    )
                }
            },
    ) {
        val rect = fittedImageRect(size, imageWidth, imageHeight)
        val dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
        val dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())

        val cut = editor.cutPreview
        if (editor.previewCutout && cut != null) {
            drawRect(brush = checker, topLeft = rect.topLeft, size = rect.size)
            drawImage(cut, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)
        } else {
            drawImage(workspace.displayImage, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)
            editor.visuals?.let {
                drawImage(it.overlay, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)
            }
        }

        val stroke = editor.liveStroke
        if (stroke.isNotEmpty()) {
            val radiusFraction = if (editor.tool == LiftTool.Select) SCRIBBLE_RADIUS else editor.brushRadius
            val widthPx = 2f * radiusFraction * max(rect.width, rect.height)
            val adding = if (editor.tool == LiftTool.Select) editor.markSubject else editor.paintAdd
            // Fixed on purpose (semantic): green = "this is subject / add", red = "this is not /
            // erase". Not accents, so they read the same under any theme and any photo.
            val colour = (if (adding) Color(0xFF4CAF50) else Color(0xFFF44336)).copy(alpha = 0.55f)
            if (stroke.size == 1) {
                drawCircle(colour, radius = widthPx / 2f, center = stroke[0])
            } else {
                val path = Path().apply {
                    moveTo(stroke[0].x, stroke[0].y)
                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                drawPath(path, colour, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---- constants ------------------------------------------------------------------------------

/**
 * The bound for [decodeUpright] when decoding for the editor: big enough to be a sharp stage to
 * work on, small enough to hold in memory next to the working copy and the preview bitmaps.
 */
private val LIFT_DECODE_LIMITS = DecodeLimits(maxLongEdge = 1024, maxPixels = 1024L * 1024L)

/** The bound for the one-off decode behind Copy / Share / Save: the sticker's real resolution. */
private val EXPORT_DECODE_LIMITS = DecodeLimits(
    maxLongEdge = StickerExporter.EXPORT_LONG_EDGE_PX,
    maxPixels = StickerExporter.EXPORT_LONG_EDGE_PX.toLong() * StickerExporter.EXPORT_LONG_EDGE_PX,
)

/**
 * Long edge of the copy the solver runs on. The geodesic solve is two Dijkstra sweeps over every
 * pixel: at 512 it answers in a fraction of a second (fast enough to re-run after every stroke
 * and every Undo), at 1024 it is four times the work for edges the 2048px export resamples from
 * the mask anyway.
 */
private const val WORKING_LONG_EDGE_PX = 512

/** Brush radius, as a fraction of the image's long edge, for tap and scribble hints. */
private const val SCRIBBLE_RADIUS = 0.02f
private const val DEFAULT_REFINE_RADIUS = 0.035f

/** Closest two recorded points of a stroke may be, in canvas pixels. */
private const val MIN_POINT_SPACING_PX = 4f

/** The selection wash: a fixed cyan at up to 45% (semantic, like the stroke colours above). */
private const val SELECTION_RGB = 0x2EC4FF
private const val SELECTION_MAX_ALPHA = 115

private const val STATUS_MESSAGE_MS = 2600L
