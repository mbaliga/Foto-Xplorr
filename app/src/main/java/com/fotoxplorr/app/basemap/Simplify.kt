package com.fotoxplorr.app.basemap

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Douglas-Peucker thinning of a path so a world-sized view draws a few thousand points, not
 * sixty thousand. Works in Mercator degrees so the tolerance means the same on screen at every
 * latitude.
 */
object Simplify {
    /** The drawing size the per-level tolerance is defined against. */
    const val REFERENCE_WORLD_PX = 1000.0

    /** Allowed deviation on screen. */
    const val PIXEL_TOLERANCE = 0.8

    /** Tolerance in 1/100 degree for a detail [bucket] (0 = whole world in 1000 px). */
    fun toleranceUnits(bucket: Int): Double = PIXEL_TOLERANCE * 360.0 * 100.0 / (REFERENCE_WORLD_PX * 2.0.pow(bucket))

    /** [xy] holds lon, lat pairs in 1/100 degree. First and last points are always kept. */
    fun simplify(xy: IntArray, toleranceUnits: Double): IntArray {
        val n = xy.size / 2
        if (n <= 2 || toleranceUnits < 0.5) return xy
        val xs = DoubleArray(n) { xy[2 * it].toDouble() }
        val ys = DoubleArray(n) { mercatorUnits(xy[2 * it + 1]) }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = IntArray(2 * n)
        var top = 0
        stack[top++] = 0
        stack[top++] = n - 1
        while (top > 0) {
            val hi = stack[--top]
            val lo = stack[--top]
            var worst = -1.0
            var at = -1
            for (i in lo + 1 until hi) {
                val d = distanceToSegment(xs[i], ys[i], xs[lo], ys[lo], xs[hi], ys[hi])
                if (d > worst) { worst = d; at = i }
            }
            if (at >= 0 && worst > toleranceUnits) {
                keep[at] = true
                stack[top++] = lo; stack[top++] = at
                stack[top++] = at; stack[top++] = hi
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val out = IntArray(count * 2)
        var o = 0
        for (i in 0 until n) if (keep[i]) { out[o++] = xy[2 * i]; out[o++] = xy[2 * i + 1] }
        return out
    }

    private fun mercatorUnits(latUnits: Int): Double {
        val lat = (latUnits / 100.0).coerceIn(-MapMath.MAX_LAT, MapMath.MAX_LAT) * PI / 180.0
        return ln(tan(PI / 4.0 + lat / 2.0)) * 180.0 / PI * 100.0
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

}
