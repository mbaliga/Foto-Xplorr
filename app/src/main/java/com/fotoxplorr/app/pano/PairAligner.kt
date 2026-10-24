package com.fotoxplorr.app.pano

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where does the second photo of a sweep sit on the first? Two grey images in, one [Offset] out, in
 * pure Kotlin.
 *
 * ## The idea
 * After the cylindrical warp ([PanoGeometry]) a sweep is a sideways slide, so the answer is a
 * (dx, dy). Slide one image over the other and, for every slide, score how alike the overlapping
 * pixels are with zero-mean normalised cross-correlation: subtract each overlap's mean and divide
 * by its spread, so a photo that came out brighter, flatter or with a different black level
 * (auto-exposure moving between frames) still scores 1 against itself. Searching every slide at
 * full size is far too slow, so both images are box-halved into a short pyramid, EVERY slide is
 * tried at the tiny top level, and the best few are walked down the pyramid, each re-searched
 * within +-[Params.refineRadius] pixels per level.
 *
 * ## Never a confident wrong answer
 * A stitcher that joins the wrong place is worse than one that says "cannot join these". Carrying
 * several top-level candidates all the way down is what lets this notice trouble: the answer is
 * [Offset.confident] only when the best slide matches well, overlaps enough, AND no clearly
 * different slide matches nearly as well. A repeating fence or striped wall has many equally good
 * slides, a flat sky or a smooth gradient has all of them, and two unrelated photos have none that
 * is good: each of those comes back with `confident = false`.
 *
 * Two more checks run on the finished answer, because the walk down can be fooled where the top
 * level cannot. A repeating texture OVER real content (siding, blinds, a picket fence in front of a
 * street) is invisible at the top level, which averages the fine period away, so the walk starts
 * at the right slide and then slips a whole period at a finer level, where the search window is
 * under half a period. So every slide within about 48 px of the answer is scored at half size, the
 * best few local peaks are refined at full size and join the candidates: the right slide is found
 * and the comb of near-equal neighbours around it counts as rivals. And a wide, shallow peak
 * (faint clouds, a smooth wall: slides 6 to 8 px away score within [Params.shoulderMargin] of the
 * best) is ambiguous too, because the answer is only as good as the width of the peak. A texture
 * period longer than about 48 px at the working size (about 160 px on a 4000 px photo) is outside the
 * rescan and still unguarded: the walk down is trusted there. Measured on 108 stripe and grid
 * cases of period 30 to 60, a radius of 28 left two confidently wrong. All of this is measured on
 * procedural scenes ([PairAlignerTextureTest]); real photographs may have a different share of wide
 * peaks, so the margin may refuse stitches that were good, which is the safe direction.
 *
 * ## Honest ceiling
 * Translation only. A frame held with more than about a degree of roll, shear from a rolling
 * shutter, parallax from near objects, and large moving subjects all make the overlap not quite a
 * translation, so the answer lands on whichever part of the overlap dominates; the tests measure
 * how far that is rather than claiming it is zero. Integer pixels at the working size (about 1200
 * px wide), which is about 3 px at 4000. Real-photo behaviour is unproven until real handheld sweeps
 * have been through it.
 *
 * Pure, deterministic, and cancellable ([shouldStop]) so a job can abandon it between pairs.
 */
object PairAligner {

    /** Tunables. The defaults are what the tests and the panorama job use; they are not user-facing. */
    class Params(
        /** Pyramid depth, level 0 being the input. Fewer if the image is too small to halve this often. */
        val levels: Int = 5,
        /** The smallest overlap the search will try, as a fraction of the smaller image's area. */
        val minOverlap: Float = 0.125f,
        /** Vertical search range at every level's scale, as a fraction of the image height. */
        val maxDyFraction: Float = 0.25f,
        /** Top-level peaks carried down the pyramid. */
        val candidates: Int = 6,
        /** Peaks kept when going from level 2 to the finer, expensive levels. */
        val keepAtFineLevels: Int = 4,
        /** Re-search +- this many pixels around each candidate at every level on the way down. */
        val refineRadius: Int = 2,
        /** A match scoring below this is not trusted. */
        val minScore: Float = 0.55f,
        /** Less overlap than this (fraction of the smaller image) is not trusted even if it matches. */
        val minConfidentOverlap: Float = 0.15f,
        /** An overlap of fewer pixels than this (at the input size) is too little evidence to trust. */
        val minConfidentPixels: Int = 4096,
        /** A different slide within this much of the best score makes the answer ambiguous. */
        val ambiguityMargin: Float = 0.05f,
        /** Two finished candidates closer than this (pixels, both axes) are the same peak. */
        val samePeakDistance: Int = 4,
        /** Slides 6 to 8 px from the best scoring within this of it mean the peak is too wide to trust to the pixel. */
        val shoulderMargin: Float = 0.01f,
    )

