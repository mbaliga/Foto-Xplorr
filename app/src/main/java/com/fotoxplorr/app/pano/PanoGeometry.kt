package com.fotoxplorr.app.pano

import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.MIN_LADDER_EDGE
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The numbers under a horizontal-sweep panorama, in pure Kotlin: the focal length in pixels, the
 * cylinder every photo is warped onto, and how big the finished canvas may be.
 *
 * ## Why a cylinder
 * A person who stands still and turns the phone is rotating the camera about its own centre. On a
 * cylinder around that centre, a pure rotation about the vertical axis is a pure sideways
 * translation of the picture, so joining two frames is "find dx and dy" ([PairAligner]) instead of
 * a homography. That is also this feature's honest ceiling: it needs a roughly right focal length
 * (a wrong one bends straight lines), cannot repair parallax from a person who walked or held a
 * near object, and has no global adjustment, which is why a sweep is capped at [MAX_PHOTOS].
 *
 * Coordinates: [forward] and [inverse] work in pixels measured from the photo's principal point
 * (taken as its centre), x to the right and y down, and [f] is the focal length in the same pixels.
 *
 * Nothing here knows about Android; the sizes and offsets are whatever unit the caller works in,
 * as long as it is the same unit throughout.
 */
object PanoGeometry {

    /** The diagonal of a 35 mm frame in millimetres: what "35 mm equivalent" is measured against. */
    const val FULL_FRAME_DIAGONAL_MM = 43.27

    /**
     * Assumed diagonal field of view when a photo does not say its focal length, about a 27 mm
     * equivalent: the main camera of most phones. It is a guess, so the UI should say so.
     */
    const val DEFAULT_FALLBACK_FOV_DEG = 78.0

    /** Fewest and most photos in one sweep: pairwise chaining without a global fit drifts past this. */
    const val MIN_PHOTOS = 2
    const val MAX_PHOTOS = 12

    /** What a panorama is planned at unless the person asks for less: 24 megapixels. */
    const val DEFAULT_MAX_PIXELS = 24_000_000L

    /** A 35 mm-equivalent focal length outside this range is a bad tag, not a lens. */
    private const val MIN_PLAUSIBLE_F35 = 8
    private const val MAX_PLAUSIBLE_F35 = 1200

    /**
     * A band of rows is about this many pixels (4 bytes each). The compositor keeps two band buffers
     * per worker thread (up to [PanoCompositor.MAX_WORKERS]), so its Java heap is up to 8 of these,
     * about 64 MB at most and about 16 MB for a typical sweep.
     */
    private const val BAND_PIXELS = 2_000_000L
    private const val MIN_BAND_ROWS = 8
    private const val MAX_BAND_ROWS = 256

    /** One step down the out-of-memory ladder multiplies the pixel budget by this (edges by 0.75). */
    private const val LADDER_AREA_FACTOR = 0.5625

    /**
     * The focal length in pixels of a photo [widthPx] x [heightPx]: `f35 * diagonal_px / 43.27` when
     * the EXIF 35 mm-equivalent [focal35mm] is present and plausible, otherwise the focal length that
     * gives [fallbackFovDeg] across the diagonal. The diagonal is used for both so the two branches
     * agree on what "field of view" means, and so a cropped or rotated photo needs no special case.
     */
    fun focalPixels(
        focal35mm: Int?,
        widthPx: Int,
        heightPx: Int,
        fallbackFovDeg: Double = DEFAULT_FALLBACK_FOV_DEG,
    ): Double {
        require(widthPx > 0 && heightPx > 0) { "a photo must have a size" }
        val diagonal = hypot(widthPx.toDouble(), heightPx.toDouble())
        if (focal35mm != null && focal35mm in MIN_PLAUSIBLE_F35..MAX_PLAUSIBLE_F35) {
            return focal35mm * diagonal / FULL_FRAME_DIAGONAL_MM
        }
        val fov = if (fallbackFovDeg.isNaN()) DEFAULT_FALLBACK_FOV_DEG else fallbackFovDeg.coerceIn(10.0, 170.0)
        return (diagonal / 2.0) / tan(Math.toRadians(fov) / 2.0)
    }

