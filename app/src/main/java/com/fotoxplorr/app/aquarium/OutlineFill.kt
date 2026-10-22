package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A pixel rectangle; [right] and [bottom] are exclusive, so a one-pixel dot is 1 x 1. */
data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** This rectangle grown by [by] on every side and kept inside a [w] x [h] image. */
    fun expandedBy(by: Int, w: Int, h: Int): IntBounds =
        IntBounds(max(0, left - by), max(0, top - by), min(w, right + by), min(h, bottom + by))
}

/**
 * Turning an outline into a filled shape, and cropping the result: the pure pixel half of the
 * drawing board. Everything works on a [BooleanArray] "ink" mask or an ARGB [IntArray], row-major,
 * so a JVM test can check it without a Bitmap.
 *
 * The idea is the one a child would use: a shape is FILLED when its outline is closed, and "inside"
 * means "cannot be reached from the edge of the board without crossing ink". A nearly closed
 * outline (the finger lifted a hair before the start) is closed for the person by [closedOutline];
 * one with a real gap stays open, the outside floods in, and nothing is filled. That is the whole
 * leak rule: no gap-dilation, no guessing.
 */
object OutlineFill {

    /** The ends may be this far apart, as a fraction of the outline's own bounding-box diagonal. */
    const val CLOSE_FRACTION = 0.12f

    /** Fewer points than this is a tap or a flick, never a loop. */
    const val MIN_CLOSE_POINTS = 6

    /** An outline smaller than this (board units) is a speck, never a shape to fill. */
    const val MIN_SHAPE_SIZE = 0.06f

    /** Thinner than this (or than two brush widths) there is no room inside to fill: an out-and-back scribble. */
    const val MIN_THICKNESS = 0.02f

    /** The loop must enclose at least this share of its bounding box, so out-and-back scribbles do not count. */
    const val MIN_AREA_FRACTION = 0.1f

    /** Largest ink mask we will flood; a bigger one is refused rather than risking memory. */
    private const val MAX_CELLS = 16_000_000L

