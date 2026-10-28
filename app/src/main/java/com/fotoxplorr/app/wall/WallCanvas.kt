package com.fotoxplorr.app.wall

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.clearOfShellEdges

/** The whole canvas, for a test that touches it by absolute position. */
internal const val WALL_CANVAS_TAG = "wall-canvas"

private val MAT_COLOR = Color(0xFFF3F1EA)
private val MISSING_COLOR = Color(0xFF3A3A3A)

/** How far (dp) from a frame a finger may land and still take it: small prints are a few dp wide. */
private const val GRAB_SLOP_DP = 24f

/**
 * The wall photo with the frames hung on it, and the touch layer over them.
 *
 * The photo and frames are drawn across the whole area. In [WallStep.HANG] only the TOUCH layer is
 * inset with [clearOfShellEdges] (TRAPS #72), because it is what takes drags: a drag that starts
 * within the shell's edge zone would open a room instead. A frame hanging wholly inside an edge zone
 * cannot be grabbed, and the person selects it from the list of pictures and moves it with the buttons.
 *
 * In [WallStep.SCALE] a tap marks an end of the reference ([onMark] gets the photo's own pixel), and
 * the layer covers the whole canvas: a tap is no drag, and a door or sheet at the photo's border has
 * to be markable. (Whether the shell still reads a tap on its edge zone is a device check.)
 * In [WallStep.HANG] one finger drags the frame it lands on, and two fingers pinch and twist the
 * selected frame wherever they are; a gesture that moved, sized or turned a frame ends with it on a
 * standard size and level when close to them ([WallView.settled]), a plain tap changes nothing.
 * Nothing here has been tried on a device.
 */
@Composable
internal fun WallCanvas(
    wall: ImageBitmap?,
    draft: WallDraft,
    pictures: Map<Long, HungPicture?>,
    marks: List<Offset>,
    onMark: (Offset) -> Unit,
    onDraft: (WallDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    var boxRoot by remember { mutableStateOf(Offset.Zero) }
    var layerRoot by remember { mutableStateOf(Offset.Zero) }
    val view = remember(wall, area, draft.wallWidthCm) {
        WallView(wall?.width ?: 0, wall?.height ?: 0, area.width.toFloat(), area.height.toFloat(), draft.wallWidthCm)
    }
    val latestView by rememberUpdatedState(view)
    val latestDraft by rememberUpdatedState(draft)
    val latestOnMark by rememberUpdatedState(onMark)
    val latestOnDraft by rememberUpdatedState(onDraft)

    Box(
        modifier
            .testTag(WALL_CANVAS_TAG)
            .onGloballyPositioned {
                boxRoot = it.positionInRoot()
                area = it.size
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = WallCopy.canvasDescription(draft.frames.size) },
        ) {
            if (wall != null && view.scale > 0f) {
                drawImage(
                    wall,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(wall.width, wall.height),
                    dstOffset = IntOffset(view.offX.toInt(), view.offY.toInt()),
                    dstSize = IntSize((wall.width * view.scale).toInt(), (wall.height * view.scale).toInt()),
                    filterQuality = FilterQuality.Medium,
                )
                if (draft.step == WallStep.SCALE) drawMarks(marks, view) else drawFrames(draft, pictures, view)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (draft.step == WallStep.HANG) Modifier.clearOfShellEdges() else Modifier)
                .onGloballyPositioned { layerRoot = it.positionInRoot() }
                .pointerInput(draft.step) {
                    val shift = { layerRoot - boxRoot }
                    if (draft.step == WallStep.SCALE) {
                        detectTapGestures { p ->
                            val at = p + shift()
                            val v = latestView
                            if (v.contains(at.x, at.y)) {
                                val (bx, by) = v.toBitmap(at.x, at.y)
                                latestOnMark(Offset(bx, by))
                            }
                        }
                    } else {
                        hangGestures(
                            view = { latestView },
                            draft = { latestDraft },
                            shift = shift,
                            slopPx = GRAB_SLOP_DP * density,
                            touchSlopPx = viewConfiguration.touchSlop,
                            onDraft = { latestOnDraft(it) },
                        )
                    }
                },
        )
    }
}