    /** A point on a plane or a cylinder, in pixels. */
    class Point(val x: Double, val y: Double)

    /** Photo pixel (x, y), from the principal point, to cylinder (u, v): `u = f*atan(x/f)`, `v = f*y/hypot(x, f)`. */
    fun forward(x: Double, y: Double, f: Double): Point =
        Point(planeToCylinderX(x, f), f * y / hypot(x, f))

    /**
     * Cylinder (u, v) back to the photo plane, or null when [u] is at or beyond a quarter turn
     * (`|u| >= f*pi/2`), where the plane never reaches: `x = f*tan(u/f)`, `y = v / cos(u/f)`.
     */
    fun inverse(u: Double, v: Double, f: Double): Point? {
        val theta = u / f
        if (kotlin.math.abs(theta) >= MAX_ANGLE) return null
        return Point(f * tan(theta), v / cos(theta))
    }

    /** The column part of [forward] on its own, so a compositor can tabulate it once per column. */
    fun planeToCylinderX(x: Double, f: Double): Double = f * atan(x / f)

    /** The column part of [inverse]: the plane column for cylinder column [u]; infinite at a quarter turn. */
    fun cylinderToPlaneX(u: Double, f: Double): Double = f * tan(u / f)

    /** How far the plane row stretches at cylinder column [u]: `1 / cos(u/f)`, so `y = v * rowStretch(u, f)`. */
    fun rowStretch(u: Double, f: Double): Double = 1.0 / cos(u / f)

    /**
     * The bounding size of a [widthPx] x [heightPx] photo once warped onto the cylinder: `2*f*atan(w/2f)`
     * wide, and as tall as it was (the centre column is not stretched; the edges only shrink).
     */
    fun warpedSize(widthPx: Int, heightPx: Int, f: Double): PanoSize =
        PanoSize(max(1, (2.0 * planeToCylinderX(widthPx / 2.0, f)).roundToInt()), heightPx)

    private const val MAX_ANGLE = PI / 2 - 1e-6

    // ---- layout of the canvas ----------------------------------------------------------------

    /** Why a [Plan] is smaller than the photos would make it. */
    enum class Limit { NONE, PIXELS, EDGE }

    /**
     * The canvas an export is made on. [fullWidth] x [fullHeight] is the size the placed photos span
     * at their own resolution; [canvasWidth] x [canvasHeight] is what will actually be rendered, at
     * [scale] (never above 1: a panorama is not enlarged). [bandHeight] is the number of canvas rows
     * the compositor renders at a time.
     */
    class Plan(
        val fullWidth: Long,
        val fullHeight: Long,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val scale: Double,
        val bandHeight: Int,
        val limit: Limit,
    ) {
        val pixels: Long get() = canvasWidth.toLong() * canvasHeight
    }

    /**
     * Chain [offsets] (the aligner's pairwise answers, `offsets[i]` places photo i+1 in photo i's
     * grid) into each photo's top-left in the FIRST photo's grid. The first photo is at (0, 0).
     * Positions can be negative (a frame held higher) and are not normalised here.
     */
    fun place(offsets: List<Offset>): List<PanoPoint> {
        val points = ArrayList<PanoPoint>(offsets.size + 1)
        var x = 0L
        var y = 0L
        points += PanoPoint(0, 0)
        for (o in offsets) {
            x += o.dx
            y += o.dy
            require(x in Int.MIN_VALUE..Int.MAX_VALUE && y in Int.MIN_VALUE..Int.MAX_VALUE) { "offsets run off the grid" }
            points += PanoPoint(x.toInt(), y.toInt())
        }
        return points
    }

    /** [plan] for pairwise [offsets]: `offsets.size` must be `photoSizes.size - 1`. */
    fun plan(photoSizes: List<PanoSize>, offsets: List<Offset>, maxPixels: Long = DEFAULT_MAX_PIXELS): Plan {
        require(offsets.size == photoSizes.size - 1) { "need one offset between each neighbouring pair" }
        return planPlaced(photoSizes, place(offsets), maxPixels)
    }

