package com.fotoxplorr.app.editor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Hit-testing and coordinate maths for a finger on the curve graph, in PIXELS.
 *
 * The old editor hit-tested in normalised space with one radius for both axes. On a graph that is
 * wider than it is tall that is an ellipse, and at 0.08 it came to about 26dp across and 18dp up
 * and down -- under the 24dp a fingertip needs. Doing it in pixels makes the touch target a true
 * circle whatever shape the canvas is, and lets the radius be stated in dp.
 *
 * Pure Kotlin so it is testable on the JVM; [CurvesEditor] only converts dp to px and feeds events in.
 */
object CurveHit {

    /** Radius of the circular touch target around a control point, in dp. */
    const val TOUCH_RADIUS_DP = 24f

    /** How far outside the graph a drag may travel, in dp, before releasing there deletes the point. */
    const val DRAG_OUT_MARGIN_DP = 32f

    /** Graph y grows upward; canvas y grows downward. Both helpers keep that flip in one place. */
    fun toPixelX(x: Float, widthPx: Float): Float = x * widthPx
    fun toPixelY(y: Float, heightPx: Float): Float = (1f - y) * heightPx
    fun toNormX(xPx: Float, widthPx: Float): Float = if (widthPx <= 0f) 0f else (xPx / widthPx).coerceIn(0f, 1f)
    fun toNormY(yPx: Float, heightPx: Float): Float = if (heightPx <= 0f) 0f else (1f - yPx / heightPx).coerceIn(0f, 1f)

    /**
     * The index of the control point nearest ([xPx], [yPx]) and within [radiusPx] of it, else null.
     * With two points inside the radius the nearer one wins. A zero-sized canvas has nothing to hit.
     */
    fun nearest(
        points: List<CurvePoint>,
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
    ): Int? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for (index in points.indices) {
            val distance = hypot(
                toPixelX(points[index].x, widthPx) - xPx,
                toPixelY(points[index].y, heightPx) - yPx,
            )
            if (distance <= radiusPx && distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Distance in pixels from ([xPx], [yPx]) to the polyline [lut] draws on a [widthPx] x
     * [heightPx] canvas -- the "segment" half of hit-testing: a touch near the line, but not near
     * a point, is a request to put a point ON the line.
     */
    fun distanceToCurve(lut: IntArray, xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Float {
        if (lut.size < 2 || widthPx <= 0f || heightPx <= 0f) return Float.MAX_VALUE
        val stepX = widthPx / (lut.size - 1)
        var best = Float.MAX_VALUE
        for (i in 0 until lut.size - 1) {
            val d = distanceToSegment(
                xPx, yPx,
                i * stepX, toPixelY(lut[i] / 255f, heightPx),
                (i + 1) * stepX, toPixelY(lut[i + 1] / 255f, heightPx),
            )
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return sqrt((px - (ax + t * dx)).let { it * it } + (py - (ay + t * dy)).let { it * it })
    }

    /** True when ([xPx], [yPx]) is more than [marginPx] outside the [widthPx] x [heightPx] graph. */
    fun isDraggedOut(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float, marginPx: Float): Boolean =
        xPx < -marginPx || xPx > widthPx + marginPx || yPx < -marginPx || yPx > heightPx + marginPx

    /**
     * What a touch-down at ([xPx], [yPx]) grabs. [offsetXPx]/[offsetYPx] are the finger's offset
     * from the point's centre at the moment of grabbing, so the drag can move the point by the
     * finger's DELTA rather than snapping its centre under the fingertip (which would hide it
     * and make every grab jump). [existed] is false when this touch created the point.
     */
    data class Grab(
        val curve: ToneCurve,
        val index: Int,
        val offsetXPx: Float,
        val offsetYPx: Float,
        val existed: Boolean,
    )

    /**
     * Decide what a touch-down does, in this order:
     *  1. on a control point -- grab it;
     *  2. near the curve -- insert a point ON the curve (shape all but unchanged), grab that;
     *  3. anywhere else -- insert a point under the finger, which bends the curve toward it;
     *  4. insertion refused ([ToneCurve.MAX_POINTS], or a point already within
     *     [ToneCurve.MIN_POINT_GAP] in x) -- grab that x-neighbour, as the only thing a vertical
     *     drag there could mean; null if there is none.
     */
    fun planGrab(
        curve: ToneCurve,
        xPx: Float,
        yPx: Float,
        widthPx: Float,
        heightPx: Float,
        radiusPx: Float,
    ): Grab? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        fun grabExisting(index: Int): Grab {
            val p = curve.points[index]
            return Grab(
                curve, index,
                xPx - toPixelX(p.x, widthPx), yPx - toPixelY(p.y, heightPx),
                existed = true,
            )
        }

        nearest(curve.points, xPx, yPx, widthPx, heightPx, radiusPx)?.let { return grabExisting(it) }

        val nx = toNormX(xPx, widthPx)
        val onCurve = distanceToCurve(curve.toLut(), xPx, yPx, widthPx, heightPx) <= radiusPx
        val inserted = if (onCurve) curve.insertAt(nx) else curve.insertAt(nx, toNormY(yPx, heightPx))
        if (inserted != null) {
            val p = inserted.curve.points[inserted.index]
            return Grab(
                inserted.curve, inserted.index,
                xPx - toPixelX(p.x, widthPx), yPx - toPixelY(p.y, heightPx),
                existed = false,
            )
        }

        val neighbour = curve.points.indices.minByOrNull { abs(curve.points[it].x - nx) }
        return neighbour
            ?.takeIf { abs(curve.points[it].x - nx) < ToneCurve.MIN_POINT_GAP }
            ?.let(::grabExisting)
    }
}
