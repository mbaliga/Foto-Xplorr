package com.fotoxplorr.app.board

import kotlin.math.cos
import kotlin.math.sin

/** A point on the canvas, in units. */
data class Pt(val x: Float, val y: Float)

/** An axis-aligned rectangle on the canvas, in units. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    fun shifted(dx: Float, dy: Float): Bounds = Bounds(left + dx, top + dy, right + dx, bottom + dy)
    fun intersects(other: Bounds): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Where a layer is on the canvas and which layer a finger is on. A layer is its box (width x height,
 * enlarged by the scale) turned about its centre; the maths here is the inverse of what the renderer
 * does, and the tests hold the two to the same corners.
 */
object BoardGeometry {

    /** The four corners of the drawn box, clockwise from the top left of the unturned box. */
    fun corners(layer: Layer): List<Pt> {
        val hw = layer.drawnWidth / 2f
        val hh = layer.drawnHeight / 2f
        return listOf(
            toCanvas(layer, -hw, -hh),
            toCanvas(layer, hw, -hh),
            toCanvas(layer, hw, hh),
            toCanvas(layer, -hw, hh),
        )
    }

    /** The smallest upright rectangle that holds the turned box. */
    fun bounds(layer: Layer): Bounds {
        val c = corners(layer)
        return Bounds(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
    }

    /** A point given from the box centre along the box's own (turned and enlarged) axes, put on the canvas. */
    private fun toCanvas(layer: Layer, ox: Float, oy: Float): Pt {
        val t = layer.transform
        val r = Math.toRadians(t.rotationDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        return Pt(t.cx + ox * cs - oy * sn, t.cy + ox * sn + oy * cs)
    }

    /** The canvas point for the box-local share ([u], [v]) (0..1 across and down the unturned box). */
    fun pointOf(layer: Layer, u: Float, v: Float): Pt =
        toCanvas(layer, (u - 0.5f) * layer.drawnWidth, (v - 0.5f) * layer.drawnHeight)

    /**
     * The box-local position of canvas point ([x], [y]) as shares of the box (0..1 inside), or null
     * for a box with no size. Outside the box the shares leave 0..1.
     */
    fun localShare(layer: Layer, x: Float, y: Float): Pt? {
        val dw = layer.drawnWidth
        val dh = layer.drawnHeight
        if (dw <= 0f || dh <= 0f) return null
        val t = layer.transform
        val r = Math.toRadians(t.rotationDeg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        val dx = x - t.cx
        val dy = y - t.cy
        // The inverse of a turn is the turn the other way.
        val lx = dx * cs + dy * sn
        val ly = -dx * sn + dy * cs
        return Pt(lx / dw + 0.5f, ly / dh + 0.5f)
    }

    /** True when the canvas point is in the (turned) box, widened by [slop] units on every side. */
    fun contains(layer: Layer, x: Float, y: Float, slop: Float = 0f): Boolean {
        val s = localShare(layer, x, y) ?: return false
        val sx = slop / layer.drawnWidth
        val sy = slop / layer.drawnHeight
        return s.x >= -sx && s.x <= 1f + sx && s.y >= -sy && s.y <= 1f + sy
    }

    /**
     * True when a tap at ([x], [y]) lands on what the layer shows: inside the (widened) box and,
     * when [respectMask], on a part the mask shows. In the margin that [slop] adds, the mask is
     * asked at the nearest point of the box and the tap counts if that point is no more than [slop]
     * outside the shape, so a thin layer or one with a soft edge can still be hit by a finger just
     * beside it (a soft edge shows nothing AT the edge, which would otherwise refuse the margin).
     * A [MaskShape.Subject] counts as the whole box (its shape is only known after rendering).
     */
    fun hits(layer: Layer, x: Float, y: Float, slop: Float = 0f, respectMask: Boolean = true): Boolean {
        if (!contains(layer, x, y, slop)) return false
        if (!respectMask || layer.mask.isNone) return true
        val s = localShare(layer, x, y) ?: return false
        val u = s.x.coerceIn(0f, 1f)
        val v = s.y.coerceIn(0f, 1f)
        val inMargin = u != s.x || v != s.y
        if (!inMargin) return MaskGeometry.shows(layer.mask, layer.width, layer.height, u * layer.width, v * layer.height)
        val d = MaskGeometry.signedDistance(layer.mask.shape, layer.width, layer.height, u * layer.width, v * layer.height)
        return d >= -slop / layer.transform.scale
    }

    /**
     * The layers a tap at ([x], [y]) lands on, topmost first. Locked layers are left out unless
     * [includeLocked]: a locked layer cannot be grabbed, so a tap goes through to what is under it.
     */
    fun hitStack(
        board: Board,
        x: Float,
        y: Float,
        slop: Float = 0f,
        includeLocked: Boolean = false,
        respectMask: Boolean = true,
    ): List<Long> = board.layers.asReversed()
        .filter { (includeLocked || !it.locked) && hits(it, x, y, slop, respectMask) }
        .map { it.id }

    /** The topmost layer under ([x], [y]), or null. */
    fun hitTest(
        board: Board,
        x: Float,
        y: Float,
        slop: Float = 0f,
        includeLocked: Boolean = false,
        respectMask: Boolean = true,
    ): Long? = hitStack(board, x, y, slop, includeLocked, respectMask).firstOrNull()

    /**
     * Whether any of the layer is on the canvas; one that is entirely off it need not be decoded or drawn.
     * The turned box itself is tested, not the upright rectangle around it, so a turned layer whose corner
     * of that rectangle reaches the canvas but which does not is off it.
     */
    fun isOnCanvas(layer: Layer, canvas: CanvasSize): Boolean {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (!bounds(layer).intersects(Bounds(0f, 0f, w, h))) return false
        // Separating axes of the box's own: if the canvas corners are all beyond one side of the box, they do not meet.
        val shares = listOf(localShare(layer, 0f, 0f), localShare(layer, w, 0f), localShare(layer, w, h), localShare(layer, 0f, h))
        if (shares.any { it == null }) return true
        if (shares.all { it!!.x >= 1f } || shares.all { it!!.x <= 0f }) return false
        if (shares.all { it!!.y >= 1f } || shares.all { it!!.y <= 0f }) return false
        return true
    }

    /** The position that puts the layer's centre where [x], [y] is, keeping its grab point: the move by a drag. */
    fun movedBy(t: Transform, dx: Float, dy: Float): Transform = t.copy(cx = t.cx + dx, cy = t.cy + dy)

    /** The angle in degrees, clockwise from straight right, of the line from the layer's centre to ([x], [y]). */
    fun angleTo(t: Transform, x: Float, y: Float): Float =
        Math.toDegrees(kotlin.math.atan2((y - t.cy).toDouble(), (x - t.cx).toDouble())).toFloat()
}