    /**
     * Align [second] onto [first]; see [Offset] for the convention. Always returns an [Offset]:
     * when nothing overlaps well enough, `confident` is false and dx/dy are only a best guess.
     *
     * @throws java.util.concurrent.CancellationException when [shouldStop] turns true mid-search.
     */
    fun align(
        first: GrayImage,
        second: GrayImage,
        params: Params = Params(),
        shouldStop: () -> Boolean = NEVER_STOP,
    ): Offset {
        val a = pyramid(first, params.levels)
        val b = pyramid(second, a.size)
        val levels = min(a.size, b.size)
        val top = levels - 1
        val minOverlapFraction = params.minOverlap

        // 1. every slide at the top level
        val topA = a[top]
        val topB = b[top]
        val topCorrelator = Correlator(topA, topB, minOverlapFraction, shouldStop)
        val dyRange = max(1, (min(topA.height, topB.height) * params.maxDyFraction).toInt())
        val dxMin = -topB.width
        val dxMax = topA.width
        val columns = dxMax - dxMin + 1
        val rows = 2 * dyRange + 1
        val map = FloatArray(columns * rows) { NO_SCORE }
        for (dy in -dyRange..dyRange) {
            for (dx in dxMin..dxMax) {
                map[(dy + dyRange) * columns + (dx - dxMin)] = topCorrelator.score(dx, dy)
            }
        }
        var found = peaks(map, columns, rows, params.candidates).map { Candidate(it.x + dxMin, it.y - dyRange, it.score) }
        if (found.isEmpty()) return Offset(0, 0, 0f, 0f, false)

        // 2. walk them down, re-searching a small window at each finer level
        for (level in top - 1 downTo 0) {
            val correlator = Correlator(a[level], b[level], minOverlapFraction, shouldStop)
            val refined = ArrayList<Candidate>(found.size)
            for (candidate in found) {
                var best = Candidate(candidate.dx * 2, candidate.dy * 2, NO_SCORE)
                for (dy in -params.refineRadius..params.refineRadius) {
                    for (dx in -params.refineRadius..params.refineRadius) {
                        val s = correlator.score(candidate.dx * 2 + dx, candidate.dy * 2 + dy)
                        if (s > best.score) best = Candidate(candidate.dx * 2 + dx, candidate.dy * 2 + dy, s)
                    }
                }
                if (best.score > NO_SCORE && refined.none { abs(it.dx - best.dx) <= 1 && abs(it.dy - best.dy) <= 1 }) refined += best
            }
            found = refined.sortedByDescending { it.score }
            if (level < 2) found = found.take(params.keepAtFineLevels)
            if (found.isEmpty()) return Offset(0, 0, 0f, 0f, false)
        }

        // 3. rescan around the answer. The walk down re-searches only +-refineRadius per level, which
        //    is less than half a period of a repeating texture (siding, blinds, a fence over a street),
        //    so over real content it can settle one or two periods out with a good-looking score.
        //    Every slide near the answer is scored at a fine level, and the best few local peaks are
        //    refined at full size and join the candidates: the right slide is found, and the comb of
        //    near-equal neighbours around it is seen as the rivals it is.
        val scanLevel = min(1, levels - 1)
        val scale = 1 shl scanLevel
        val radius = RESCAN_RADIUS / scale
        val side = 2 * radius + 1
        val scanner = Correlator(a[scanLevel], b[scanLevel], minOverlapFraction, shouldStop)
        val centreX = Math.floorDiv(found.first().dx + scale / 2, scale)
        val centreY = Math.floorDiv(found.first().dy + scale / 2, scale)
        val local = FloatArray(side * side)
        for (j in 0 until side) for (i in 0 until side) local[j * side + i] = scanner.score(centreX - radius + i, centreY - radius + j)
        val full = Correlator(a[0], b[0], minOverlapFraction, shouldStop)
        val merged = ArrayList<Candidate>(found)
        for (peak in peaks(local, side, side, RESCAN_PEAKS, localOnly = true)) {
            val px = (centreX - radius + peak.x) * scale
            val py = (centreY - radius + peak.y) * scale
            var refined = Candidate(px, py, NO_SCORE)
            for (dy in -params.refineRadius..params.refineRadius) {
                for (dx in -params.refineRadius..params.refineRadius) {
                    val s = full.score(px + dx, py + dy)
                    if (s > refined.score) refined = Candidate(px + dx, py + dy, s)
                }
            }
            if (refined.score > NO_SCORE && merged.none { abs(it.dx - refined.dx) <= 1 && abs(it.dy - refined.dy) <= 1 }) merged += refined
        }
        found = merged.sortedByDescending { it.score }

        // 4. judge the finished candidates at full working size
        val best = found.first()
        val overlap = full.overlapFraction(best.dx, best.dy)
        val rival = found.drop(1).firstOrNull {
            abs(it.dx - best.dx) > params.samePeakDistance || abs(it.dy - best.dy) > params.samePeakDistance
        }
        // A wide, shallow peak: slides 6 to 8 px away score almost the same as the best one, so the
        // answer is only good to the width of the peak. Judged on the rescan's own scores, same level.
        val bestCellX = Math.floorDiv(best.dx + scale / 2, scale) - (centreX - radius)
        val bestCellY = Math.floorDiv(best.dy + scale / 2, scale) - (centreY - radius)
        var atBest = NO_SCORE
        var shoulder = NO_SCORE
        for (j in 0 until side) {
            for (i in 0 until side) {
                val distance = scale * max(abs(i - bestCellX), abs(j - bestCellY))
                if (distance <= scale) atBest = max(atBest, local[j * side + i])
                else if (distance in SHOULDER_NEAR..SHOULDER_FAR) shoulder = max(shoulder, local[j * side + i])
            }
        }
        val wide = atBest > NO_SCORE && shoulder > NO_SCORE && atBest - shoulder < params.shoulderMargin
        val ambiguous = wide || (rival != null && best.score - rival.score < params.ambiguityMargin)
        val overlapPixels = overlap.toDouble() * min(first.width.toLong() * first.height, second.width.toLong() * second.height)
        val confident = best.score >= params.minScore && overlap >= params.minConfidentOverlap &&
            overlapPixels >= params.minConfidentPixels && !ambiguous
        return Offset(best.dx, best.dy, best.score, overlap, confident)
    }

