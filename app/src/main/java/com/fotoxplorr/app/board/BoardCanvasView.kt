package com.fotoxplorr.app.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp
import com.fotoxplorr.app.lift.rememberCheckerBrush
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** What a touch on the board means right now. */
internal sealed interface CanvasMode {
    /** Pick, drag, pinch and turn layers. */
    data object Edit : CanvasMode

    /** Taps place the corner points of a mask outline on the picked layer; [points] are those so far. */
    class Corners(val points: List<NormPoint>) : CanvasMode

    /** Taps mark the subject of the picked photo ([foreground]) or what is not the subject. */
    class Marks(val foreground: Boolean) : CanvasMode
}

/**
 * The board on the screen: the picture of it ([preview], drawn by [BoardRenderer]) with the picked
 * layer's frame, the snapping guides and the marks drawn over it from the board itself, so the frame
 * and the guides are exact while the picture catches up a moment behind.
 *
 * Touch (in [CanvasMode.Edit]):
 *  - A tap picks the topmost layer under the finger, or within a finger's width of its edge (a locked one is not
 *    picked here; Layers picks it) or, on empty board, nothing.
 *  - One finger dragged from a layer moves it: the picked layer if the finger is on it, otherwise the
 *    topmost layer under the finger, which becomes the picked one. The move snaps to the guides when
 *    [snap] is on.
 *  - Two fingers pinch, twist and move the PICKED layer, about the point between them, wherever on
 *    the board they are. The turn snaps to 15 degree steps near one.
 *  - Every move is the same arithmetic as the buttons ([BoardGestures.transformAbout]) and one
 *    gesture is one undo step. A finger down never changes anything until it has moved a touch-slop.
 * In [CanvasMode.Corners] and [CanvasMode.Marks] a tap is reported to [onPoint] and nothing moves.
 *
 * The board is placed to fit the room it is given ([onFit] reports the placement, so the screen can
 * ask for a picture of the right size). Horizontal drags that begin within the shell's edge zones
 * never reach this view (TRAPS #72); the screen keeps it clear of them. Nothing here has run on a
 * device: how the drag feels, and whether a twist is easy to make with the thumb and finger, are
 * device checks.
 */
