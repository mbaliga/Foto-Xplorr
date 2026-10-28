package com.fotoxplorr.app.board

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An 8-bit coverage plane over a layer's box, row-major, 0 hidden to 255 shown. It may be smaller
 * than the size asked for (see [MaskGeometry.plane]); the renderer stretches it over the box.
 */
class MaskPlane(val width: Int, val height: Int, val alpha: ByteArray) {
    init {
        require(width > 0 && height > 0 && alpha.size == width * height) { "plane size must match ${width}x$height" }
    }

    fun at(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    /**
     * Coverage 0..1 at ([x], [y]) in plane pixels, where a pixel's centre is at +0.5, bilinear between
     * the four nearest centres; outside the plane it holds the nearest edge value.
     */
    fun coverageAt(x: Float, y: Float): Float {
        val fx = (x - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val fy = (y - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val wx = fx - x0
        val wy = fy - y0
        val top = at(x0, y0) * (1f - wx) + at(x1, y0) * wx
        val bottom = at(x0, y1) * (1f - wx) + at(x1, y1) * wx
        return (top * (1f - wy) + bottom * wy) / 255f
    }
}

/**
 * Which part of a box a mask shows, as pure maths: a point asked of a shape, a feather, and the
 * plane the renderer multiplies a layer by. Points are in the box's own units, origin at its top
 * left, 0..w across and 0..h down, so a feather is the same width in every direction whatever the
 * box's shape.
 *
 * [MaskShape.Subject] has no geometry here (its shape is only known once the segmenter has run over
 * pixels): it is treated as the whole box, which is also what a tap on a transparent corner of a
 * subject layer should do before the cut exists.
 */
object MaskGeometry {

    /** The longest side a feathered polygon is rasterised at; a soft edge loses nothing by being stretched. */
    internal const val SOFT_POLYGON_EDGE = 512

    /**
     * How far inside the shape ([x], [y]) is, in units: positive inside, negative outside, zero on
     * the edge. Exact for the box, the rounded rectangle and the polygon. For the ellipse it is the
     * first-order estimate (exact for a circle, and within a few percent near the edge of an ellipse
     * up to about 3:1); it is held to the inscribed radius so a feather never reaches past the middle.
     */
    fun signedDistance(shape: MaskShape, w: Float, h: Float, x: Float, y: Float): Float = when (shape) {
        MaskShape.Full, is MaskShape.Subject -> boxDistance(w, h, x, y)
        is MaskShape.RoundedRect -> roundedDistance(shape.radius, w, h, x, y)
        MaskShape.Ellipse -> ellipseDistance(w, h, x, y)
        is MaskShape.Polygon -> if (shape.points.size < 3) boxDistance(w, h, x, y) else polygonDistance(shape.points, w, h, x, y)
    }

    /**
     * The share of the point that shows, 0..1. With a feather it ramps (smoothly) from 0 at the edge
     * to 1 one feather-width in. Without one the edge is hard; [pixelUnits] (the size of one output
     * pixel in units, 0 for none) gives it a one-pixel soft edge so the rendered shape is not jagged:
     * exactly half is shown on the edge itself.
     */
    fun coverage(mask: LayerMask, w: Float, h: Float, x: Float, y: Float, pixelUnits: Float = 0f): Float {
        val d = signedDistance(mask.shape, w, h, x, y)
        val feather = mask.featherClamped * min(w, h)
        return when {
            feather > 0f -> if (d <= 0f) 0f else smooth((d / feather).coerceAtMost(1f))
            pixelUnits > 0f -> (0.5f + d / pixelUnits).coerceIn(0f, 1f)
            else -> if (d >= 0f) 1f else 0f
        }
    }

    /** Whether ([x], [y]) shows at all (a hit test: more than a tenth of it). */
    fun shows(mask: LayerMask, w: Float, h: Float, x: Float, y: Float): Boolean =
        coverage(mask, w, h, x, y) > HIT_COVERAGE

    internal const val HIT_COVERAGE = 0.1f

    /**
     * The plane for [mask] over a [boxW] x [boxH] box, at most [pxW] x [pxH]. A hard polygon is filled
     * by scanline and only the pixels along its edge get an exact soft edge; everything else is
     * evaluated per pixel. A feathered polygon (an exact distance at every pixel) is made at most
     * [SOFT_POLYGON_EDGE] on its long side, and the renderer stretches it.
     */
    fun plane(mask: LayerMask, boxW: Float, boxH: Float, pxW: Int, pxH: Int): MaskPlane {
        require(pxW > 0 && pxH > 0 && boxW > 0f && boxH > 0f) { "an empty box has no plane" }
        val shape = mask.shape
        val polygon = shape as? MaskShape.Polygon
        val soft = mask.featherClamped > 0f
        if (polygon != null && polygon.points.size >= 3 && !soft) return hardPolygonPlane(polygon, mask, boxW, boxH, pxW, pxH)
        var w = pxW
        var h = pxH
        if (polygon != null && polygon.points.size >= 3 && max(w, h) > SOFT_POLYGON_EDGE) {
            val k = SOFT_POLYGON_EDGE.toFloat() / max(w, h)
            w = max(1, (w * k).roundToInt())
            h = max(1, (h * k).roundToInt())
        }
        val out = ByteArray(w * h)
        val ux = boxW / w
        val uy = boxH / h
        val pixelUnits = (ux + uy) / 2f
        for (row in 0 until h) {
            val y = (row + 0.5f) * uy
            for (col in 0 until w) {
                out[row * w + col] = toByte(coverage(mask, boxW, boxH, (col + 0.5f) * ux, y, pixelUnits))
            }
        }
        return MaskPlane(w, h, out)
    }

    /** A plane from float coverage 0..1 (the lift segmenter's mask), rounded to 8 bits. */
    fun planeOf(width: Int, height: Int, coverage: FloatArray): MaskPlane {
        val out = ByteArray(width * height)
        for (i in out.indices) out[i] = toByte(coverage[i])
        return MaskPlane(width, height, out)
    }

    private fun hardPolygonPlane(polygon: MaskShape.Polygon, mask: LayerMask, boxW: Float, boxH: Float, pw: Int, ph: Int): MaskPlane {
        val pts = polygon.points
        val n = pts.size
        val ax = FloatArray(n) { pts[it].x * pw }
        val ay = FloatArray(n) { pts[it].y * ph }
        val inside = BooleanArray(pw * ph)
        val crossings = FloatArray(n)
        for (row in 0 until ph) {
            val yc = row + 0.5f
            var count = 0
            var j = n - 1
            for (i in 0 until n) {
                if ((ay[i] > yc) != (ay[j] > yc)) {
                    crossings[count++] = ax[i] + (yc - ay[i]) * (ax[j] - ax[i]) / (ay[j] - ay[i])
                }
                j = i
            }
            java.util.Arrays.sort(crossings, 0, count)
            var k = 0
            while (k + 1 < count) {
                val c0 = max(0, ceil(crossings[k] - 0.5f).toInt())
                val c1 = min(pw - 1, ceil(crossings[k + 1] - 0.5f).toInt() - 1)
                for (c in c0..c1) inside[row * pw + c] = true
                k += 2
            }
        }
        val out = ByteArray(pw * ph)
        for (i in out.indices) if (inside[i]) out[i] = 255.toByte()
        // Only a pixel with a neighbour on the other side of the edge can be partly covered.
        val ux = boxW / pw
        val uy = boxH / ph
        val pixelUnits = (ux + uy) / 2f
        for (row in 0 until ph) {
            for (col in 0 until pw) {
                val here = inside[row * pw + col]
                var edge = false
                loop@ for (dy in -1..1) {
                    val r = row + dy
                    if (r < 0 || r >= ph) continue
                    for (dx in -1..1) {
                        val c = col + dx
                        if (c < 0 || c >= pw) continue
                        if (inside[r * pw + c] != here) {
                            edge = true
                            break@loop
                        }
                    }
                }
                if (edge) out[row * pw + col] = toByte(coverage(mask, boxW, boxH, (col + 0.5f) * ux, (row + 0.5f) * uy, pixelUnits))
            }
        }
        return MaskPlane(pw, ph, out)
    }

    private fun toByte(coverage: Float): Byte = (coverage.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun boxDistance(w: Float, h: Float, x: Float, y: Float): Float = min(min(x, w - x), min(y, h - y))

    private fun roundedDistance(radius: Float, w: Float, h: Float, x: Float, y: Float): Float {
        val r = finite(radius, 0f).coerceIn(0f, 0.5f) * min(w, h)
        if (r <= 0f) return boxDistance(w, h, x, y)
        val qx = abs(x - w / 2f) - (w / 2f - r)
        val qy = abs(y - h / 2f) - (h / 2f - r)
        val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
        return -(outside + min(max(qx, qy), 0f) - r)
    }

    private fun ellipseDistance(w: Float, h: Float, x: Float, y: Float): Float {
        val a = w / 2f
        val b = h / 2f
        val inradius = min(a, b)
        val nx = (x - a) / a
        val ny = (y - b) / b
        val k = sqrt(nx * nx + ny * ny)
        if (k < 1e-4f) return inradius
        val gx = nx / a
        val gy = ny / b
        val gradient = sqrt(gx * gx + gy * gy) / k
        val d = (1f - k) / gradient
        return if (d > 0f) min(d, inradius) else d
    }

    private fun polygonDistance(points: List<com.fotoxplorr.app.lift.NormPoint>, w: Float, h: Float, x: Float, y: Float): Float {
        var inside = false
        var best = Float.MAX_VALUE
        var j = points.size - 1
        for (i in points.indices) {
            val ax = points[i].x * w
            val ay = points[i].y * h
            val bx = points[j].x * w
            val by = points[j].y * h
            if ((ay > y) != (by > y)) {
                val crossX = ax + (y - ay) * (bx - ax) / (by - ay)
                if (x < crossX) inside = !inside
            }
            best = min(best, segmentDistance(x, y, ax, ay, bx, by))
            j = i
        }
        return if (inside) best else -best
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }
}