    // ---- pyramid ---------------------------------------------------------------------------

    private class Level(val width: Int, val height: Int, val pixels: ByteArray)

    private const val SMALLEST_TOP_EDGE = 24

    private fun pyramid(image: GrayImage, wanted: Int): List<Level> {
        val levels = ArrayList<Level>()
        levels += Level(image.width, image.height, image.pixels)
        while (levels.size < wanted) {
            val last = levels.last()
            if (last.width / 2 < SMALLEST_TOP_EDGE || last.height / 2 < SMALLEST_TOP_EDGE) break
            levels += halve(last)
        }
        return levels
    }

    /** Box filter: each output pixel is the rounded mean of a 2x2 block (an odd last row/column is dropped). */
    private fun halve(level: Level): Level {
        val w = level.width / 2
        val h = level.height / 2
        val out = ByteArray(w * h)
        val src = level.pixels
        for (y in 0 until h) {
            val top = 2 * y * level.width
            val bottom = top + level.width
            for (x in 0 until w) {
                val sum = (src[top + 2 * x].toInt() and 0xFF) + (src[top + 2 * x + 1].toInt() and 0xFF) +
                    (src[bottom + 2 * x].toInt() and 0xFF) + (src[bottom + 2 * x + 1].toInt() and 0xFF)
                out[y * w + x] = ((sum + 2) shr 2).toByte()
            }
        }
        return Level(w, h, out)
    }

    // ---- correlation -----------------------------------------------------------------------

    private const val NO_SCORE = -2f

    /** Below this spread (grey levels, per image, over the overlap) an image has nothing to correlate. */
    private const val MIN_VARIANCE = 1.0

    private class Candidate(val dx: Int, val dy: Int, val score: Float)

    private class Peak(val x: Int, val y: Int, val score: Float)

