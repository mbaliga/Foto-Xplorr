package com.fotoxplorr.app.textselect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Plane geometry for the text-selection model: points, four-cornered quads, and the distance maths
 * hit-testing is built from. Nothing here knows about Android, Compose or ML Kit, so all of it is
 * pinned by plain JVM tests.
 *
 * Every point in this package lives in FRAME PIXELS: x in 0..frameWidth, y in 0..frameHeight of the
 * image the recogniser ran on. That one choice is what keeps the maths isotropic. A normalised
 * 0..1 coordinate is a different physical distance in x and in y on any photo that is not square,
 * so a finger-sized radius measured in normalised units is a wide ellipse on a landscape frame and
 * a tall one on a portrait frame; in frame pixels a circle is a circle. Screen positions enter
 * through [FrameMap], which turns a screen distance into a frame distance with a single factor.
 */
data class Pt(val x: Float, val y: Float) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/**
 * A four-cornered area, in the order ML Kit reports corner points: top-left, top-right,
 * bottom-right, bottom-left OF THE TEXT. For upright text that is the picture's own top-left; for
 * tilted text the quad is tilted with it; for text turned upside down, [tl] sits at the bottom
 * right of the picture. [angle] therefore reads the direction the text runs in, whichever way the
 * photo was held, which is what lets a stored axis-aligned rectangle (that cannot tell a tilted
 * line from its bounding box) be replaced by the real shape.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val corners: List<Pt> get() = listOf(tl, tr, br, bl)

    val left: Float = min(min(tl.x, tr.x), min(br.x, bl.x))
    val top: Float = min(min(tl.y, tr.y), min(br.y, bl.y))
    val right: Float = max(max(tl.x, tr.x), max(br.x, bl.x))
    val bottom: Float = max(max(tl.y, tr.y), max(br.y, bl.y))

    val isFinite: Boolean get() = tl.isFinite && tr.isFinite && br.isFinite && bl.isFinite

    val centre: Pt get() = Pt((tl.x + tr.x + br.x + bl.x) / 4f, (tl.y + tr.y + br.y + bl.y) / 4f)

    /**
     * The direction the text runs in, in radians, measured from the +x axis towards +y (so on
     * screen, where y grows downward, a positive angle runs clockwise). 0 for upright left-to-right
     * text; about +-PI for text upside down; +-PI/2 for a line running down or up the picture.
     */
    val angle: Float get() = atan2(tr.y - tl.y, tr.x - tl.x)

    /** The same shape with x and y scaled, e.g. normalised 0..1 corners onto frame pixels. */
    fun scaled(sx: Float, sy: Float): Quad =
        Quad(Pt(tl.x * sx, tl.y * sy), Pt(tr.x * sx, tr.y * sy), Pt(br.x * sx, br.y * sy), Pt(bl.x * sx, bl.y * sy))

    /** Whether [p] is inside the quad or on its edge. Any winding, convex or not. */
    fun contains(p: Pt): Boolean = distanceTo(p) <= 0f

    /** 0 inside or on the edge, else the straight-line distance to the nearest edge. */
    fun distanceTo(p: Pt): Float {
        if (p.x < left - EDGE_SLACK || p.x > right + EDGE_SLACK || p.y < top - EDGE_SLACK || p.y > bottom + EDGE_SLACK) {
            return distanceToEdges(p)
        }
        if (TextGeometry.polygonContains(corners, p)) return 0f
        return distanceToEdges(p)
    }

    private fun distanceToEdges(p: Pt): Float = min(
        min(TextGeometry.distanceToSegment(p, tl, tr), TextGeometry.distanceToSegment(p, tr, br)),
        min(TextGeometry.distanceToSegment(p, br, bl), TextGeometry.distanceToSegment(p, bl, tl)),
    )

    /**
     * 0 when the segment a-b touches the quad (an end inside it, or a crossing of an edge), else
     * the smallest gap between the segment and the quad's outline.
     */
    fun distanceToSegment(a: Pt, b: Pt): Float {
        if (contains(a) || contains(b)) return 0f
        return min(
            min(TextGeometry.distanceBetweenSegments(a, b, tl, tr), TextGeometry.distanceBetweenSegments(a, b, tr, br)),
            min(TextGeometry.distanceBetweenSegments(a, b, br, bl), TextGeometry.distanceBetweenSegments(a, b, bl, tl)),
        )
    }

    /**
     * Whether the segment a-b passes within [radius] of the quad: a finger of that radius drawn
     * from a to b touches it. A bounding-box pre-reject keeps a long stroke over a page of words
     * from paying for the exact test on the words it is nowhere near.
     */
    fun touchedBySegment(a: Pt, b: Pt, radius: Float): Boolean {
        if (max(a.x, b.x) + radius < left || min(a.x, b.x) - radius > right) return false
        if (max(a.y, b.y) + radius < top || min(a.y, b.y) - radius > bottom) return false
        return distanceToSegment(a, b) <= radius
    }

    companion object {
        private const val EDGE_SLACK = 1e-3f

        /** An upright quad from a rectangle's edges. */
        fun fromBounds(left: Float, top: Float, right: Float, bottom: Float): Quad =
            Quad(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

        /**
         * The smallest rectangle whose sides run along [angle] (and at right angles to it) that
         * holds every one of [points], as a quad in text order. A highlight over several words of a
         * tilted line is this: one continuous band at the line's own slope, not a staircase of
         * axis-aligned boxes and not a box that swallows the neighbouring line.
         */
        fun orientedHull(points: List<Pt>, angle: Float): Quad {
            require(points.isNotEmpty()) { "orientedHull needs at least one point" }
            val ux = cos(angle)
            val uy = sin(angle)
            // The second axis is the first turned a quarter-turn towards +y (down on screen).
            val vx = -uy
            val vy = ux
            var minU = Float.POSITIVE_INFINITY
            var maxU = Float.NEGATIVE_INFINITY
            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (p in points) {
                val u = p.x * ux + p.y * uy
                val v = p.x * vx + p.y * vy
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            fun at(u: Float, v: Float) = Pt(u * ux + v * vx, u * uy + v * vy)
            return Quad(at(minU, minV), at(maxU, minV), at(maxU, maxV), at(minU, maxV))
        }
    }
}

/** The line-and-polygon maths under [Quad] and the stroke gestures. Pure; all in frame pixels. */
object TextGeometry {
    /** Distance from [p] to the segment a-b (to the single point when a == b). */
    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy)).toFloat()
    }

    /** Whether the closed segments a-b and c-d share at least one point (touching counts). */
    fun segmentsIntersect(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(a, b, c)) return true
        if (o2 == 0 && onSegment(a, b, d)) return true
        if (o3 == 0 && onSegment(c, d, a)) return true
        if (o4 == 0 && onSegment(c, d, b)) return true
        return false
    }

    /** 0 when the segments touch, else the smallest distance between any two of their points. */
    fun distanceBetweenSegments(a: Pt, b: Pt, c: Pt, d: Pt): Float {
        if (segmentsIntersect(a, b, c, d)) return 0f
        return min(
            min(distanceToSegment(a, c, d), distanceToSegment(b, c, d)),
            min(distanceToSegment(c, a, b), distanceToSegment(d, a, b)),
        )
    }

    /** Even-odd test: whether [p] is inside the polygon [poly], closed implicitly last-to-first. */
    fun polygonContains(poly: List<Pt>, p: Pt): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val crossX = (b.x - a.x).toDouble() * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Shoelace area of the polygon: positive one way round, negative the other, 0 when degenerate. */
    fun signedArea(poly: List<Pt>): Float {
        if (poly.size < 3) return 0f
        var sum = 0.0
        var j = poly.size - 1
        for (i in poly.indices) {
            sum += poly[j].x.toDouble() * poly[i].y - poly[i].x.toDouble() * poly[j].y
            j = i
        }
        return (sum / 2.0).toFloat()
    }

    fun distance(a: Pt, b: Pt): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Thin a finger path: keep the first point, then only points at least [minStep] from the last
     * one kept, and always the final point. Touch gives a point every few milliseconds, far more
     * than a few-dozen-pixel finger radius can tell apart; testing every sample against every word
     * is wasted work. Every dropped point is within [minStep] of a kept one.
     */
    fun decimate(path: List<Pt>, minStep: Float): List<Pt> {
        if (path.size <= 2) return path
        val kept = ArrayList<Pt>()
        kept.add(path[0])
        for (i in 1 until path.size - 1) {
            if (distance(kept.last(), path[i]) >= minStep) kept.add(path[i])
        }
        kept.add(path.last())
        return kept
    }

    private fun orientation(a: Pt, b: Pt, c: Pt): Int {
        val v = (b.y - a.y).toDouble() * (c.x - b.x) - (b.x - a.x).toDouble() * (c.y - b.y)
        return when {
            abs(v) < 1e-9 -> 0
            v > 0 -> 1
            else -> 2
        }
    }

    private fun onSegment(a: Pt, b: Pt, p: Pt): Boolean =
        p.x <= max(a.x, b.x) && p.x >= min(a.x, b.x) && p.y <= max(a.y, b.y) && p.y >= min(a.y, b.y)
}