    /**
     * The canvas for photos of [photoSizes] placed at [positions] (top-left corners in one shared grid),
     * rendered at no more than [maxPixels] pixels. The budget is clamped to the editor's own export
     * ceilings ([MAX_EXPORT_PIXELS] pixels, [MAX_EXPORT_EDGE] on a side), and the photos are never
     * enlarged: the result has no more pixels than the budget, no edge past the ceiling and a scale
     * of at most 1.
     *
     * The width is the largest integer for which the canvas fits, so lowering [maxPixels] can never
     * raise the scale or the pixel count (the out-of-memory ladder relies on that). All the
     * arithmetic is Long: twelve 12-megapixel photos span well past what an Int can count.
     */
    fun planPlaced(photoSizes: List<PanoSize>, positions: List<PanoPoint>, maxPixels: Long = DEFAULT_MAX_PIXELS): Plan {
        require(photoSizes.isNotEmpty() && photoSizes.size == positions.size) { "one position per photo" }
        require(maxPixels > 0) { "the pixel budget must be positive" }
        var minX = Long.MAX_VALUE
        var minY = Long.MAX_VALUE
        var maxX = Long.MIN_VALUE
        var maxY = Long.MIN_VALUE
        for (i in photoSizes.indices) {
            val s = photoSizes[i]
            val p = positions[i]
            require(s.width > 0 && s.height > 0) { "a photo must have a size" }
            minX = min(minX, p.x.toLong()); minY = min(minY, p.y.toLong())
            maxX = max(maxX, p.x.toLong() + s.width); maxY = max(maxY, p.y.toLong() + s.height)
        }
        val fullWidth = maxX - minX
        val fullHeight = maxY - minY
        val budget = min(maxPixels, MAX_EXPORT_PIXELS)

        fun rowsFor(w: Long): Long = max(1L, Math.round(fullHeight.toDouble() * w / fullWidth))
        fun pixelsOk(w: Long): Boolean = w * rowsFor(w) <= budget
        fun edgeOk(w: Long): Boolean = w <= MAX_EXPORT_EDGE && rowsFor(w) <= MAX_EXPORT_EDGE
        require(pixelsOk(1) && edgeOk(1)) { "the photos span a canvas too thin to render" }

        // Start a little high and walk down: the float estimate is only a starting point, the
        // integer test is the rule.
        val estimate = sqrt(budget.toDouble() / (fullWidth.toDouble() * fullHeight.toDouble()))
        var width = min(fullWidth, (min(1.0, estimate) * fullWidth).toLong() + 2)
        while (width > 1 && !(pixelsOk(width) && edgeOk(width))) width--
        val height = rowsFor(width)

        val limit = when {
            width == fullWidth -> Limit.NONE
            pixelsOk(width + 1) -> Limit.EDGE
            else -> Limit.PIXELS
        }
        val cw = width.toInt()
        val ch = height.toInt()
        val rows = (BAND_PIXELS / cw).toInt().coerceIn(MIN_BAND_ROWS, MAX_BAND_ROWS)
        return Plan(fullWidth, fullHeight, cw, ch, width.toDouble() / fullWidth, min(rows, ch), limit)
    }

    /**
     * The out-of-memory ladder: [planPlaced] at [maxPixels], then at 0.5625x the budget (edges x0.75)
     * and so on, ending on the first plan whose long side is at or under the editor's ladder floor
     * ([MIN_LADDER_EDGE]). Largest first, strictly shrinking, never empty; a caller tries the next rung
     * only after an [OutOfMemoryError] on the previous one, as [com.fotoxplorr.app.editor.renderDownTheLadder] does.
     */
    fun ladder(photoSizes: List<PanoSize>, positions: List<PanoPoint>, maxPixels: Long = DEFAULT_MAX_PIXELS): List<Plan> {
        val rungs = ArrayList<Plan>()
        var budget = maxPixels.toDouble()
        while (true) {
            val plan = planPlaced(photoSizes, positions, max(1L, budget.toLong()))
            val previous = rungs.lastOrNull()
            if (previous == null || plan.pixels < previous.pixels) rungs += plan
            if (max(plan.canvasWidth, plan.canvasHeight) <= MIN_LADDER_EDGE || plan.canvasWidth == 1) break
            budget *= LADDER_AREA_FACTOR
        }
        return rungs
    }
}
