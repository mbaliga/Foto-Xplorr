package com.fotoxplorr.app.editor

import kotlin.math.roundToInt

/**
 * A tone curve: control points the user drags, and the smooth curve through them.
 *
 * Interpolated with **monotone cubic Hermite** (Fritsch–Carlson), not a natural cubic spline, and
 * that choice is the whole reason this file has tests. A natural spline through hand-placed points
 * overshoots between them: pull one control point up and the curve dips *below* its neighbours on
 * the way there. In a tone curve an overshoot is not a cosmetic wobble — it is a band of pixels
 * that gets darker when the user asked for brighter, which shows up as a halo around edges and as
 * inverted patches in skies. Monotone interpolation cannot do that: between two rising points the
 * curve only rises.
 *
 * Pure Kotlin with no Android imports, so the arithmetic is testable on the JVM. Everything the
 * editor does to colour is built from these.
 */
data class ToneCurve(val points: List<CurvePoint> = DEFAULT_POINTS) {

    init {
        require(points.size >= 2) { "a curve needs at least two points, had ${points.size}" }
    }

    /**
     * The table, computed once per curve value and never short-circuited.
     *
     * Split out of [toLut] and [isIdentity] because each of those needs the other's answer: the
     * identity test is "does the table equal the identity table" (so a three-point collinear curve
     * counts, which a point-count check cannot see), while [toLut] wants to hand back the shared
     * [IDENTITY_LUT] for exactly that case. If either called the other they would recurse forever;
     * both call this instead.
     */
    private val computed: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) { computeLut() }

    /** True when this curve is the identity, so a caller can skip it entirely. */
    val isIdentity: Boolean
        get() = (points.size == 2 && points[0].x == 0f && points[0].y == 0f && points[1].x == 1f && points[1].y == 1f) ||
            computed.contentEquals(IDENTITY_LUT)

    /**
     * The 256-entry lookup this curve becomes. Treat the result as read-only: it is cached, and
     * for an identity curve it is the shared [IDENTITY_LUT].
     *
     * Sampled once into a table rather than evaluated per pixel: a 24-megapixel photo is 24 million
     * evaluations of a cubic against a binary search, versus 256 of them and an array index.
     */
    fun toLut(): IntArray = if (isIdentity) IDENTITY_LUT else computed

    /**
     * The curve's value at [x], 0..1 -- what a point inserted at [x] would sit at without bending
     * the curve at all.
     */
    fun valueAt(x: Float): Float = interpolate(points.sortedBy { it.x }, x.coerceIn(0f, 1f))

    private fun computeLut(): IntArray {
        val sorted = points.sortedBy { it.x }
        return IntArray(256) { index ->
            val y = interpolate(sorted, index / 255f)
            // Rounded, not truncated: a collinear curve's float error (0.49999997 for what should
            // be 128) would otherwise land one level low and make a true identity look edited.
            (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    // ---- index-based editing, for a finger on the graph ----
    //
    // The old drag went through [withPoint], which merges by x: it never MOVED the grabbed point,
    // it added a new one at the finger and dropped only neighbours within 0.02 of it. A fast
    // horizontal drag left a trail of stray points, and dragging across a neighbour re-sorted the
    // list under a saved index. These never reorder, so an index taken at touch-down stays
    // valid for the whole gesture. All of them assume [points] is sorted by x, which every path
    // through the editor maintains.

    /**
     * Move the point at [index] to ([x], [y]), keeping the list's order and size.
     *
     * The two end points keep their x (only y moves): they are what the curve says about black and
     * white. An interior point's x is held [MIN_POINT_GAP] short of each neighbour, so it can
     * neither cross nor swallow one. Out-of-range or non-finite input is clamped or ignored rather
     * than thrown, since this runs on every touch event.
     */
    fun moveTo(index: Int, x: Float, y: Float): ToneCurve {
        if (index !in points.indices) return this
        val old = points[index]
        val newY = if (y.isNaN()) old.y else y.coerceIn(0f, 1f)
        val newX = if (index == 0 || index == points.lastIndex || x.isNaN()) {
            old.x
        } else {
            val lo = points[index - 1].x + MIN_POINT_GAP
            val hi = points[index + 1].x - MIN_POINT_GAP
            if (lo > hi) old.x else x.coerceIn(lo, hi)
        }
        if (newX == old.x && newY == old.y) return this
        return ToneCurve(points.toMutableList().also { it[index] = CurvePoint(newX, newY) })
    }

    /** A curve with a point freshly added, and where in [curve]'s list that point landed. */
    data class Inserted(val curve: ToneCurve, val index: Int)

    /**
     * Add a point at [x], at height [y] -- by default ON the current curve, so the curve passes
     * through it and barely changes at the moment of insertion (the tangents around a new point
     * are recomputed, so it is not bit-for-bit the same curve), and the user then drags it where
     * they want it.
     *
     * Null (refused) when the list is full ([MAX_POINTS]) or [x] is within [MIN_POINT_GAP] of an
     * existing point: the caller treats that as grabbing the neighbour instead.
     */
    fun insertAt(x: Float, y: Float = valueAt(x)): Inserted? {
        if (points.size >= MAX_POINTS || x.isNaN() || y.isNaN()) return null
        val nx = x.coerceIn(0f, 1f)
        if (points.any { kotlin.math.abs(it.x - nx) < MIN_POINT_GAP }) return null
        val at = points.indexOfFirst { it.x > nx }.let { if (it < 0) points.size else it }
        val grown = points.toMutableList().also { it.add(at, CurvePoint(nx, y.coerceIn(0f, 1f))) }
        return Inserted(ToneCurve(grown), at)
    }

    /** Remove the interior point at [index]. The two end points (and any index out of range) are refused. */
    fun removeAt(index: Int): ToneCurve {
        if (index <= 0 || index >= points.lastIndex) return this
        return ToneCurve(points.toMutableList().also { it.removeAt(index) })
    }

    /** Add or move a control point, keeping the list sorted and the ends pinned. */
    fun withPoint(point: CurvePoint): ToneCurve {
        val kept = points.filterNot { kotlin.math.abs(it.x - point.x) < POINT_MERGE_DISTANCE }
        return ToneCurve((kept + point).sortedBy { it.x })
    }

    fun withoutPointAt(x: Float): ToneCurve {
        // The endpoints are structural: a curve with no value at 0 or 1 has nothing to say about
        // black or white, and every consumer would need its own fallback.
        val kept = points.filterNot {
            kotlin.math.abs(it.x - x) < POINT_MERGE_DISTANCE && it.x > 0f && it.x < 1f
        }
        return if (kept.size >= 2) ToneCurve(kept) else this
    }

    companion object {
        val DEFAULT_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val IDENTITY = ToneCurve()

        /** Shared, so the common case allocates nothing. Never mutated. */
        val IDENTITY_LUT = IntArray(256) { it }

        /** Two control points closer than this are the same point being dragged. */
        const val POINT_MERGE_DISTANCE = 0.02f

        /** How close in x [moveTo] lets a point get to its neighbours, and [insertAt] to any point. */
        const val MIN_POINT_GAP = POINT_MERGE_DISTANCE

        /** The most control points a curve may have. Beyond this a finger cannot tell them apart. */
        const val MAX_POINTS = 16
    }
}

/** One control point, both axes normalised 0..1. */
data class CurvePoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "curve points are normalised, was ($x, $y)" }
    }
}