@Composable
internal fun BoardCanvasView(
    board: Board,
    selectedId: Long?,
    preview: ImageBitmap?,
    snap: Boolean,
    mode: CanvasMode,
    description: String,
    onTap: (x: Float, y: Float, slop: Float) -> Unit,
    onPick: (Long) -> Unit,
    onTransform: (id: Long, transform: Transform, gestureKey: String) -> Unit,
    onPoint: (x: Float, y: Float) -> Unit,
    onFit: (BoardGestures.Fit) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val fit = remember(board.canvas, boxW, boxH) { BoardGestures.fit(board.canvas, boxW, boxH) }
        LaunchedEffect(fit.widthPx, fit.heightPx, fit.pxPerUnit) { onFit(fit) }

        val latestBoard by rememberUpdatedState(board)
        val latestSelected by rememberUpdatedState(selectedId)
        val latestSnap by rememberUpdatedState(snap)
        val latestMode by rememberUpdatedState(mode)
        val latestTap by rememberUpdatedState(onTap)
        val latestPick by rememberUpdatedState(onPick)
        val latestTransform by rememberUpdatedState(onTransform)
        val latestPoint by rememberUpdatedState(onPoint)
        var guides by remember { mutableStateOf<List<SnapGuide>>(emptyList()) }
        val checker = rememberCheckerBrush(cell = 10.dp)

        val widthDp = with(density) { fit.widthPx.toDp() }
        val heightDp = with(density) { fit.heightPx.toDp() }
        Box(
            Modifier
                .size(widthDp, heightDp)
                .semantics { contentDescription = description }
                .then(
                    if (board.background is BoardBackground.Transparent) Modifier.background(checker)
                    else Modifier.background(Color((board.background as BoardBackground.Colour).argb)),
                )
                .pointerInput(fit.pxPerUnit) {
                    boardGestures(
                        pxPerUnit = fit.pxPerUnit,
                        board = { latestBoard },
                        selectedId = { latestSelected },
                        snap = { latestSnap },
                        mode = { latestMode },
                        onTap = { x, y, slop -> latestTap(x, y, slop) },
                        onPoint = { x, y -> latestPoint(x, y) },
                        onPick = { latestPick(it) },
                        onTransform = { id, t, key -> latestTransform(id, t, key) },
                        onGuides = { guides = it },
                    )
                },
        ) {
            if (preview != null) {
                Image(bitmap = preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Canvas(Modifier.fillMaxSize()) {
                drawOverlay(board, selectedId, mode, guides, fit.pxPerUnit)
            }
        }
    }
}

// ---- the pointer loop ---------------------------------------------------------------------------

/** One number for every gesture of the process, so two gestures never share an undo step by sharing a key. */
private val gestureCounter = AtomicInteger()

private suspend fun PointerInputScope.boardGestures(
    pxPerUnit: Float,
    board: () -> Board,
    selectedId: () -> Long?,
    snap: () -> Boolean,
    mode: () -> CanvasMode,
    onTap: (Float, Float, Float) -> Unit,
    onPoint: (Float, Float) -> Unit,
    onPick: (Long) -> Unit,
    onTransform: (Long, Transform, String) -> Unit,
    onGuides: (List<SnapGuide>) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val hitSlopUnits = BoardGestures.units(BoardGestures.HIT_SLOP_DP * density, pxPerUnit)
    val snapUnits = BoardGestures.units(BoardGestures.SNAP_DP * density, pxPerUnit)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downX = down.position.x / pxPerUnit
        val downY = down.position.y / pxPerUnit
        val editing = mode() is CanvasMode.Edit
        var pastSlop = false
        var cancelled = false
        var accPan = Offset.Zero
        var accZoom = 1f
        var accRotation = 0f
        var maxPointers = 1
        var netTurn = 0f
        var target: Long? = null
        var raw: Transform? = null
        val key = "gesture:${gestureCounter.incrementAndGet()}"

        do {
            val event = awaitPointerEvent()
            maxPointers = max(maxPointers, event.changes.count { it.pressed })
            cancelled = event.changes.any { it.isConsumed }
            if (cancelled) break
            var zoom = event.calculateZoom()
            var rotation = event.calculateRotation()
            var pan = event.calculatePan()
            if (!pastSlop) {
                accZoom *= zoom
                accRotation += rotation
                accPan += pan
                val size = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - accZoom) * size
                val rotationMotion = abs(accRotation * PI.toFloat() / 180f) * size
                if (zoomMotion > slop || rotationMotion > slop || accPan.getDistance() > slop) {
                    pastSlop = true
                    // What gathered while under the slop is the first move.
                    zoom = accZoom
                    rotation = accRotation
                    pan = accPan
                    if (editing) {
                        val b = board()
                        target = if (maxPointers >= 2) {
                            selectedId()?.takeIf { id -> b.layer(id)?.locked == false }
                        } else {
                            val stack = BoardGeometry.hitStack(b, downX, downY, hitSlopUnits)
                            val picked = selectedId()
                            (if (picked != null && picked in stack) picked else stack.firstOrNull())?.also { if (it != picked) onPick(it) }
                        }
                        raw = target?.let { id -> b.layer(id)?.transform }
                    }
                }
            }
            if (pastSlop && editing) {
                val id = target
                val start = raw
                if (id != null && start != null && board().layer(id) != null) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val about = if (centroid.isSpecified) Pt(centroid.x / pxPerUnit, centroid.y / pxPerUnit) else Pt(start.cx, start.cy)
                    val next = BoardGestures.transformAbout(start, about, pan.x / pxPerUnit, pan.y / pxPerUnit, zoom, rotation)
                    raw = next
                    netTurn += rotation
                    val settled = BoardGestures.settle(
                        board(), id, next,
                        turned = BoardGestures.turnNoticed(netTurn),
                        moveOnly = maxPointers == 1,
                        snap = if (snap()) SnapOptions(threshold = snapUnits) else null,
                    )
                    onGuides(settled.guides)
                    onTransform(id, settled.transform, key)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })

        onGuides(emptyList())
        // A touch that never moved a slop and never had a second finger is a tap.
        if (!cancelled && !pastSlop && maxPointers == 1) {
            if (editing) onTap(downX, downY, hitSlopUnits) else onPoint(downX, downY)
        }
    }
}

// ---- the overlay ----------------------------------------------------------------------------------

private val FRAME_LIGHT = Color.White
private val FRAME_DARK = Color(0xFF202020)
private val GUIDE = Color(0xFFFF4081)
private val FOREGROUND_MARK = Color(0xFF00E676)
private val BACKGROUND_MARK = Color(0xFFFF5252)

