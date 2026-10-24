package com.fotoxplorr.app.pano

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One brightness multiplier per photo, so the seams of a sweep do not show a step where the phone's
 * auto-exposure changed its mind between frames.
 *
 * ## The idea
 * Where two photos overlap they show the same scene, so their average brightness over the overlap
 * should match; if photo a is 10% brighter there, a is exposed 10% higher and its multiplier should be
 * 10% lower than b's. Every overlapping pair gives one such ratio, and the multipliers that disagree
 * with the fewest ratios (least squares on their logarithms, so "twice as bright" and "half as bright"
 * weigh the same) are the answer. With the photos laid out by [PanoLayout] a photo overlaps its
 * neighbours and, in a generous sweep, the one after that too, so the extra pairs make the answer
 * agree with itself instead of piling up the error of a chain.
 *
 * ## What a "ratio" is here
 * The plain ratio of two averages is thrown by the one thing a sweep always has: a person walking
 * through the overlap. So the overlap is cut into small blocks and the ratio is the MEDIAN of the
 * blocks' ratios, which is the same number for a pure exposure change and ignores a mover that fills
 * less than half the overlap. Pixels at either end of the range (clipped shadow or highlight) are left
 * out of both images together, because a clipped pixel hides the change it would show.
 *
 * ## What it is not
 * One scalar per photo: no white balance, no vignetting, no curve. A phone's auto-exposure is closer
 * to a gain plus an offset than to a pure gain, and a pure gain cannot undo an offset; the tests
 * measure how much that costs. The photos should be the same kind of pixels the compositor mixes
 * (the multiplier is applied to the photo's own pixels, in the same encoding it was measured in), so
 * the job measures on the working-size grey it already decoded for the aligner and applies the
 * multiplier to every channel. Mean multiplier is 1 so the panorama's overall brightness stays about
 * what the photos had, and every multiplier is held to [MIN_GAIN]..[MAX_GAIN]: a bigger correction
 * is a mismatch (not an exposure change) and brightening a photo four-fold would only show noise.
 *
 * Pure, deterministic, cancellable between pairs.
 */
object ExposureGain {

    /** The multiplier is never outside this range; see the class note. */
    const val MIN_GAIN = 0.5
    const val MAX_GAIN = 2.0

    /** Overlap blocks are this many pixels on a side. */
    private const val BLOCK = 32

    /** An overlap with fewer usable blocks than this says too little to trust (about 6 blocks = 6000 px). */
    private const val MIN_BLOCKS = 6

    /** A pixel at or beyond these levels, in either image, is clipped and left out of the comparison. */
    private const val CLIP_LOW = 4
    private const val CLIP_HIGH = 251

    /** A block needs at least this share of its pixels unclipped, and both averages above [MIN_BLOCK_MEAN]. */
    private const val MIN_BLOCK_FILL = 0.5
    private const val MIN_BLOCK_MEAN = 12.0

    /** A pull toward "no change" so a photo with no measured pair keeps 1.0 and the equations never go singular. */
    private const val RIDGE = 1e-6

    private const val CLAMP_ROUNDS = 8

    /**
     * What one overlap says: image [a] is [ratio] times as bright as image [b] over their common view
     * (`ratio = a / b`), and [weight] says how much evidence that is (usable blocks).
     */
    class PairRatio(val a: Int, val b: Int, val ratio: Double, val weight: Double)

    /** One measured overlap: the median block ratio and how many blocks it stands on. */
    class Measured(val ratio: Double, val blocks: Int)

    /**
     * @property gains the multiplier to apply to each photo's pixels, mean 1, each in [MIN_GAIN]..[MAX_GAIN].
     * @property clamped some multiplier hit a limit, so the mean may be a little off 1 and the seams may still show.
     * @property worstMismatch the largest disagreement left over, as a fraction: after the multipliers, the
     *   worst pair is still this much brighter or darker than its neighbour (0.03 is 3%). Small means the
     *   measurements agree with each other; large means one pair is a poor measurement (or the sweep has an
     *   exposure ramp that is not a gain).
     */
    class Result(val gains: List<Double>, val clamped: Boolean, val worstMismatch: Double)

    /**
     * How much brighter [first] is than [second] over their overlap, where [second]'s top-left is at
     * ([dx], [dy]) in [first]'s grid ([Offset]); null when the overlap is too small, too dark or too
     * clipped to say.
     */
    fun measure(first: GrayImage, second: GrayImage, dx: Int, dy: Int): Measured? {
        val x0 = max(0, dx)
        val y0 = max(0, dy)
        val x1 = min(first.width.toLong(), dx.toLong() + second.width).toInt()
        val y1 = min(first.height.toLong(), dy.toLong() + second.height).toInt()
        val blocksX = (x1 - x0) / BLOCK
        val blocksY = (y1 - y0) / BLOCK
        if (blocksX <= 0 || blocksY <= 0 || blocksX.toLong() * blocksY < MIN_BLOCKS) return null
        // Centre the grid of blocks in the overlap rather than hugging its top-left corner.
        val startX = x0 + ((x1 - x0) - blocksX * BLOCK) / 2
        val startY = y0 + ((y1 - y0) - blocksY * BLOCK) / 2

        val logs = DoubleArray(blocksX * blocksY)
        var used = 0
        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                var sumA = 0L
                var sumB = 0L
                var count = 0
                for (row in 0 until BLOCK) {
                    val ya = startY + by * BLOCK + row
                    val rowA = ya * first.width
                    val rowB = (ya - dy) * second.width
                    for (col in 0 until BLOCK) {
                        val xa = startX + bx * BLOCK + col
                        val a = first.pixels[rowA + xa].toInt() and 0xFF
                        val b = second.pixels[rowB + xa - dx].toInt() and 0xFF
                        if (a <= CLIP_LOW || a >= CLIP_HIGH || b <= CLIP_LOW || b >= CLIP_HIGH) continue
                        sumA += a
                        sumB += b
                        count++
                    }
                }
                if (count < BLOCK * BLOCK * MIN_BLOCK_FILL) continue
                val meanA = sumA.toDouble() / count
                val meanB = sumB.toDouble() / count
                if (meanA < MIN_BLOCK_MEAN || meanB < MIN_BLOCK_MEAN) continue
                logs[used++] = ln(meanA / meanB)
            }
        }
        if (used < MIN_BLOCKS) return null
        logs.sort(0, used)
        val median = if (used % 2 == 1) logs[used / 2] else (logs[used / 2 - 1] + logs[used / 2]) / 2
        return Measured(exp(median), used)
    }

    /**
     * Every overlapping pair of [images], the adjacent ones and any further ones that share a view,
     * with [positions] from [PanoLayout.Layout.positions] (one per image, one shared unit and grid).
     * Pairs that [measure] cannot read are left out.
     *
     * @throws java.util.concurrent.CancellationException when [shouldStop] turns true (checked once per pair).
     */
    fun measureAll(
        images: List<GrayImage>,
        positions: List<PanoPoint>,
        shouldStop: () -> Boolean = { false },
    ): List<PairRatio> {
        require(images.size == positions.size) { "one position per image" }
        val out = ArrayList<PairRatio>()
        for (i in images.indices) {
            for (j in i + 1 until images.size) {
                if (shouldStop()) throw CancellationException("exposure measurement superseded")
                val dx = positions[j].x.toLong() - positions[i].x
                val dy = positions[j].y.toLong() - positions[i].y
                if (abs(dx) > images[i].width || abs(dy) > images[i].height) continue
                val m = measure(images[i], images[j], dx.toInt(), dy.toInt()) ?: continue
                out += PairRatio(i, j, m.ratio, m.blocks.toDouble())
            }
        }
        return out
    }

    /** [measureAll] then [solve]: the multipliers for a laid-out sweep. */
    fun compute(images: List<GrayImage>, positions: List<PanoPoint>, shouldStop: () -> Boolean = { false }): Result =
        solve(images.size, measureAll(images, positions, shouldStop))

    /**
     * The multipliers for [count] images given the measured [ratios]: least squares on
     * `ln(gain[a]) - ln(gain[b]) = -ln(ratio)`, each pair weighted by its evidence, then scaled to a
     * mean of 1 and held to [MIN_GAIN]..[MAX_GAIN]. An image in no pair gets 1.0, and so does a whole
     * island of images that no pair connects to the rest (each island is centred on its own).
     */
    fun solve(count: Int, ratios: List<PairRatio>): Result {
        require(count >= 1) { "need at least one image" }
        for (r in ratios) {
            require(r.a in 0 until count && r.b in 0 until count && r.a != r.b) { "a pair names two different images" }
            require(r.ratio > 0.0 && r.ratio.isFinite() && r.weight > 0.0 && r.weight.isFinite()) { "a ratio and its weight must be positive" }
        }
        val normal = Array(count) { DoubleArray(count) }
        val target = DoubleArray(count)
        var heaviest = 0.0
        for (r in ratios) {
            val w = r.weight
            val t = -ln(r.ratio)
            normal[r.a][r.a] += w
            normal[r.b][r.b] += w
            normal[r.a][r.b] -= w
            normal[r.b][r.a] -= w
            target[r.a] += w * t
            target[r.b] -= w * t
            heaviest = max(heaviest, w)
        }
        val ridge = RIDGE * max(1.0, heaviest)
        for (i in 0 until count) normal[i][i] += ridge
        val logGain = solveLinear(normal, target)

        val gains = DoubleArray(count) { exp(logGain[it]) }
        // Mean 1 and the limits are two constraints; alternate until both hold (they are convex and
        // compatible, so a few rounds settle it), and let the limits have the last word.
        var clamped = false
        for (round in 0 until CLAMP_ROUNDS) {
            val mean = gains.average()
            for (i in gains.indices) gains[i] /= mean
            var changed = false
            for (i in gains.indices) {
                val c = gains[i].coerceIn(MIN_GAIN, MAX_GAIN)
                if (c != gains[i]) { gains[i] = c; changed = true; clamped = true }
            }
            if (!changed) break
        }
        for (i in gains.indices) gains[i] = gains[i].coerceIn(MIN_GAIN, MAX_GAIN)

        var worst = 0.0
        for (r in ratios) {
            // After correcting, a's brightness over b's is ratio * gain[a] / gain[b]; 1 is a perfect seam.
            worst = max(worst, abs(r.ratio * gains[r.a] / gains[r.b] - 1.0))
        }
        return Result(gains.toList(), clamped, worst)
    }

    /** Gaussian elimination with partial pivoting; [a] is small (one row per photo) and positive definite. */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val v = b.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            val rowSwap = m[col]; m[col] = m[pivot]; m[pivot] = rowSwap
            val vSwap = v[col]; v[col] = v[pivot]; v[pivot] = vSwap
            for (row in col + 1 until n) {
                val f = m[row][col] / m[col][col]
                if (f == 0.0) continue
                for (k in col until n) m[row][k] -= f * m[col][k]
                v[row] -= f * v[col]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = v[row]
            for (k in row + 1 until n) s -= m[row][k] * x[k]
            x[row] = s / m[row][row]
        }
        return x
    }
}
