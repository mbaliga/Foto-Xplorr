package com.fotoxplorr.app.lift

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Scribble-driven subject selection, in pure Kotlin: the person marks a little of the subject
 * (and, if they like, a little of what is NOT the subject) and this finds the boundary between.
 *
 * ## The idea
 * Every pixel is given two numbers: how "far" it is from the foreground marks and how far from the
 * background marks, where far means the cheapest PATH there, not the straight-line distance. A
 * step costs a little by default, more when it walks across a colour edge, and more again when it
 * walks over pixels whose colour looks like the OTHER side (colour histograms learnt from the
 * marks themselves). A pixel belongs to the subject when it is geodesically closer to the
 * foreground marks. Because a strong edge makes the path across it expensive, the two fields meet
 * at the edge rather than halfway between the marks, and a scribble only has to be roughly right.
 *
 * ## Why this and not GrabCut
 * GrabCut needs Gaussian mixtures and a max-flow solver (hundreds of lines, several passes); this
 * is two Dijkstra sweeps and a few flood fills, deterministic, O(N log N), and fast enough at the
 * ~512px working size to be re-run after every stroke. It has the same honest ceiling as every
 * classical method: hair, glass and a subject that shares its colours with its surroundings need
 * a learned model, which the offline build deliberately does not bundle. GrabCut can later sit
 * behind [solveRegion] without anything above it changing.
 *
 * Pure, Android-free and cancellable ([shouldStop]) so it can run on a background dispatcher and
 * be abandoned the moment a newer stroke makes its answer stale.
 */
object GeodesicSegmenter {

    /** Tunables. The defaults are what the tests and the editor use; they are not user-facing. */
    class Params(
        /** Extra cost per step for pixels that look like the other side's colours, in "pixel steps". */
        val colourWeight: Float = 3f,
        /** Cost of a full-contrast edge crossing, in "pixel steps". Large so the boundary SNAPS to the edge. */
        val edgeWeight: Float = 600f,
        /** Smoothed RGB difference (0..1.73) below which a neighbour step is not an edge at all: sensor noise. */
        val edgeFloor: Float = 0.04f,
        /** Holes in the selection no bigger than this fraction of the image are filled in. */
        val maxHoleFraction: Float = 0.02f,
        /** Soften the cut by this many pixels (box blur of the alpha), see [SubjectSegmentation.feather]. */
        val featherRadius: Int = 1,
        /** Treat frame pixels whose colour the foreground marks have never shown as background marks too. */
        val borderPrior: Boolean = true,
    )

    /** [solveRegion] feathered into the mask type the rest of lift already speaks. */
    fun solve(
        pixels: IntArray,
        width: Int,
        height: Int,
        foreground: BooleanArray,
        background: BooleanArray,
        params: Params = Params(),
        shouldStop: () -> Boolean = NEVER_STOP,
    ): SubjectSegmentation.Mask {
        val region = solveRegion(pixels, width, height, foreground, background, params, shouldStop)
        return SubjectSegmentation.feather(region, width, height, params.featherRadius)
    }

    /**
     * The hard subject region. Always includes every foreground mark that is not also a background
     * mark's pixel; empty only when there are no foreground marks at all.
     *
     * @throws java.util.concurrent.CancellationException when [shouldStop] turns true mid-solve.
     */
    fun solveRegion(
        pixels: IntArray,
        width: Int,
        height: Int,
        foreground: BooleanArray,
        background: BooleanArray,
        params: Params = Params(),
        shouldStop: () -> Boolean = NEVER_STOP,
    ): BooleanArray {
        val n = width * height
        require(pixels.size == n && foreground.size == n && background.size == n) {
            "pixel/seed arrays must all be ${width}x$height"
        }
        if (n == 0 || foreground.none { it }) return BooleanArray(n)

        val fgSeeds = foreground.copyOf()
        val bgSeeds = background.copyOf()
        for (i in 0 until n) if (fgSeeds[i]) bgSeeds[i] = false

        val fgHist = histogram(pixels, fgSeeds)
        if (params.borderPrior) addBorderPrior(pixels, width, height, fgHist, fgSeeds, bgSeeds)
        val bgHist = histogram(pixels, bgSeeds)

        val likelihood = FloatArray(n) { foregroundLikelihood(pixels[it], fgHist, bgHist) }
        val smooth = SmoothedColour(pixels, width, height)

        val dFg = distanceField(smooth, likelihood, fgSeeds, foregroundField = true, width, height, params, shouldStop)
        val dBg = distanceField(smooth, likelihood, bgSeeds, foregroundField = false, width, height, params, shouldStop)

        val region = BooleanArray(n) { fgSeeds[it] || (!bgSeeds[it] && dFg[it] < dBg[it]) }
        val connected = keepComponentsTouching(region, fgSeeds, width, height)
        fillSmallHoles(connected, bgSeeds, width, height, (params.maxHoleFraction * n).toInt())
        return connected
    }

