package com.fotoxplorr.app.pano

/**
 * Turns the aligner's pairwise answers into a layout the compositor can use, and into a plain-spoken
 * verdict the screen can show: which photos form the joined sweep, which one did not match the one
 * before it, and how far the sweep wandered up or down on the way.
 *
 * ## Chaining and its honest ceiling
 * `offsets[i]` places photo i+1 in photo i's grid ([Offset]), so photo k sits at the sum of the first
 * k offsets ([PanoGeometry.place]). There is no global adjustment: every pair's integer error is
 * added to the next one's, so a long sweep drifts, vertically first (a phone that is tilted a degree
 * climbs a few pixels per frame). That is why [MAX_SWEEP] is [PanoGeometry.MAX_PHOTOS], and why the
 * drift is reported instead of hidden.
 *
 * ## A pair that does not join
 * Only a [Offset.confident] pair is ever trusted. The first pair that is not confident is where the
 * sweep breaks: [Layout.cannotJoin] names the photo after it ("image N does not match the one before
 * it"). The sweep is split at EVERY such pair into runs of joinable photos, and the longest run (the
 * earliest on a tie) is what [Layout] lays out, so the screen can offer "join these N" and name the
 * photos that would be left out. A photo that matches neither neighbour is [Layout.isolated]: that is
 * the one to suggest removing.
 *
 * Positions and sizes are in whatever unit the offsets are in (the aligner's working pixels at the
 * time a person is looking at the answer); [Layout.scaledPositions] converts to the compositor's
 * unit with a single rounding instead of one per pair.
 *
 * Pure and Android-free.
 */
object PanoLayout {

    /** The most photos one layout takes: the geometry's cap, repeated so a caller reads it from here. */
    const val MAX_SWEEP = PanoGeometry.MAX_PHOTOS

    /**
     * A sweep whose tops spread over more than this fraction of the shortest photo is flagged
     * ([Layout.drifting]): the finished picture is cropped to the rows every photo has, so a quarter
     * of the height is already a visible loss.
     */
    const val DRIFT_WARN_FRACTION = 0.25f

    /** A photo's rectangle on the panorama canvas; the canvas starts at (0, 0). */
    class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    /**
     * The result. Everything about the joined sweep refers to [run]: photo `run.first + k` is
     * `positions[k]` and `rects[k]`. With a single photo, or when no pair is confident, [run] is one
     * photo and [joinable] is false; the rest is still filled in so a screen can show something.
     */
    class Layout(
        /** How many photos were given. */
        val photoCount: Int,
        /** Every run of photos that join each to the next, in sweep order, each one or more photos. */
        val runs: List<IntRange>,
        /** The longest run (earliest on a tie): the photos that will be joined. */
        val run: IntRange,
        /** Pair indices that are not confident: pair i is between photo i and photo i+1. Empty when the sweep joins. */
        val breaks: List<Int>,
        /** Top-left of each photo of [run], moved so the sweep's smallest x and y are 0. */
        val positions: List<PanoPoint>,
        /** The same, with each photo's size. */
        val rects: List<Rect>,
        /** The size of the canvas the rects span. */
        val canvasWidth: Long,
        val canvasHeight: Long,
        /** Net climb from the first photo of [run] to its last, in pixels; positive is lower. */
        val verticalDrift: Int,
        /** Highest top minus lowest top within [run]: the rows the auto-crop will lose. */
        val verticalSpread: Int,
        /** [verticalSpread] over the shortest photo in [run]. */
        val spreadFraction: Float,
        /** False when the confident offsets go left in some places and right in others: the photos look out of order. */
        val oneWay: Boolean,
    ) {
        /** At least two photos join, so there is a panorama to make. */
        val joinable: Boolean get() = run.last - run.first + 1 >= PanoGeometry.MIN_PHOTOS

        /** Every photo given is in [run]. */
        val complete: Boolean get() = run.first == 0 && run.last == photoCount - 1

        /** The photo (0-based) that did not match the one before it at the FIRST broken pair, or null when none broke. */
        val cannotJoin: Int? get() = breaks.firstOrNull()?.plus(1)

        /** Photos left out of [run], in sweep order. */
        val outside: List<Int> get() = (0 until photoCount).filter { it !in run }

        /** Photos that match neither neighbour (a lone first or last photo counts): the ones to suggest removing. */
        val isolated: List<Int>
            get() = if (photoCount < 2) emptyList() else runs.filter { it.first == it.last }.map { it.first }

        /** The sweep climbs or sinks enough to cost a visible slice of the picture ([DRIFT_WARN_FRACTION]). */
        val drifting: Boolean get() = spreadFraction > DRIFT_WARN_FRACTION

        /** [positions] in another unit: each coordinate times [factor], rounded once, keeping the minimum at 0. */
        fun scaledPositions(factor: Double): List<PanoPoint> {
            require(factor > 0.0 && factor.isFinite()) { "the scale must be a positive number" }
            return positions.map { PanoPoint(Math.round(it.x * factor).toInt(), Math.round(it.y * factor).toInt()) }
        }
    }

    /**
     * Lay out [photoSizes] (1 to [MAX_SWEEP] of them) from the pairwise [offsets] (one fewer, in the
     * same unit as the sizes).
     */
    fun layout(photoSizes: List<PanoSize>, offsets: List<Offset>): Layout {
        val n = photoSizes.size
        require(n in 1..MAX_SWEEP) { "a sweep is 1 to $MAX_SWEEP photos, got $n" }
        require(offsets.size == n - 1) { "need one offset between each neighbouring pair" }

        val breaks = offsets.indices.filter { !offsets[it].confident }
        val runs = ArrayList<IntRange>()
        var start = 0
        for (b in breaks) {
            runs += start..b
            start = b + 1
        }
        runs += start until n

        var run = runs[0]
        for (candidate in runs) if (candidate.last - candidate.first > run.last - run.first) run = candidate

        val inside = offsets.subList(run.first, run.last)
        val raw = PanoGeometry.place(inside)
        val minX = raw.minOf { it.x }.toLong()
        val minY = raw.minOf { it.y }.toLong()
        val positions = raw.map { PanoPoint(toInt(it.x - minX), toInt(it.y - minY)) }
        val sizes = photoSizes.subList(run.first, run.last + 1)
        val rects = positions.mapIndexed { k, p -> Rect(p.x, p.y, sizes[k].width, sizes[k].height) }
        val canvasWidth = rects.maxOf { it.right.toLong() }
        val canvasHeight = rects.maxOf { it.bottom.toLong() }

        val spread = positions.maxOf { it.y } - positions.minOf { it.y }
        val shortest = sizes.minOf { it.height }
        var left = false
        var right = false
        for (o in inside) {
            if (o.dx < 0) left = true
            if (o.dx > 0) right = true
        }
        return Layout(
            photoCount = n,
            runs = runs,
            run = run,
            breaks = breaks,
            positions = positions,
            rects = rects,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            verticalDrift = raw.last().y - raw.first().y,
            verticalSpread = spread,
            spreadFraction = spread.toFloat() / shortest,
            oneWay = !(left && right),
        )
    }

    private fun toInt(v: Long): Int {
        require(v in 0..Int.MAX_VALUE.toLong()) { "the photos span more than the grid can hold" }
        return v.toInt()
    }
}
