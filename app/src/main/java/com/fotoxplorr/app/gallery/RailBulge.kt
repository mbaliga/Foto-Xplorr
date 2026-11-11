package com.fotoxplorr.app.gallery

import dev.aarso.cellshell.ScrubberEdge
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The rail's raise-and-bulge, as pure functions (owner, batch 9 #5: "the rail bends into an ARC
 * that bulges away from the finger, so the letters near the finger are displaced out from under it
 * and stay readable"; the reference is a launcher's A-Z rail). Kept out of the composable for the
 * reason [com.fotoxplorr.app.viewer.FilmstripGeometry] is: a rail whose labels sit under the
 * finger, or whose bubble leaves the screen, is the kind of thing nobody files a bug about, so
 * each judgement is pinned by a test instead.
 *
 * ### Coordinates
 *
 * Everything works in two numbers per point: *along* the rail (y on LEFT/RIGHT, x on TOP/BOTTOM)
 * and *depth* (the distance from the screen edge the rail hugs, growing toward the screen's
 * interior). "Away from the finger" is then one rule for all four edges: the finger rests on the
 * strip at the edge, so away from it is **greater depth**, i.e. the arc bulges left on RIGHT,
 * right on LEFT, down on TOP and up on BOTTOM. [toOffset] turns (along, depth) back into pixels.
 *
 * ### The arc
 *
 * [bump] is 1 for a label within [RailBulgeSpec.plateauPx] of the finger and eases to 0 over the
 * next [RailBulgeSpec.fallPx] by a raised cosine (zero slope at both ends, so there is no seam
 * where the bulge starts). A label's displacement is that times the peak. The flat top is what
 * lets the guarantee be stated as a circle: every label within one finger radius of the touch
 * point is pushed out to at least `fingerRadius + (how far in from the edge the finger sits) +
 * margin` of depth, so none of them is inside the finger's footprint (see [isClearOfFinger]).
 */
data class RailBulgeSpec(
    /** Half the width of a finger pad: nothing may sit within this of the touch point. */
    val fingerRadiusPx: Float,
    /** Labels within this along-distance of the finger all get the full displacement. */
    val plateauPx: Float,
    /** Over this much further along-distance the displacement eases to zero. */
    val fallPx: Float,
    /** Smallest peak displacement: about one finger width. [peakFor] may raise it, never lower it. */
    val minPeakPx: Float,
    /** Extra clearance past the finger's edge, so a label is not merely tangent to the finger. */
    val marginPx: Float,
    /** Where a label rests, as depth of its centre from the edge. */
    val restDepthPx: Float,
    /** Scale of the label under the finger at full strength; 1 at rest. Small on purpose. */
    val maxScale: Float,
) {
    companion object {
        /** Label row height the radii are measured in: the library strip's 14 dp pitch. */
        const val LABEL_HEIGHT_DP = 14f

        /** Share of the rail's length the arc falls over on each side of the plateau. */
        const val FALL_SHARE_OF_RAIL = 0.30f

        /** The arc never falls over more than this, so a very tall rail still has a readable slope. */
        const val MAX_FALL_DP = 220f

        /**
         * The spec for a rail of [railLengthPx]: [forDensity], with the fall widened to about
         * [FALL_SHARE_OF_RAIL] of the rail (capped at [MAX_FALL_DP]) so the whole column bends in
         * one wide arc, as in the reference recordings, instead of a bump around the finger. A
         * wider fall only adds displacement, so the finger-clearance guarantee is unchanged.
         */
        fun forRail(density: Float, railLengthPx: Float): RailBulgeSpec {
            val base = forDensity(density)
            val wide = (railLengthPx * FALL_SHARE_OF_RAIL).coerceIn(base.fallPx, MAX_FALL_DP * density)
            return base.copy(fallPx = wide.coerceAtLeast(base.fallPx))
        }

        fun forDensity(density: Float): RailBulgeSpec = RailBulgeSpec(
            fingerRadiusPx = 28f * density,
            plateauPx = 17f * density,
            fallPx = 3.5f * LABEL_HEIGHT_DP * density,
            minPeakPx = 56f * density,
            marginPx = 8f * density,
            restDepthPx = 20f * density,
            maxScale = 1.3f,
        )
    }
}

/** Where the bubble goes: its top-left in screen pixels, and its centre along / depth. */
data class RailBubblePlacement(val x: Float, val y: Float, val alongCenter: Float, val depthCenter: Float)

object RailBulge {

    /** How long the rail takes to ease straight again after the finger lifts. */
    const val SNAP_BACK_MS = 240

    /** How long the rail stays up after release in the only-while-scrubbing mode. */
    const val LINGER_MS = 1000L