private fun DrawScope.drawOverlay(board: Board, selectedId: Long?, mode: CanvasMode, guides: List<SnapGuide>, pxPerUnit: Float) {
    val layer = selectedId?.let { board.layer(it) }
    if (layer != null) {
        val path = Path()
        BoardGeometry.corners(layer).forEachIndexed { i, p ->
            val x = p.x * pxPerUnit
            val y = p.y * pxPerUnit
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // A white line with a dark dashed one on it, so the frame shows on a light photo and a dark one.
        drawPath(path, FRAME_LIGHT, style = Stroke(width = 3.dp.toPx()))
        drawPath(path, FRAME_DARK, style = Stroke(width = 1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))))
        BoardGeometry.corners(layer).forEach { p ->
            val c = Offset(p.x * pxPerUnit, p.y * pxPerUnit)
            drawCircle(FRAME_LIGHT, 5.dp.toPx(), c)
            drawCircle(FRAME_DARK, 5.dp.toPx(), c, style = Stroke(1.5f.dp.toPx()))
        }
        if (mode is CanvasMode.Corners) drawCorners(layer, mode.points, pxPerUnit)
        if (mode is CanvasMode.Marks) drawMarks(layer, pxPerUnit)
    }
    for (g in guides) drawGuide(g, pxPerUnit)
}

private fun DrawScope.drawGuide(g: SnapGuide, pxPerUnit: Float) {
    val at = g.at * pxPerUnit
    val from = g.from * pxPerUnit
    val to = g.to * pxPerUnit
    val a = if (g.vertical) Offset(at, from) else Offset(from, at)
    val b = if (g.vertical) Offset(at, to) else Offset(to, at)
    val width = if (g.kind == GuideKind.SPACING) 2.dp.toPx() else 1.5f.dp.toPx()
    drawLine(GUIDE, a, b, strokeWidth = width, cap = StrokeCap.Round)
    if (g.kind == GuideKind.SPACING) {
        // A gap is told by its end caps.
        val cap = 5.dp.toPx()
        if (g.vertical) {
            drawLine(GUIDE, Offset(at - cap, from), Offset(at + cap, from), strokeWidth = width)
            drawLine(GUIDE, Offset(at - cap, to), Offset(at + cap, to), strokeWidth = width)
        } else {
            drawLine(GUIDE, Offset(from, at - cap), Offset(from, at + cap), strokeWidth = width)
            drawLine(GUIDE, Offset(to, at - cap), Offset(to, at + cap), strokeWidth = width)
        }
    }
}

private fun DrawScope.drawCorners(layer: Layer, points: List<NormPoint>, pxPerUnit: Float) {
    val canvasPoints = points.map { BoardGeometry.pointOf(layer, it.x, it.y) }.map { Offset(it.x * pxPerUnit, it.y * pxPerUnit) }
    for (i in 0 until canvasPoints.size - 1) drawLine(FRAME_LIGHT, canvasPoints[i], canvasPoints[i + 1], strokeWidth = 2.dp.toPx())
    canvasPoints.forEachIndexed { i, c ->
        // The first corner is a ring, so the way round is clear.
        drawCircle(FRAME_LIGHT, 7.dp.toPx(), c)
        drawCircle(FRAME_DARK, 7.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        if (i == 0) drawCircle(FRAME_DARK, 3.dp.toPx(), c)
    }
}

private fun DrawScope.drawMarks(layer: Layer, pxPerUnit: Float) {
    val ops = (layer.mask.shape as? MaskShape.Subject)?.ops.orEmpty()
    for (op in ops) {
        val hint = op as? SelectionOp.Hint ?: continue
        val p = BoardEditing.markInBox(layer, hint) ?: continue
        val at = BoardGeometry.pointOf(layer, p.x, p.y)
        val c = Offset(at.x * pxPerUnit, at.y * pxPerUnit)
        if (hint.foreground) {
            // The subject: a filled dot.
            drawCircle(FRAME_LIGHT, 8.dp.toPx(), c)
            drawCircle(FOREGROUND_MARK, 6.dp.toPx(), c)
        } else {
            // Not the subject: a cross, a different shape as well as a different colour.
            val r = 6.dp.toPx()
            drawLine(FRAME_LIGHT, c + Offset(-r, -r), c + Offset(r, r), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(FRAME_LIGHT, c + Offset(-r, r), c + Offset(r, -r), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(BACKGROUND_MARK, c + Offset(-r, -r), c + Offset(r, r), strokeWidth = 2.5f.dp.toPx(), cap = StrokeCap.Round)
            drawLine(BACKGROUND_MARK, c + Offset(-r, r), c + Offset(r, -r), strokeWidth = 2.5f.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