/**
 * Monotone cubic Hermite interpolation of [sorted] at [t].
 *
 * Fritsch–Carlson: compute the secant slopes, take the naive tangent at each interior point as
 * their average, then *limit* those tangents so no segment can overshoot. The limiting step is the
 * whole algorithm — without it this is an ordinary Catmull–Rom spline and it rings.
 */
internal fun interpolate(sorted: List<CurvePoint>, t: Float): Float {
    if (t <= sorted.first().x) return sorted.first().y
    if (t >= sorted.last().x) return sorted.last().y

    val n = sorted.size
    val secants = FloatArray(n - 1) { i ->
        val dx = sorted[i + 1].x - sorted[i].x
        // Coincident points would divide by zero. They are deduplicated on the way in, so this is
        // a guard rather than a case.
        if (dx <= 0f) 0f else (sorted[i + 1].y - sorted[i].y) / dx
    }

    val tangents = FloatArray(n)
    tangents[0] = secants[0]
    tangents[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
        tangents[i] = if (secants[i - 1] * secants[i] <= 0f) {
            // A local extremum: a flat tangent is what keeps the curve from bulging past the point.
            0f
        } else {
            (secants[i - 1] + secants[i]) / 2f
        }
    }

    // The limiter. Any tangent longer than three times its secant lets the segment overshoot, so
    // scale the pair back onto the circle of radius 3.
    for (i in 0 until n - 1) {
        val secant = secants[i]
        if (secant == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
            continue
        }
        val alpha = tangents[i] / secant
        val beta = tangents[i + 1] / secant
        val magnitude = alpha * alpha + beta * beta
        if (magnitude > 9f) {
            val scale = 3f / kotlin.math.sqrt(magnitude)
            tangents[i] = scale * alpha * secant
            tangents[i + 1] = scale * beta * secant
        }
    }

    var segment = 0
    while (segment < n - 2 && t > sorted[segment + 1].x) segment++
    val x0 = sorted[segment].x
    val x1 = sorted[segment + 1].x
    val h = x1 - x0
    if (h <= 0f) return sorted[segment].y
    val s = (t - x0) / h

    // Hermite basis.
    val s2 = s * s
    val s3 = s2 * s
    val h00 = 2f * s3 - 3f * s2 + 1f
    val h10 = s3 - 2f * s2 + s
    val h01 = -2f * s3 + 3f * s2
    val h11 = s3 - s2

    return (
        h00 * sorted[segment].y +
            h10 * h * tangents[segment] +
            h01 * sorted[segment + 1].y +
            h11 * h * tangents[segment + 1]
        ).coerceIn(0f, 1f)
}

/**
 * Which curve a graph edit is aimed at. [LUMA] is distinct from [RGB]: RGB bends all three
 * channels by the same function and so shifts colour as it shifts tone, LUMA bends brightness only
 * and keeps each pixel's hue (see [applyColour]). The UI colours live next to the graph, not here,
 * so this stays pure and [Adjustments] can address a curve by channel.
 */
enum class CurveChannel(val label: String) {
    RGB("RGB"),
    LUMA("Luma"),
    RED("R"),
    GREEN("G"),
    BLUE("B"),
}