/** One finger drags the frame it lands on; two pinch and twist the selected one. */
private suspend fun PointerInputScope.hangGestures(
    view: () -> WallView,
    draft: () -> WallDraft,
    shift: () -> Offset,
    slopPx: Float,
    touchSlopPx: Float,
    onDraft: (WallDraft) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val v = view()
        var d = draft()
        if (v.pxPerCm <= 0f) return@awaitEachGesture
        val at = down.position + shift()
        val hit = v.frameAt(d.frames, d.borderCm, slopPx / v.pxPerCm, at.x, at.y)
        if (hit != null && hit != d.selected) {
            d = d.copy(selected = hit)
            onDraft(d)
        }
        var target: Int? = null
        // Whether the person really moved, sized or turned a frame, as against a tap with a finger that
        // wobbled: only then does the gesture end on a standard size and level.
        var travelled = Offset.Zero
        var changed = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            target = hit ?: d.selected.takeIf { pressed >= 2 && it in d.frames.indices } ?: target
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            val twist = event.calculateRotation()
            val moving = target
            if (moving != null && (pan != Offset.Zero || zoom != 1f || twist != 0f)) {
                d = d.withFrame(moving) { v.transformed(it, pan.x, pan.y, zoom, twist) }
                onDraft(d)
                travelled += pan
                if (zoom != 1f || twist != 0f || travelled.getDistance() > touchSlopPx) changed = true
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        target?.let { done -> if (changed) onDraft(d.withFrame(done) { WallView.settled(it) }) }
    }
}

private fun DrawScope.drawMarks(marks: List<Offset>, view: WallView) {
    val points = marks.map { Offset(view.offX + it.x * view.scale, view.offY + it.y * view.scale) }
    if (points.size == 2) {
        drawLine(Color.Black, points[0], points[1], strokeWidth = 5.dp.toPx())
        drawLine(Color.White, points[0], points[1], strokeWidth = 2.dp.toPx())
    }
    points.forEach {
        drawCircle(Color.Black, 9.dp.toPx(), it)
        drawCircle(Color.White, 6.dp.toPx(), it)
    }
}

private fun DrawScope.drawFrames(draft: WallDraft, pictures: Map<Long, HungPicture?>, view: WallView) {
    if (view.pxPerCm <= 0f) return
    val ppc = view.pxPerCm
    val mat = draft.matCm * ppc
    val mold = draft.style.moldingCm * ppc
    val border = mat + mold
    draft.frames.forEachIndexed { index, f ->
        val (cx, cy) = view.toArea(f.cx, f.cy)
        val centre = Offset(cx, cy)
        val pw = f.wCm * ppc
        val ph = f.hCm * ppc
        val outer = Size(pw + 2 * border, ph + 2 * border)
        val outerTopLeft = Offset(cx - outer.width / 2, cy - outer.height / 2)
        rotate(f.rotationDeg, pivot = centre) {
            drawRect(Color.Black.copy(alpha = 0.35f), outerTopLeft + Offset(3.dp.toPx(), 4.dp.toPx()), outer)
            if (mold > 0f) drawRect(Color(draft.style.argb), outerTopLeft, outer)
            if (mat > 0f) drawRect(MAT_COLOR, outerTopLeft + Offset(mold, mold), Size(outer.width - 2 * mold, outer.height - 2 * mold))
            val pictureTopLeft = Offset(cx - pw / 2, cy - ph / 2)
            val image = pictures[f.assetId]?.image
            if (image != null) {
                val crop = PrintMath.coverCrop(image.width, image.height, f.wCm.toDouble(), f.hCm.toDouble())
                val keptW = (image.width * crop.keptWidth).toInt().coerceAtLeast(1)
                val keptH = (image.height * crop.keptHeight).toInt().coerceAtLeast(1)
                drawImage(
                    image,
                    srcOffset = IntOffset((image.width - keptW) / 2, (image.height - keptH) / 2),
                    srcSize = IntSize(keptW, keptH),
                    dstOffset = IntOffset(pictureTopLeft.x.toInt(), pictureTopLeft.y.toInt()),
                    dstSize = IntSize(pw.toInt().coerceAtLeast(1), ph.toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                drawRect(MISSING_COLOR, pictureTopLeft, Size(pw, ph))
            }
            if (index == draft.selected) drawSelection(outerTopLeft, outer)
        }
    }
}

/**
 * The chosen frame: a dark line under a white dashed one (seen on any wall) and a square on each
 * corner. A shape, so the choice does not rest on colour.
 */
private fun DrawScope.drawSelection(topLeft: Offset, size: Size) {
    val grow = 3.dp.toPx()
    val tl = topLeft - Offset(grow, grow)
    val sz = Size(size.width + 2 * grow, size.height + 2 * grow)
    drawRect(Color.Black, tl, sz, style = Stroke(width = 4.dp.toPx()))
    drawRect(Color.White, tl, sz, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
    val handle = 7.dp.toPx()
    listOf(tl, tl + Offset(sz.width, 0f), tl + Offset(0f, sz.height), tl + Offset(sz.width, sz.height)).forEach {
        drawRect(Color.Black, it - Offset(handle / 2 + 1.dp.toPx(), handle / 2 + 1.dp.toPx()), Size(handle + 2.dp.toPx(), handle + 2.dp.toPx()))
        drawRect(Color.White, it - Offset(handle / 2, handle / 2), Size(handle, handle))
    }
}
