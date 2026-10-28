package com.fotoxplorr.app.board

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The arithmetic of a finger on the board, without a finger: how big the board is on the screen, how
 * a screen point becomes a canvas point, and what a pinch or a twist does to a layer. The pointer
 * code in the canvas view only reads the events and calls these, so the sign of a turn and the
 * point a pinch holds still are decided and tested here.
 */
object BoardGestures {

    /** How a board of [canvas] is placed in a box [boxW] x [boxH] px: [pxPerUnit], and the size it takes. */
    class Fit(val pxPerUnit: Float, val widthPx: Float, val heightPx: Float)

    /** The largest placement that keeps the whole board in the box (it never crops, never stretches). */
    fun fit(canvas: CanvasSize, boxW: Float, boxH: Float): Fit {
        val w = boxW.coerceAtLeast(1f)
        val h = boxH.coerceAtLeast(1f)
        val k = min(w / canvas.width, h / canvas.height)
        return Fit(k, canvas.width * k, canvas.height * k)
    }

    /** A screen distance of [px] pixels as canvas units. */
    fun units(px: Float, pxPerUnit: Float): Float = if (pxPerUnit > 0f) px / pxPerUnit else 0f

    /**
     * The long edge to draw a preview at for a board shown [fit]-ted on the screen: the shown size,
     * rounded up to a multiple of 64 so a few pixels of layout change do not redraw it, and held to
     * [MIN_PREVIEW_EDGE]..[MAX_PREVIEW_EDGE].
     */
    fun previewEdge(fit: Fit): Int {
        val shown = kotlin.math.max(fit.widthPx, fit.heightPx)
        val rounded = (kotlin.math.ceil(shown / 64f) * 64f).toInt()
        return rounded.coerceIn(MIN_PREVIEW_EDGE, MAX_PREVIEW_EDGE)
    }

    /**
     * [t] after a pinch and twist about the canvas point [centroid]: the fingers' middle moved by
     * ([panX], [panY]) units, their spread grew by [zoom] and they turned by [rotationDeg] (clockwise
     * positive, the same sign as the layer's own turn). The layer's centre is carried round the middle
     * like a point drawn on the same sheet, so what was under the fingers stays under them. A one
     * finger drag is the same call with a zoom of 1 and a turn of 0. The result is not clamped; the
     * edit holds it to [BoardLimits].
     */
    fun transformAbout(t: Transform, centroid: Pt, panX: Float, panY: Float, zoom: Float, rotationDeg: Float): Transform {
        val r = Math.toRadians(rotationDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        val rx = t.cx - centroid.x
        val ry = t.cy - centroid.y
        return Transform(
            cx = centroid.x + panX + zoom * (rx * cs - ry * sn),
            cy = centroid.y + panY + zoom * (rx * sn + ry * cs),
            scale = t.scale * zoom,
            rotationDeg = t.rotationDeg + rotationDeg,
        )
    }

    /**
     * What a finger gesture is allowed to do to the snapped result of a drag: the position pulled to
     * a guide when one finger moved the layer, and the turn pulled to a step when it was turned.
     * [moveOnly] is true while only one finger has touched down; once a second has, the layer is
     * being pinched or turned and its position is the person's alone.
     */
    fun settle(board: Board, id: Long, raw: Transform, turned: Boolean, moveOnly: Boolean, snap: SnapOptions?): Settled {
        var t = raw
        var guides = emptyList<SnapGuide>()
        if (turned) t = t.copy(rotationDeg = BoardSnap.snapRotation(t.rotationDeg))
        if (moveOnly && snap != null) {
            val s = BoardSnap.snapMove(board, id, t.cx, t.cy, snap)
            t = t.copy(cx = s.cx, cy = s.cy)
            guides = s.guides
        }
        return Settled(t, guides)
    }

    /**
     * Whether a gesture's net twist ([netDegrees], signed, summed over the gesture) is a turn the person made.
     * The sum is signed so the small back-and-forth of two fingers pinching cancels out and a pure zoom never
     * counts, and the layer's angle is pulled to a step only once it does.
     */
    fun turnNoticed(netDegrees: Float): Boolean = abs(netDegrees) > TURN_NOTICED_DEG

    class Settled(val transform: Transform, val guides: List<SnapGuide>)

    /** A touch counts as a gesture, not a tap, after it has moved this far (dp). */
    const val TAP_SLOP_DP = 8f

    /** The reach of a tap beyond a layer's edge (dp): a thin layer can still be hit with a finger. */
    const val HIT_SLOP_DP = 10f

    /** How close a layer must come to a guide to be pulled to it (dp). */
    const val SNAP_DP = 8f

    /** A twist of this many degrees or more, net, is a turn (not the jitter of a pinch). */
    const val TURN_NOTICED_DEG = 4f

    const val MIN_PREVIEW_EDGE = 256
    const val MAX_PREVIEW_EDGE = 1280
}
