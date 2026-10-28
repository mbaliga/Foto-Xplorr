package com.fotoxplorr.app.pano

import java.util.concurrent.CancellationException

/**
 * Where can a stitched panorama be cut so that no empty pixel shows? Stitching leaves ragged,
 * stair-stepped borders (vertical jitter between frames, a tilted frame, the curved edge of the
 * cylinder warp), and the useful picture is the largest axis-aligned rectangle that lies wholly
 * inside the valid pixels. Pure Kotlin over a boolean mask, cancellable like the other pano code.
 *
 * ## The idea
 * The exact answer comes from the "histogram stack": walk the rows top to bottom keeping, per
 * column, how many valid pixels run unbroken above; each row is then a bar chart, and the largest
 * rectangle under a bar chart falls out of one pass with a stack of rising bars. That is
 * O(width x height) and exact ([largestRect]), but at 24 MP it is a lot of work for a result the
 * eye cannot tell from one that is a few pixels tighter.
 *
 * [cropRect] therefore asks the question at 1/[DEFAULT_SCALE] size first: a coarse cell counts as
 * valid only if EVERY pixel under it is, so the coarse rectangle is already inside the valid
 * region at full size. It is then scaled up and each side is pushed outwards a pixel at a time
 * while the strip it would take in is still entirely valid, which recovers the sub-cell margin
 * lost to the coarse grid. Finally the whole rectangle is checked pixel by pixel at full size;
 * if that ever fails, or the coarse mask is empty because the valid area is thinner than a cell,
 * the exact pass runs instead. The result therefore never contains a hole, and is at most a
 * little smaller than the true largest rectangle (the tests measure how much).
 *
 * All sizes and areas that can pass 2^31 are Long; a mask that cannot be indexed with an Int is
 * refused rather than wrapped.
 */
object AutoCrop {

    /** The coarse-grid factor the design asks for. */
    const val DEFAULT_SCALE = 8

    /** A rectangle in pixels, [right] and [bottom] exclusive. */
    data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val area: Long get() = width.toLong() * height
    }

    /**
     * The largest rectangle of true pixels in [mask] (row-major, [width] x [height]), exactly, or
     * null when no pixel is valid. Ties go to the one found first scanning top to bottom, left to
     * right by its bottom edge.
     *
     * @throws java.util.concurrent.CancellationException when [shouldStop] turns true (checked once per row).
     */
    fun largestRect(mask: BooleanArray, width: Int, height: Int, shouldStop: () -> Boolean = { false }): CropRect? {
        checkMask(mask, width, height)
        val heights = IntArray(width)
        val stackX = IntArray(width + 1)
        val stackH = IntArray(width + 1)
        var best = 0L
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        var i = 0
        for (y in 0 until height) {
            if (shouldStop()) throw CancellationException("auto-crop cancelled")
            for (x in 0 until width) {
                heights[x] = if (mask[i++]) heights[x] + 1 else 0
            }
            var depth = 0
            for (x in 0..width) {
                val h = if (x == width) 0 else heights[x]
                var start = x
                while (depth > 0 && stackH[depth - 1] >= h) {
                    depth--
                    val barHeight = stackH[depth]
                    val barStart = stackX[depth]
                    val area = barHeight.toLong() * (x - barStart)
                    if (area > best) {
                        best = area
                        left = barStart
                        right = x
                        bottom = y + 1
                        top = y + 1 - barHeight
                    }
                    start = barStart
                }
                if (h > 0) {
                    stackX[depth] = start
                    stackH[depth] = h
                    depth++
                }
            }
        }
        return if (best == 0L) null else CropRect(left, top, right, bottom)
    }

    /**
     * The crop to apply to a panorama whose valid pixels are [mask]: close to [largestRect] but
     * found on a grid [scale] times coarser and then tightened at full size (see the class note).
     * Null when no pixel is valid. Every pixel inside the returned rectangle is valid.
     *
     * @throws java.util.concurrent.CancellationException when [shouldStop] turns true.
     */
    fun cropRect(
        mask: BooleanArray,
        width: Int,
        height: Int,
        scale: Int = DEFAULT_SCALE,
        shouldStop: () -> Boolean = { false },
    ): CropRect? {
        checkMask(mask, width, height)
        require(scale >= 1) { "scale must be at least 1" }
        if (scale == 1) return largestRect(mask, width, height, shouldStop)

        val coarseW = (width + scale - 1) / scale
        val coarseH = (height + scale - 1) / scale
        val coarse = BooleanArray(coarseW * coarseH) { true }
        var i = 0
        for (y in 0 until height) {
            if (shouldStop()) throw CancellationException("auto-crop cancelled")
            val rowBase = (y / scale) * coarseW
            for (x in 0 until width) {
                if (!mask[i++]) coarse[rowBase + x / scale] = false
            }
        }
        val cell = largestRect(coarse, coarseW, coarseH, shouldStop) ?: return largestRect(mask, width, height, shouldStop)

        var left = cell.left * scale
        var top = cell.top * scale
        var right = minOf(width.toLong(), cell.right.toLong() * scale).toInt()
        var bottom = minOf(height.toLong(), cell.bottom.toLong() * scale).toInt()
        do {
            if (shouldStop()) throw CancellationException("auto-crop cancelled")
            var grew = false
            while (left > 0 && columnValid(mask, width, left - 1, top, bottom)) { left--; grew = true }
            while (right < width && columnValid(mask, width, right, top, bottom)) { right++; grew = true }
            while (top > 0 && rowValid(mask, width, top - 1, left, right)) { top--; grew = true }
            while (bottom < height && rowValid(mask, width, bottom, left, right)) { bottom++; grew = true }
        } while (grew)

        val rect = CropRect(left, top, right, bottom)
        return if (allValid(mask, width, rect)) rect else largestRect(mask, width, height, shouldStop)
    }

    /** True when every pixel of [rect] is valid: the guarantee callers rely on, checked rather than assumed. */
    fun allValid(mask: BooleanArray, width: Int, rect: CropRect): Boolean {
        for (y in rect.top until rect.bottom) if (!rowValid(mask, width, y, rect.left, rect.right)) return false
        return true
    }

    private fun rowValid(mask: BooleanArray, width: Int, y: Int, from: Int, end: Int): Boolean {
        val base = y * width
        for (x in from until end) if (!mask[base + x]) return false
        return true
    }

    private fun columnValid(mask: BooleanArray, width: Int, x: Int, from: Int, end: Int): Boolean {
        for (y in from until end) if (!mask[y * width + x]) return false
        return true
    }

    private fun checkMask(mask: BooleanArray, width: Int, height: Int) {
        require(width > 0 && height > 0) { "a mask must not be empty" }
        require(mask.size.toLong() == width.toLong() * height) { "mask must hold ${width}x$height flags" }
    }
}