/**
 * Where the frame sits on the screen: the Fit rectangle the photo is drawn in (the same rectangle
 * the viewer's text overlay already computes) against the frame the recogniser ran on.
 *
 * The overlay sits inside the viewer's zoom layer, so these are LAYER coordinates and zoom, pan and
 * rotation are already undone; a finger radius that should feel constant on screen has to be
 * divided by the current zoom by the caller before it comes through [frameDistance].
 */
class FrameMap(
    val frameWidth: Float,
    val frameHeight: Float,
    val fitLeft: Float,
    val fitTop: Float,
    val fitWidth: Float,
    val fitHeight: Float,
) {
    private val sx: Float get() = if (fitWidth > 0f) frameWidth / fitWidth else 1f
    private val sy: Float get() = if (fitHeight > 0f) frameHeight / fitHeight else 1f

    fun toFrame(screenX: Float, screenY: Float): Pt = Pt((screenX - fitLeft) * sx, (screenY - fitTop) * sy)

    fun toScreen(p: Pt): Pt = Pt(fitLeft + p.x / sx, fitTop + p.y / sy)

    /**
     * A screen distance as a frame distance. One factor for both axes: the Fit rectangle keeps the
     * frame's aspect, so a circle on screen is a circle in the frame (an extra width/height factor
     * here is precisely the bug this package exists to avoid).
     */
    fun frameDistance(screenDistance: Float): Float = screenDistance * sx
}