    /**
     * Thickness of the invisible touch target kept at the edge while the rail is hidden. 44 dp is
     * the library strip's own width; TRAPS #72's edge-zone rules still apply to it.
     */
    const val TOUCH_TARGET_DP = 44

    /** The bump: 1 inside the plateau, raised cosine to 0 over the fall, 0 beyond. Symmetric. */
    fun bump(distancePx: Float, spec: RailBulgeSpec): Float {
        val d = abs(distancePx)
        if (d <= spec.plateauPx) return 1f
        if (spec.fallPx <= 0f || d >= spec.plateauPx + spec.fallPx) return 0f
        val t = (d - spec.plateauPx) / spec.fallPx
        return (cos(t * PI.toFloat()) + 1f) / 2f
    }

    /**
     * The peak displacement for a finger resting [fingerDepthPx] in from the edge: the spec's
     * minimum, raised when the finger sits far enough in that the minimum would not clear it.
     */
    fun peakFor(fingerDepthPx: Float, spec: RailBulgeSpec): Float =
        max(spec.minPeakPx, spec.fingerRadiusPx + max(0f, fingerDepthPx - spec.restDepthPx) + spec.marginPx)

    /**
     * How far a label centred at [labelAlongPx] is pushed away from the edge, in px (never
     * negative). [strength] is 0 at rest and 1 while scrubbing and scales the whole arc, which is
     * all the snap-back has to animate.
     */
    fun displacement(
        labelAlongPx: Float,
        fingerAlongPx: Float,
        fingerDepthPx: Float,
        strength: Float,
        spec: RailBulgeSpec,
    ): Float {
        val s = strength.coerceIn(0f, 1f)
        if (s == 0f) return 0f
        return s * peakFor(fingerDepthPx, spec) * bump(labelAlongPx - fingerAlongPx, spec)
    }

    /** Scale of a label near the finger: 1 far away and at rest, [RailBulgeSpec.maxScale] under it. */
    fun scale(labelAlongPx: Float, fingerAlongPx: Float, strength: Float, spec: RailBulgeSpec): Float =
        1f + (spec.maxScale - 1f) * strength.coerceIn(0f, 1f) * bump(labelAlongPx - fingerAlongPx, spec)

    /** A label's depth from the edge once displaced. */
    fun labelDepth(
        labelAlongPx: Float,
        fingerAlongPx: Float,
        fingerDepthPx: Float,
        strength: Float,
        spec: RailBulgeSpec,
    ): Float = spec.restDepthPx + displacement(labelAlongPx, fingerAlongPx, fingerDepthPx, strength, spec)

    /**
     * Whether a label's centre is outside the finger's circle (radius [RailBulgeSpec.fingerRadiusPx])
     * around the touch point, measured in the plane of the screen.
     */
    fun isClearOfFinger(
        labelAlongPx: Float,
        fingerAlongPx: Float,
        fingerDepthPx: Float,
        strength: Float,
        spec: RailBulgeSpec,
    ): Boolean {
        val a = labelAlongPx - fingerAlongPx
        val p = labelDepth(labelAlongPx, fingerAlongPx, fingerDepthPx, strength, spec) - fingerDepthPx
        return sqrt(a * a + p * p) >= spec.fingerRadiusPx - 0.001f
    }

    /**
     * The eased progress of the snap-back, 0 to 1: a cubic ease-out (fast to leave the arc, slow
     * to settle straight). Monotone and exact at both ends so it can be an animation's easing.
     */
    fun snapBackEase(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val r = 1f - f
        return 1f - r * r * r
    }

    /** Converts a point given as (along, depth) to screen pixels on a [width] x [height] screen. */
    fun toOffset(edge: ScrubberEdge, along: Float, depth: Float, width: Float, height: Float): Pair<Float, Float> =
        when (edge) {
            ScrubberEdge.RIGHT -> (width - depth) to along
            ScrubberEdge.LEFT -> depth to along
            ScrubberEdge.TOP -> along to depth
            ScrubberEdge.BOTTOM -> along to (height - depth)
        }