    /**
     * True when [points] is a loop the person meant to close: enough points, a real size, ends within
     * max([CLOSE_FRACTION] x diagonal, [strokeWidth]) of each other, and a polygon area that is a real
     * share of its bounding box. A dot, a line, a C with a wide mouth, an out-and-back scribble and
     * anything with a non-finite point are not.
     */
    fun isNearlyClosed(points: List<NormPoint>, strokeWidth: Float): Boolean {
        if (points.size < MIN_CLOSE_POINTS || !strokeWidth.isFinite()) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) return false
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        val bw = maxX - minX
        val bh = maxY - minY
        val diagonal = hypot(bw, bh)
        if (diagonal < MIN_SHAPE_SIZE || min(bw, bh) < max(MIN_THICKNESS, 2f * strokeWidth)) return false
        val first = points.first()
        val last = points.last()
        val gap = hypot(last.x - first.x, last.y - first.y)
        if (gap > max(CLOSE_FRACTION * diagonal, strokeWidth)) return false
        return abs(shoelace(points)) >= MIN_AREA_FRACTION * bw * bh
    }

    /** [points], with the first point appended when the outline is nearly closed; otherwise unchanged. */
    fun closedOutline(points: List<NormPoint>, strokeWidth: Float = 0f): List<NormPoint> =
        if (isNearlyClosed(points, strokeWidth)) points + points.first() else points

    /**
     * The cells that are NOT ink and cannot be reached from the border without crossing ink: the
     * inside of every closed shape, however many there are, and the gaps between nested ones. A
     * 4-connected flood, so a one-pixel diagonal line is a wall. Iterative (an [IntArray] queue), so a
     * 16-megapixel mask cannot overflow the stack. A size that does not match [ink] gives an all-false
     * array of [ink]'s length.
     */
    fun interior(ink: BooleanArray, w: Int, h: Int): BooleanArray {
        if (w <= 0 || h <= 0 || ink.size.toLong() != w.toLong() * h || ink.size > MAX_CELLS) return BooleanArray(ink.size)
        val outside = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!ink[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (i >= w) seed(i - w)
            if (i < ink.size - w) seed(i + w)
        }
        return BooleanArray(ink.size) { !ink[it] && !outside[it] }
    }

    /**
     * The fill mask for one stroke on a [w] x [h] board: stamp its (closed, if nearly closed) outline
     * [widthNorm] thick, then take [interior]. Empty for an open stroke, a dot, or a brush so fat the
     * ink covers the middle. The rasteriser paints this under its own antialiased ink, so the half-
     * covered edge pixels of the ink are never left as a pale rim.
     */
    fun filledRegion(points: List<NormPoint>, widthNorm: Float, w: Int, h: Int): BooleanArray {
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_CELLS) return BooleanArray(0)
        val ink = BooleanArray(w * h)
        stampStroke(ink, w, h, closedOutline(points, widthNorm), widthNorm)
        return interior(ink, w, h)
    }

    /**
     * Marks every cell of the [w] x [h] [ink] mask within half of [widthNorm] (a fraction of the
     * width) of the polyline [points]. Points are first thinned to a spacing of a third of the radius
     * so a 4000-point stroke with a huge brush costs a few capsules, not millions; the radius is
     * capped at the board size, so a huge or non-finite width cannot overflow an Int.
     */
    fun stampStroke(ink: BooleanArray, w: Int, h: Int, points: List<NormPoint>, widthNorm: Float) {
        if (w <= 0 || h <= 0 || ink.size.toLong() != w.toLong() * h || points.isEmpty()) return
        val radius = if (widthNorm.isNaN()) 0.5f else (widthNorm * w / 2f).coerceIn(0.5f, max(w, h).toFloat())
        val spacing = max(radius / 3f, 0.5f)
        val limit = max(w, h) * 4f
        var px = 0f
        var py = 0f
        var have = false
        var lastPoint: NormPoint? = null
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            val x = (p.x * w).coerceIn(-limit, limit)
            val y = (p.y * h).coerceIn(-limit, limit)
            lastPoint = p
            if (!have) {
                disc(ink, w, h, x, y, radius)
                px = x; py = y; have = true
            } else if (hypot(x - px, y - py) >= spacing) {
                capsule(ink, w, h, px, py, x, y, radius)
                px = x; py = y
            }
        }
        // The last point always lands, even when it was closer than the spacing.
        val end = lastPoint ?: return
        val ex = (end.x * w).coerceIn(-limit, limit)
        val ey = (end.y * h).coerceIn(-limit, limit)
        if (ex != px || ey != py) capsule(ink, w, h, px, py, ex, ey, radius)
    }

    private fun disc(ink: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, r: Float) =
        capsule(ink, w, h, cx, cy, cx, cy, r)

    private fun capsule(ink: BooleanArray, w: Int, h: Int, ax: Float, ay: Float, bx: Float, by: Float, r: Float) {
        val x0 = max(0, floor(min(ax, bx) - r).toInt())
        val x1 = min(w - 1, ceil(max(ax, bx) + r).toInt())
        val y0 = max(0, floor(min(ay, by) - r).toInt())
        val y1 = min(h - 1, ceil(max(ay, by) + r).toInt())
        if (x0 > x1 || y0 > y1) return
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val r2 = r * r
        for (y in y0..y1) {
            val cy = y + 0.5f
            for (x in x0..x1) {
                val cx = x + 0.5f
                val t = if (len2 > 0f) (((cx - ax) * dx + (cy - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
                val ex = cx - (ax + t * dx)
                val ey = cy - (ay + t * dy)
                if (ex * ex + ey * ey <= r2) ink[y * w + x] = true
            }
        }
    }

    /**
     * The tight rectangle around every pixel whose alpha is at least [alphaMin], or null when there is
     * none (an empty sketch) or [pixels] does not hold a [w] x [h] image. A single dot gives 1 x 1.
     */
    fun opaqueBounds(pixels: IntArray, w: Int, h: Int, alphaMin: Int = 8): IntBounds? {
        if (w <= 0 || h <= 0 || pixels.size.toLong() != w.toLong() * h) return null
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) >= alphaMin) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    maxY = y
                }
            }
        }
        return if (maxX < 0) null else IntBounds(minX, minY, maxX + 1, maxY + 1)
    }

    private fun shoelace(points: List<NormPoint>): Float {
        var sum = 0.0
        var prev = points.last()
        for (p in points) {
            sum += prev.x.toDouble() * p.y - p.x.toDouble() * prev.y
            prev = p
        }
        return (sum / 2.0).toFloat()
    }
}
