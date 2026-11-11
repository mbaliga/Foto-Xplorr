package com.fotoxplorr.app.board

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a guide line says. */
enum class GuideKind { EDGE, CENTRE, SPACING }

/**
 * One line to draw while a layer is dragged. For [GuideKind.EDGE] and [GuideKind.CENTRE], [vertical]
 * is a vertical line at x = [at] running from y = [from] to [to]; false is a horizontal line at
 * y = [at] from x = [from] to [to]. For [GuideKind.SPACING] it is a gap marker with the same reading:
 * a horizontal marker (not vertical) at y = [at] between x = [from] and [to] is a gap between two
 * layers side by side.
 */
data class SnapGuide(val kind: GuideKind, val vertical: Boolean, val at: Float, val from: Float, val to: Float)

/** [cx], [cy] is where the layer's centre ends up. [snappedX] and [snappedY] say which axes moved to a guide. */
data class SnapResult(
    val cx: Float,
    val cy: Float,
    val snappedX: Boolean,
    val snappedY: Boolean,
    val guides: List<SnapGuide>,
)

/**
 * What a drag may snap to. [threshold] is the pull in canvas units (convert from dp with the
 * preview's pixels per unit; about 8 dp is comfortable).
 */
data class SnapOptions(
    val threshold: Float = 8f,
    val canvasEdges: Boolean = true,
    val canvasCentre: Boolean = true,
    val layers: Boolean = true,
    val spacing: Boolean = true,
)

/**
 * Snapping a dragged layer, as pure functions. The dragged layer is held by its upright bounds (the
 * smallest rectangle round the turned box), so a layer turned 30 degrees snaps by the corners that
 * stick out; this is what a person sees line up.
 *
 * Each axis is settled on its own: the nearest of these within the threshold wins, canvas centre
 * before canvas edge before another layer's centre before its edge before spacing when two are
 * equally near.
 *  - the layer's left, middle or right against the canvas, or against another layer (edge to edge,
 *    middle to middle);
 *  - equal gaps: the layer placed so the gap to the layer on one side equals the gap to the one on
 *    the other, or so its gap to a neighbour repeats a gap already between two others.
 * Only layers that are on the canvas are snapped to, and only those level with the dragged one
 * (their extents overlap across the axis) count for spacing, so a row is spaced as a row.
 */
object BoardSnap {

    private const val SAME = 0.01f
    private const val MIN_GAP = 1f

    fun snapMove(board: Board, movingId: Long, cx: Float, cy: Float, options: SnapOptions = SnapOptions()): SnapResult {
        val layer = board.layer(movingId) ?: return SnapResult(cx, cy, false, false, emptyList())
        val proposed = BoardGeometry.bounds(layer).shifted(cx - layer.transform.cx, cy - layer.transform.cy)
        val others = board.layers.filter { it.id != movingId && BoardGeometry.isOnCanvas(it, board.canvas) }.map { BoardGeometry.bounds(it) }

        val xPick = pick(Axis.X, proposed, others, board.canvas, options)
        val yPick = pick(Axis.Y, proposed, others, board.canvas, options)
        val dx = xPick?.delta ?: 0f
        val dy = yPick?.delta ?: 0f
        val finalBounds = proposed.shifted(dx, dy)
        val guides = buildList {
            xPick?.cands?.forEach { addAll(it.build(finalBounds)) }
            yPick?.cands?.forEach { addAll(it.build(finalBounds)) }
        }
        return SnapResult(cx + dx, cy + dy, xPick != null, yPick != null, guides.distinct())
    }

    /**
     * [deg] pulled to the nearest multiple of [stepDeg] when within [toleranceDeg] of it, else left
     * alone. The result is in (-180, 180].
     */
    fun snapRotation(deg: Float, stepDeg: Float = 15f, toleranceDeg: Float = 3f): Float {
        if (stepDeg <= 0f) return normaliseDegrees(deg)
        val nearest = (deg / stepDeg).roundToInt() * stepDeg
        return normaliseDegrees(if (abs(deg - nearest) <= toleranceDeg) nearest else deg)
    }

    private enum class Axis {
        X, Y;

        fun lo(b: Bounds) = if (this == X) b.left else b.top
        fun hi(b: Bounds) = if (this == X) b.right else b.bottom
        fun mid(b: Bounds) = if (this == X) b.centreX else b.centreY
        fun crossLo(b: Bounds) = if (this == X) b.top else b.left
        fun crossHi(b: Bounds) = if (this == X) b.bottom else b.right
        fun length(c: CanvasSize) = (if (this == X) c.width else c.height).toFloat()
        fun crossLength(c: CanvasSize) = (if (this == X) c.height else c.width).toFloat()
    }