    /**
     * The bubble: level with the finger, on the interior side of the arc's peak (so it clears the
     * bulged labels as well as the finger), and clamped inside the screen and out of the shell's
     * edge-gesture zones ([edgeZonePx], TRAPS #72) on both axes. If the screen is too small to
     * satisfy the zones it is still kept on the screen.
     *
     * @param bubbleWidthPx the bubble's size in screen x, [bubbleHeightPx] in screen y.
     */
    fun bubble(
        edge: ScrubberEdge,
        fingerAlongPx: Float,
        fingerDepthPx: Float,
        bubbleWidthPx: Float,
        bubbleHeightPx: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
        edgeZonePx: Float,
        gapPx: Float,
        spec: RailBulgeSpec,
    ): RailBubblePlacement {
        val vertical = edge == ScrubberEdge.LEFT || edge == ScrubberEdge.RIGHT
        val alongSize = if (vertical) bubbleHeightPx else bubbleWidthPx
        val depthSize = if (vertical) bubbleWidthPx else bubbleHeightPx
        val alongExtent = if (vertical) screenHeightPx else screenWidthPx
        val depthExtent = if (vertical) screenWidthPx else screenHeightPx

        // Past the arc's peak label (about one label's width of ink beyond its centre).
        val labelClear = 16f * (spec.fingerRadiusPx / 28f)
        val wantedDepth = spec.restDepthPx + peakFor(fingerDepthPx, spec) + labelClear + gapPx + depthSize / 2f

        val alongCenter = clampCentre(fingerAlongPx, alongSize, alongExtent, edgeZonePx)
        val depthCenter = clampCentre(wantedDepth, depthSize, depthExtent, edgeZonePx)
        val (cx, cy) = toOffset(edge, alongCenter, depthCenter, screenWidthPx, screenHeightPx)
        return RailBubblePlacement(cx - bubbleWidthPx / 2f, cy - bubbleHeightPx / 2f, alongCenter, depthCenter)
    }

    /** Centre kept [zone] clear of both ends; if that is impossible, kept on the screen. */
    private fun clampCentre(centre: Float, size: Float, extent: Float, zone: Float): Float {
        val half = size / 2f
        val lo = zone + half
        val hi = extent - zone - half
        if (lo <= hi) return centre.coerceIn(lo, hi)
        val loHard = half
        val hiHard = max(loHard, extent - half)
        return min(max(centre, loHard), hiHard)
    }
}

/**
 * The position-to-item mapping the rail needs, restated app-side because the library's own copies
 * are `internal` to it. Same rules as `EdgeTimelineScrubber`: position maps linearly to an item
 * index, a stop labels the run that starts at it, and a stop's label sits where its first item maps.
 */
internal object RailMapping {

    /** The item a fraction of the way along the rail refers to; clamps, and NaN takes the first. */
    fun indexForFraction(fraction: Float, itemCount: Int): Int {
        if (itemCount <= 0 || fraction.isNaN()) return 0
        return (fraction.coerceIn(0f, 1f) * itemCount).toInt().coerceIn(0, itemCount - 1)
    }

    /** The last stop that begins at or before [index], or null before the first stop. */
    fun stopForIndex(stops: List<dev.aarso.cellshell.ScrubberStop>, index: Int): dev.aarso.cellshell.ScrubberStop? =
        stops.lastOrNull { it.itemIndex <= index }

    /** Where an item sits along a rail of [lengthPx], at the centre of its band. */
    fun centerAlong(itemIndex: Int, itemCount: Int, lengthPx: Float): Float {
        if (itemCount <= 0) return 0f
        return ((itemIndex + 0.5f) / itemCount).coerceIn(0f, 1f) * lengthPx
    }

    /** The stops that have room for a label: a greedy pass keeping those at least [minGapPx] apart. */
    fun thinned(
        stops: List<dev.aarso.cellshell.ScrubberStop>,
        itemCount: Int,
        lengthPx: Float,
        minGapPx: Float,
    ): List<dev.aarso.cellshell.ScrubberStop> {
        if (stops.isEmpty() || itemCount <= 0 || lengthPx <= 0f) return emptyList()
        val kept = ArrayList<dev.aarso.cellshell.ScrubberStop>(stops.size)
        var last = Float.NEGATIVE_INFINITY
        for (stop in stops) {
            val y = centerAlong(stop.itemIndex, itemCount, lengthPx)
            if (y - last < minGapPx) continue
            kept += stop
            last = y
        }
        return kept
    }

    /** The stop before / after the one holding [index], for the stepper alternative; null at the ends. */
    fun neighbourStop(
        stops: List<dev.aarso.cellshell.ScrubberStop>,
        index: Int,
        forward: Boolean,
    ): dev.aarso.cellshell.ScrubberStop? {
        val current = stops.indexOfLast { it.itemIndex <= index }
        val target = if (forward) current + 1 else if (current <= 0) -1 else current - 1
        // Before the first stop (current == -1) "forward" is the first stop.
        return stops.getOrNull(target)?.takeIf { target >= 0 }
    }
}

/** Every word the rail says to a person, one short sentence each. */
internal object TimelineRailCopy {
    const val RAIL = "Timeline"
    const val SCRUB = "Scrub timeline"
    const val PREVIOUS = "Previous month"
    const val NEXT = "Next month"
}
