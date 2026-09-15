package com.fotoxplorr.app.editor

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

    /** True when this curve is the identity, so a caller can skip it entirely. */
    val isIdentity: Boolean
        get() = points.size == 2 &&
            points.first() == CurvePoint(0f, 0f) &&
            points.last() == CurvePoint(1f, 1f)

    /**
     * The 256-entry lookup this curve becomes.
     *
     * Sampled once into a table rather than evaluated per pixel: a 24-megapixel photo is 24 million
     * evaluations of a cubic against a binary search, versus 256 of them and an array index.
     */
    fun toLut(): IntArray {
        if (isIdentity) return IDENTITY_LUT
        val sorted = points.sortedBy { it.x }
        return IntArray(256) { index ->
            val y = interpolate(sorted, index / 255f)
            (y * 255f).toInt().coerceIn(0, 255)
        }
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