    // ---- colour model ----------------------------------------------------------------------

    private const val BINS = 16
    private const val BIN_SHIFT = 4
    private const val HIST_SIZE = BINS * BINS * BINS
    private const val NEIGHBOUR_SPREAD = 0.35f
    private const val LIKELIHOOD_EPSILON = 0.002f

    private fun binOf(pixel: Int): Int =
        (((pixel shr 16) and 0xFF) shr BIN_SHIFT shl 8) or
            (((pixel shr 8) and 0xFF) shr BIN_SHIFT shl 4) or
            ((pixel and 0xFF) shr BIN_SHIFT)

    /**
     * A normalised 16x16x16 colour histogram of the marked pixels. Each pixel also leaks a little
     * weight into the six face-adjacent bins: a mark over a subtly graded or noisy surface would
     * otherwise teach the model a handful of razor-thin bins, and the next pixel over -- one bin
     * across -- would read as "never seen". All zero when nothing is marked.
     */
    private fun histogram(pixels: IntArray, marked: BooleanArray): FloatArray {
        val hist = FloatArray(HIST_SIZE)
        var count = 0
        for (i in pixels.indices) {
            if (!marked[i]) continue
            count++
            val bin = binOf(pixels[i])
            hist[bin] += 1f
            val r = bin shr 8
            val g = (bin shr 4) and 0xF
            val b = bin and 0xF
            if (r > 0) hist[bin - 256] += NEIGHBOUR_SPREAD
            if (r < BINS - 1) hist[bin + 256] += NEIGHBOUR_SPREAD
            if (g > 0) hist[bin - 16] += NEIGHBOUR_SPREAD
            if (g < BINS - 1) hist[bin + 16] += NEIGHBOUR_SPREAD
            if (b > 0) hist[bin - 1] += NEIGHBOUR_SPREAD
            if (b < BINS - 1) hist[bin + 1] += NEIGHBOUR_SPREAD
        }
        if (count == 0) return hist
        var total = 0f
        for (v in hist) total += v
        for (i in hist.indices) hist[i] /= total
        return hist
    }

    /**
     * P(this colour is subject), 0..1: 0.5 for a colour neither side has shown yet, so the model
     * pushes only where the marks have actually said something and the edges decide the rest.
     */
    private fun foregroundLikelihood(pixel: Int, fgHist: FloatArray, bgHist: FloatArray): Float {
        val bin = binOf(pixel)
        val f = fgHist[bin] + LIKELIHOOD_EPSILON
        val b = bgHist[bin] + LIKELIHOOD_EPSILON
        return f / (f + b)
    }