    /** Zero-mean normalised cross-correlation of two levels at a slide, with the overlap rules baked in. */
    private class Correlator(
        val a: Level,
        val b: Level,
        val minOverlap: Float,
        val shouldStop: () -> Boolean,
    ) {
        private val minPixels = (min(a.width.toLong() * a.height, b.width.toLong() * b.height) * minOverlap).toLong().coerceAtLeast(16)

        fun overlapFraction(dx: Int, dy: Int): Float {
            val w = min(a.width, dx + b.width) - max(0, dx)
            val h = min(a.height, dy + b.height) - max(0, dy)
            if (w <= 0 || h <= 0) return 0f
            return (w.toLong() * h).toFloat() / min(a.width.toLong() * a.height, b.width.toLong() * b.height)
        }

        /** NCC in -1..1, or [NO_SCORE] when the slide overlaps less than the minimum. */
        fun score(dx: Int, dy: Int): Float {
            if (shouldStop()) throw CancellationException("alignment superseded")
            val x0 = max(0, dx)
            val x1 = min(a.width, dx + b.width)
            val y0 = max(0, dy)
            val y1 = min(a.height, dy + b.height)
            if (x1 <= x0 || y1 <= y0) return NO_SCORE
            val width = x1 - x0
            val n = width.toLong() * (y1 - y0)
            if (n < minPixels) return NO_SCORE
            var sa = 0L
            var sb = 0L
            var saa = 0L
            var sbb = 0L
            var sab = 0L
            val pa = a.pixels
            val pb = b.pixels
            for (y in y0 until y1) {
                var ia = y * a.width + x0
                var ib = (y - dy) * b.width + (x0 - dx)
                var ra = 0
                var rb = 0
                var raa = 0
                var rbb = 0
                var rab = 0
                for (i in 0 until width) {
                    val va = pa[ia++].toInt() and 0xFF
                    val vb = pb[ib++].toInt() and 0xFF
                    ra += va
                    rb += vb
                    raa += va * va
                    rbb += vb * vb
                    rab += va * vb
                }
                sa += ra; sb += rb; saa += raa; sbb += rbb; sab += rab
            }
            val nd = n.toDouble()
            val varA = saa - sa.toDouble() * sa / nd
            val varB = sbb - sb.toDouble() * sb / nd
            if (varA < MIN_VARIANCE * nd || varB < MIN_VARIANCE * nd) return 0f
            val cov = sab - sa.toDouble() * sb / nd
            return (cov / sqrt(varA * varB)).toFloat()
        }
    }

    /** The best [count] local maxima of a score map, each at least [PEAK_SEPARATION] cells from the ones before it. */
    private fun peaks(map: FloatArray, columns: Int, rows: Int, count: Int, localOnly: Boolean = false): List<Peak> {
        val taken = ArrayList<Peak>()
        val blocked = BooleanArray(map.size)
        while (taken.size < count) {
            var bestIndex = -1
            var best = NO_SCORE
            for (i in map.indices) {
                if (blocked[i] || map[i] <= best) continue
                if (localOnly && !isLocalMaximum(map, columns, rows, i)) continue
                best = map[i]; bestIndex = i
            }
            if (bestIndex < 0) break
            val px = bestIndex % columns
            val py = bestIndex / columns
            taken += Peak(px, py, best)
            for (y in max(0, py - PEAK_SEPARATION)..min(rows - 1, py + PEAK_SEPARATION)) {
                for (x in max(0, px - PEAK_SEPARATION)..min(columns - 1, px + PEAK_SEPARATION)) blocked[y * columns + x] = true
            }
        }
        return taken
    }

    private const val PEAK_SEPARATION = 3

    /** Whether cell [i] scores at least as much as each of its (up to eight) neighbours in the map. */
    private fun isLocalMaximum(map: FloatArray, columns: Int, rows: Int, i: Int): Boolean {
        val x = i % columns
        val y = i / columns
        for (ny in max(0, y - 1)..min(rows - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(columns - 1, x + 1)) if (map[ny * columns + nx] > map[i]) return false
        }
        return true
    }

    /** The rescan of step 3 covers this many pixels (at the input size) each way around the answer: periods up to about 48 px. Periods beyond it are not guarded. */
    private const val RESCAN_RADIUS = 48

    /** The ring of slides (pixels from the best, at the input size) that tells a wide peak from a sharp one. */
    private const val SHOULDER_NEAR = 6
    private const val SHOULDER_FAR = 8

    /** Local peaks of the rescan that are refined at full size and kept as candidates. */
    private const val RESCAN_PEAKS = 4

    private val NEVER_STOP: () -> Boolean = { false }
}