    /** One way to snap: how far the layer must move, how it ranks on a tie, and the guides it shows once placed. */
    private class Cand(val delta: Float, val rank: Int, val build: (Bounds) -> List<SnapGuide>)

    private class Pick(val delta: Float, val cands: List<Cand>)

    private fun pick(axis: Axis, moving: Bounds, others: List<Bounds>, canvas: CanvasSize, o: SnapOptions): Pick? {
        val all = mutableListOf<Cand>()
        val len = axis.length(canvas)
        val crossLen = axis.crossLength(canvas)

        fun alignTargets(position: Float, kind: GuideKind, rank: Int, target: Bounds?) {
            // The moving layer's middle only goes to middles; its sides go to edges.
            val features = if (kind == GuideKind.CENTRE) listOf(axis.mid(moving)) else listOf(axis.lo(moving), axis.hi(moving))
            for (f in features) {
                val delta = position - f
                if (abs(delta) > o.threshold) continue
                all += Cand(delta, rank) { fin ->
                    val from = if (target == null) 0f else min(axis.crossLo(fin), axis.crossLo(target))
                    val to = if (target == null) crossLen else max(axis.crossHi(fin), axis.crossHi(target))
                    listOf(SnapGuide(kind, axis == Axis.X, position, from, to))
                }
            }
        }

        if (o.canvasCentre) alignTargets(len / 2f, GuideKind.CENTRE, 0, null)
        if (o.canvasEdges) {
            alignTargets(0f, GuideKind.EDGE, 1, null)
            alignTargets(len, GuideKind.EDGE, 1, null)
        }
        if (o.layers) {
            for (other in others) {
                alignTargets(axis.mid(other), GuideKind.CENTRE, 2, other)
                alignTargets(axis.lo(other), GuideKind.EDGE, 3, other)
                alignTargets(axis.hi(other), GuideKind.EDGE, 3, other)
            }
        }
        if (o.spacing) spacingCands(axis, moving, others, o.threshold, all)

        val best = all.minWithOrNull(compareBy<Cand> { abs(it.delta) }.thenBy { it.rank }) ?: return null
        return Pick(best.delta, all.filter { abs(it.delta - best.delta) <= SAME })
    }

    private fun spacingCands(axis: Axis, moving: Bounds, others: List<Bounds>, threshold: Float, out: MutableList<Cand>) {
        val len = axis.hi(moving) - axis.lo(moving)
        val level = others.filter { min(axis.crossHi(it), axis.crossHi(moving)) - max(axis.crossLo(it), axis.crossLo(moving)) > 0f }
        if (level.isEmpty()) return
        val mid = axis.mid(moving)

        fun gap(a: Float, b: Float, left: Bounds, right: Bounds): SnapGuide {
            val at = (max(axis.crossLo(left), axis.crossLo(right)) + min(axis.crossHi(left), axis.crossHi(right))) / 2f
            return SnapGuide(GuideKind.SPACING, axis != Axis.X, at, min(a, b), max(a, b))
        }

        // Between two neighbours: the nearest on each side, if the layer fits between them.
        val before = level.filter { axis.mid(it) < mid }.maxByOrNull { axis.hi(it) }
        val after = level.filter { axis.mid(it) > mid }.minByOrNull { axis.lo(it) }
        if (before != null && after != null && axis.lo(after) - axis.hi(before) >= len) {
            val targetLo = axis.hi(before) + (axis.lo(after) - axis.hi(before) - len) / 2f
            val delta = targetLo - axis.lo(moving)
            if (abs(delta) <= threshold) {
                out += Cand(delta, 4) { fin ->
                    listOf(gap(axis.hi(before), axis.lo(fin), before, fin), gap(axis.hi(fin), axis.lo(after), fin, after))
                }
            }
        }

        // Repeating a gap that two others already have.
        val row = level.sortedBy { axis.lo(it) }
        for (i in 0 until row.size - 1) {
            val a = row[i]
            val b = row[i + 1]
            val g = axis.lo(b) - axis.hi(a)
            if (g < MIN_GAP) continue
            if (mid >= axis.mid(b)) {
                val delta = axis.hi(b) + g - axis.lo(moving)
                if (abs(delta) <= threshold) {
                    out += Cand(delta, 4) { fin ->
                        listOf(gap(axis.hi(a), axis.lo(b), a, b), gap(axis.hi(b), axis.lo(fin), b, fin))
                    }
                }
            }
            if (mid <= axis.mid(a)) {
                val delta = axis.lo(a) - g - axis.hi(moving)
                if (abs(delta) <= threshold) {
                    out += Cand(delta, 4) { fin ->
                        listOf(gap(axis.hi(fin), axis.lo(a), fin, a), gap(axis.hi(a), axis.lo(b), a, b))
                    }
                }
            }
        }
    }
}