    /**
     * With no background marks the solver would have nothing to be "closer to" than the subject,
     * so the frame itself votes -- but only the frame pixels whose colour the foreground marks
     * have never shown. A subject that touches the border (most photographs) must not have its own
     * border pixels declared background, or the cut would be shaved off at the frame.
     *
     * It stays on after the person adds background scribbles, on purpose. Switching it off at that
     * moment makes the very first refinement WORSE: a small scribble then becomes the only thing
     * the whole unmarked background can be "closer to", and everything nearer the subject than the
     * scribble leaks into the cut. The scribbles add to the frame's vote, they do not replace it.
     *
     * It never leaves [bgSeeds] empty (unless every pixel is a [fgSeeds] pixel). A sloppy scribble
     * that touches a colour the frame also has used to vote nobody, and with nothing to be closer
     * to every pixel was "closer to the subject": the whole photo. So when the strict vote finds
     * no one, the frame pixels the marks resemble LEAST ([lowestFrameResemblance]) vote instead,
     * and as a last resort the least subject-like pixel anywhere. A weak background is a better
     * answer than none: the colour model and the edges still decide where the two meet.
     */
    private fun addBorderPrior(
        pixels: IntArray,
        width: Int,
        height: Int,
        fgHist: FloatArray,
        fgSeeds: BooleanArray,
        bgSeeds: BooleanArray,
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!onFrame(x, y, width, height)) continue
                val i = y * width + x
                if (fgHist[binOf(pixels[i])] == 0f) bgSeeds[i] = true
            }
        }
        if (bgSeeds.any { it }) return
        if (!lowestFrameResemblance(pixels, width, height, fgHist, fgSeeds, bgSeeds)) {
            // The marks cover the whole frame: take the single least subject-like pixel anywhere.
            var best = -1
            var bestScore = Float.MAX_VALUE
            for (i in pixels.indices) {
                if (fgSeeds[i]) continue
                val score = fgHist[binOf(pixels[i])]
                if (score < bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best >= 0) bgSeeds[best] = true
        }
    }

    private fun onFrame(x: Int, y: Int, width: Int, height: Int): Boolean =
        x == 0 || y == 0 || x == width - 1 || y == height - 1

    /** The fraction of the frame's resemblance range (lowest to highest) that still counts as "least like the marks". */
    private const val FALLBACK_RESEMBLANCE_BAND = 0.25f

    /**
     * Seed the frame pixels, not marked as subject, whose colour weight in the foreground
     * histogram is within the lowest [FALLBACK_RESEMBLANCE_BAND] of the frame's own range. A frame
     * of one uniform colour has no range and is seeded whole, which is the honest answer: nothing
     * tells its pixels apart. False when the frame has no unmarked pixel to seed.
     */
    private fun lowestFrameResemblance(
        pixels: IntArray,
        width: Int,
        height: Int,
        fgHist: FloatArray,
        fgSeeds: BooleanArray,
        bgSeeds: BooleanArray,
    ): Boolean {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!onFrame(x, y, width, height)) continue
                val i = y * width + x
                if (fgSeeds[i]) continue
                val weight = fgHist[binOf(pixels[i])]
                if (weight < low) low = weight
                if (weight > high) high = weight
            }
        }
        if (low == Float.MAX_VALUE) return false
        val cutoff = low + (high - low) * FALLBACK_RESEMBLANCE_BAND
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!onFrame(x, y, width, height)) continue
                val i = y * width + x
                if (!fgSeeds[i] && fgHist[binOf(pixels[i])] <= cutoff) bgSeeds[i] = true
            }
        }
        return true
    }

    // ---- geodesic distance -----------------------------------------------------------------

    /** The image with each channel box-blurred 3x3, used only to measure edges, never to cut pixels. */
    private class SmoothedColour(pixels: IntArray, width: Int, height: Int) {
        val r = IntArray(pixels.size)
        val g = IntArray(pixels.size)
        val b = IntArray(pixels.size)

        init {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var c = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= width) continue
                            val p = pixels[ny * width + nx]
                            sr += (p shr 16) and 0xFF
                            sg += (p shr 8) and 0xFF
                            sb += p and 0xFF
                            c++
                        }
                    }
                    val i = y * width + x
                    r[i] = sr / c
                    g[i] = sg / c
                    b[i] = sb / c
                }
            }
        }

        /** Euclidean RGB distance between two pixels, 0..1.73. */
        fun distance(a: Int, c: Int): Float {
            val dr = (r[a] - r[c]).toFloat()
            val dg = (g[a] - g[c]).toFloat()
            val db = (b[a] - b[c]).toFloat()
            return sqrt(dr * dr + dg * dg + db * db) / 255f
        }
    }

    private const val COST_SCALE = 16f
    private const val UNREACHED = Int.MAX_VALUE
    private const val DIAGONAL = 1.4142135f

    /**
     * Multi-source Dijkstra over the 8-connected pixel grid from every marked pixel, with costs in
     * 1/16 of a pixel step so the heap can key on a plain integer.
     */
    private fun distanceField(
        colour: SmoothedColour,
        likelihood: FloatArray,
        seeds: BooleanArray,
        foregroundField: Boolean,
        width: Int,
        height: Int,
        params: Params,
        shouldStop: () -> Boolean,
    ): IntArray {
        val n = width * height
        val dist = IntArray(n) { UNREACHED }
        val heap = LongHeap(max(1024, n / 2))
        for (i in 0 until n) {
            if (seeds[i]) {
                dist[i] = 0
                heap.push(i.toLong())
            }
        }
        var pops = 0
        while (!heap.isEmpty()) {
            if (++pops and 0x0FFF == 0 && shouldStop()) throw java.util.concurrent.CancellationException("segmentation superseded")
            val top = heap.pop()
            val cost = (top ushr 32).toInt()
            val index = (top and 0xFFFFFFFFL).toInt()
            if (cost > dist[index]) continue
            val x = index % width
            val y = index / width
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= width) continue
                    val next = ny * width + nx
                    val spatial = if (dx == 0 || dy == 0) 1f else DIAGONAL
                    val unary = if (foregroundField) 1f - likelihood[next] else likelihood[next]
                    val edge = max(0f, colour.distance(index, next) - params.edgeFloor)
                    val step = spatial * (1f + params.colourWeight * unary) + params.edgeWeight * edge
                    val candidate = cost.toLong() + (step * COST_SCALE).toLong()
                    if (candidate >= UNREACHED) continue
                    val c = candidate.toInt()
                    if (c < dist[next]) {
                        dist[next] = c
                        heap.push((c.toLong() shl 32) or next.toLong())
                    }
                }
            }
        }
        return dist
    }

    /** A binary min-heap of primitive longs: the key is the high word, so no boxing on the hot path. */
    private class LongHeap(initial: Int) {
        private var items = LongArray(initial)
        private var size = 0

        fun isEmpty(): Boolean = size == 0

        /** Seeds are pushed as a bare index (cost 0 in the high word). */
        fun push(value: Long) {
            if (size == items.size) items = items.copyOf(size * 2)
            var i = size++
            while (i > 0) {
                val parent = (i - 1) shr 1
                if (items[parent] <= value) break
                items[i] = items[parent]
                i = parent
            }
            items[i] = value
        }

        fun pop(): Long {
            val result = items[0]
            val last = items[--size]
            if (size > 0) {
                var i = 0
                while (true) {
                    var child = 2 * i + 1
                    if (child >= size) break
                    if (child + 1 < size && items[child + 1] < items[child]) child++
                    if (items[child] >= last) break
                    items[i] = items[child]
                    i = child
                }
                items[i] = last
            }
            return result
        }
    }

    // ---- clean-up --------------------------------------------------------------------------

    /**
     * Keep only the 4-connected pieces of [region] that contain a [seeds] pixel. A solver can
     * label a distant island "closer to the subject" for no reason a person would accept; if they
     * wanted it they would have marked it.
     */
    internal fun keepComponentsTouching(region: BooleanArray, seeds: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(region.size)
        val stack = IntArray(region.size)
        for (start in region.indices) {
            if (!seeds[start] || !region[start] || out[start]) continue
            var top = 0
            stack[top++] = start
            out[start] = true
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                if (x > 0 && region[i - 1] && !out[i - 1]) { out[i - 1] = true; stack[top++] = i - 1 }
                if (x < width - 1 && region[i + 1] && !out[i + 1]) { out[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && region[i - width] && !out[i - width]) { out[i - width] = true; stack[top++] = i - width }
                if (y < height - 1 && region[i + width] && !out[i + width]) { out[i + width] = true; stack[top++] = i + width }
            }
        }
        return out
    }

    /**
     * Fill, in place, every enclosed hole of at most [maxArea] pixels that holds no
     * [protectedPixels] pixel. A highlight on a mug or a button on a coat is subject, and a cut-out
     * with a see-through pinhole looks like a bug; but a hole the person deliberately scribbled
     * background into (the gap in a handle) must stay open.
     */
    internal fun fillSmallHoles(region: BooleanArray, protectedPixels: BooleanArray, width: Int, height: Int, maxArea: Int) {
        if (maxArea <= 0) return
        val n = region.size
        val visited = BooleanArray(n)
        val stack = IntArray(n)
        val members = IntArray(n)
        for (start in 0 until n) {
            if (region[start] || visited[start]) continue
            var top = 0
            var count = 0
            var touchesFrame = false
            var protected = false
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val i = stack[--top]
                members[count++] = i
                val x = i % width
                val y = i / width
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesFrame = true
                if (protectedPixels[i]) protected = true
                if (x > 0 && !region[i - 1] && !visited[i - 1]) { visited[i - 1] = true; stack[top++] = i - 1 }
                if (x < width - 1 && !region[i + 1] && !visited[i + 1]) { visited[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && !region[i - width] && !visited[i - width]) { visited[i - width] = true; stack[top++] = i - width }
                if (y < height - 1 && !region[i + width] && !visited[i + width]) { visited[i + width] = true; stack[top++] = i + width }
            }
            if (!touchesFrame && !protected && count <= maxArea) {
                for (k in 0 until count) region[members[k]] = true
            }
        }
    }

    private val NEVER_STOP: () -> Boolean = { false }
}
