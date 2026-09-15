package com.fotoxplorr.app.lift

import kotlin.math.abs
import kotlin.math.max

/**
 * Cutting a subject out of a photo without asking a network for help.
 *
 * The obvious tool for this is ML Kit's subject segmentation -- and it is deliberately not used.
 * It is backed by Google Play Services and downloads a model on first use, which means a network
 * permission and a network-capable dependency reaching the classpath. The `offline` product
 * flavour has no INTERNET permission at all, and its Gradle gates (see `app/build.gradle.kts`,
 * `verifyOfflineManifest` / `verifyOfflineRuntimeClasspath`) hard-fail the build the moment either
 * one appears — the same two gates ADR-007 already ran the in-app photo editor into. There is no
 * escape hatch and no "only in the connect flavour" carve-out worth the two codepaths it would
 * cost, so segmentation is implemented here instead, entirely in Kotlin, over nothing but an
 * ARGB pixel array.
 *
 * The technique is flood fill / region growing: colour similarity to a seed pixel, not learned
 * subject recognition. Be clear-eyed about what that buys and what it does not (documented
 * per-function below, and in the lift package's honest-limits notes) — it lifts a mug off a solid
 * desk or a red square off a blue field cleanly; it will bleed into a background that shares the
 * subject's colour, and it will not find a subject's edge where colour does not change at all
 * (a white shirt against an overcast white sky). That is the trade this feature makes for shipping
 * inside the offline flavour's constraints rather than not shipping at all.
 *
 * Pure Kotlin, no Android imports: this is the part that must be unit-tested on the JVM against
 * pixels whose correct answer is known by construction, the same discipline [Adjustments] and
 * [com.fotoxplorr.app.editor.AutoFix] already hold their pixel maths to.
 */
object SubjectSegmentation {

    /** Per-channel tolerance below which two colours are still "the same region", 0..255. */
    const val DEFAULT_TOLERANCE = 32

    /** How many pixels of feather to soften the cut edge by. See [feather] for why this is small. */
    const val DEFAULT_FEATHER_RADIUS = 2

    /** A width/height-tagged alpha plane, 0f (fully excluded) .. 1f (fully included) per pixel. */
    data class Mask(val width: Int, val height: Int, val alpha: FloatArray) {
        init {
            require(alpha.size == width * height) {
                "mask alpha size ${alpha.size} does not match ${width}x$height"
            }
        }

        /** True where a pixel is more in the subject than out of it — the pre-feather shape. */
        fun isIncluded(index: Int): Boolean = alpha[index] >= 0.5f
    }

    /** left/top/right/bottom, right and bottom EXCLUSIVE (like [CropRect][com.fotoxplorr.app.editor.CropRect] pixel math elsewhere in this app). Null when nothing was included. */
    data class BoundingBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Grow a region from ([seedX], [seedY]) over colour-similar neighbours, refine it, and return
     * the result as a feathered alpha mask the same size as the source.
     *
     * @param tolerance per-channel (Chebyshev) distance from the SEED colour a neighbour may have
     *   and still join the region. Chebyshev rather than Euclidean distance because it is what
     *   makes the tolerance number mean something a person can reason about directly ("this many
     *   levels off in any one channel"), and because it is one `max()` of three subtractions
     *   rather than a sqrt of a sum of squares, run over every pixel touched by the fill.
     * @param featherRadius see [feather].
     */
    fun grow(
        pixels: IntArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int,
        tolerance: Int = DEFAULT_TOLERANCE,
        featherRadius: Int = DEFAULT_FEATHER_RADIUS,
    ): Mask {
        require(pixels.size == width * height) {
            "pixels size ${pixels.size} does not match ${width}x$height"
        }
        if (width <= 0 || height <= 0 || seedX !in 0 until width || seedY !in 0 until height) {
            return Mask(width.coerceAtLeast(0), height.coerceAtLeast(0), FloatArray(max(0, width * height)))
        }

        val included = floodFill(pixels, width, height, seedX, seedY, tolerance)
        // Open then close: open (erode, dilate) drops single-pixel and thin-protrusion speckle
        // the fill's colour tolerance let through at a boundary pixel or two; close (dilate,
        // erode) fills the single-pixel pinholes that same tolerance leaves behind INSIDE a
        // subject with a bit of internal colour variation (a fold in fabric, a highlight). Both
        // steps use the full 3x3 neighbourhood, not a 4-connected cross, specifically so a sharp
        // rectangular subject keeps its actual corners — a cross-shaped structuring element
        // rounds them off, which [SubjectSegmentationTest] pins down with a hard-edged square.
        // Cleanup is skipped on a frame too small for a 3x3 kernel to mean anything.
        //
        // Open/close reason about features that are SMALL RELATIVE TO THE SUBJECT: speckle to drop,
        // pinholes to fill. On a handful of pixels every feature is one pixel, so close() dutifully
        // absorbs a neighbour that the colour tolerance had deliberately excluded, and the caller's
        // tolerance stops meaning anything at all. Real photographs are never this small; test
        // fixtures and thumbnails are, and silently widening their masks would be a lie about what
        // `tolerance` does.
        val cleaned = if (width < MIN_MORPHOLOGY_EDGE || height < MIN_MORPHOLOGY_EDGE) {
            included
        } else {
            val opened = dilate(erode(included, width, height), width, height)
            erode(dilate(opened, width, height), width, height)
        }
        val closed = cleaned

        return feather(closed, width, height, featherRadius)
    }

    /**
     * Classic 4-connected flood fill: a growing frontier of pixels within [tolerance] of the SEED
     * colour, not of whichever neighbour let them in.
     *
     * That last clause is the one detail that makes this correct rather than merely plausible.
     * Comparing each new pixel to the neighbour that reached it, instead of to the seed, is the
     * textbook flood-fill bug: colour is allowed to drift one small step at a time across a slow
     * gradient (a shadow, a gentle sky) until the region has silently walked across the entire
     * photograph, arbitrarily far from anything that looks like the seed. Anchoring every
     * comparison to the seed is what makes [tolerance] mean what the caller set it to, rather than
     * a per-step budget that compounds.
     */
    private fun floodFill(
        pixels: IntArray,
        width: Int,
        height: Int,
        seedX: Int,
        seedY: Int,
        tolerance: Int,
    ): BooleanArray {
        val included = BooleanArray(width * height)
        val seedIndex = seedY * width + seedX
        val seed = pixels[seedIndex]
        val seedR = (seed shr 16) and 0xFF
        val seedG = (seed shr 8) and 0xFF
        val seedB = seed and 0xFF

        val stack = IntArray(width * height)
        var top = 0
        stack[top++] = seedIndex
        included[seedIndex] = true

        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width

            if (x > 0) top = tryVisit(pixels, included, stack, top, x - 1, y, width, seedR, seedG, seedB, tolerance)
            if (x < width - 1) top = tryVisit(pixels, included, stack, top, x + 1, y, width, seedR, seedG, seedB, tolerance)
            if (y > 0) top = tryVisit(pixels, included, stack, top, x, y - 1, width, seedR, seedG, seedB, tolerance)
            if (y < height - 1) top = tryVisit(pixels, included, stack, top, x, y + 1, width, seedR, seedG, seedB, tolerance)
        }
        return included
    }

    /**
     * Push (x, y) onto the fill stack if it qualifies, returning the new stack top.
     *
     * [stack] is sized `width*height` up front and used as an explicit array-backed stack rather
     * than an `ArrayDeque` or, worse, actual recursion: a subject that fills most of a
     * megapixel-scale sample would recurse hundreds of thousands of frames deep and blow the JVM
     * stack, which is not a hypothetical for a photo of, say, a wall.
     */
    private fun tryVisit(
        pixels: IntArray,
        included: BooleanArray,
        stack: IntArray,
        stackTop: Int,
        x: Int,
        y: Int,
        width: Int,
        seedR: Int,
        seedG: Int,
        seedB: Int,
        tolerance: Int,
    ): Int {
        val index = y * width + x
        if (included[index]) return stackTop
        val pixel = pixels[index]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val distance = max(abs(r - seedR), max(abs(g - seedG), abs(b - seedB)))
        if (distance > tolerance) return stackTop
        included[index] = true
        stack[stackTop] = index
        return stackTop + 1
    }

    /** A pixel survives only if itself and all 8 neighbours are included; out-of-bounds counts as excluded. */
    /**
     * Smallest frame edge on which the 3x3 morphological cleanup is meaningful. Below this a
     * single pixel is a large fraction of the picture — see the note at the call site.
     */
    private const val MIN_MORPHOLOGY_EDGE = 8

    private fun erode(mask: BooleanArray, width: Int, height: Int): BooleanArray =
        morphology(mask, width, height, requireAll = true)

    /** A pixel is included if itself OR any of its 8 neighbours is; out-of-bounds counts as excluded. */
    private fun dilate(mask: BooleanArray, width: Int, height: Int): BooleanArray =
        morphology(mask, width, height, requireAll = false)

    private fun morphology(mask: BooleanArray, width: Int, height: Int, requireAll: Boolean): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var result = requireAll
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        // Out-of-bounds neighbours are treated as INCLUDED for erode and EXCLUDED
                        // for dilate — i.e. the frame edge is never itself evidence about the mask.
                        //
                        // The alternative, counting out-of-bounds as excluded in both, quietly
                        // destroys any subject that touches the edge of the photograph: erode eats
                        // its border row, and the dilate that is supposed to restore it cannot grow
                        // back past the frame either, so a close() removes a strip that never comes
                        // back. Subjects touching the frame are not an edge case in photographs —
                        // they are most photographs.
                        val inBounds = nx in 0 until width && ny in 0 until height
                        val neighbourIncluded =
                            if (inBounds) mask[ny * width + nx] else requireAll
                        result = if (requireAll) result && neighbourIncluded else result || neighbourIncluded
                    }
                }
                out[y * width + x] = result
            }
        }
        return out
    }

    /**
     * Turn a hard boolean region into a soft-edged alpha mask by box-blurring a 0/1 plane by
     * [radius] pixels.
     *
     * A cut-out pasted with a hard 0/1 edge shows exactly one row of fully-opaque background-
     * coloured pixels all the way round the subject — every stair-step in the flood fill's
     * boundary becomes a visible jagged pixel. A few pixels of blur on the alpha channel ALONE
     * (never the colour) turns that into a soft transition the eye reads as an edge rather than
     * as noise, which is the entire ask: "feather the boundary ... so the cut-out does not look
     * jagged". Interior and exterior pixels more than [radius] away from any boundary are
     * untouched (stay exactly 0f or 1f), so this never blurs the SHAPE, only its edge.
     */
    private fun feather(mask: BooleanArray, width: Int, height: Int, radius: Int): Mask {
        val alpha = FloatArray(mask.size) { if (mask[it]) 1f else 0f }
        if (radius > 0 && width > 0 && height > 0) {
            val scratch = FloatArray(alpha.size)
            boxBlurAxis(alpha, scratch, width, height, radius, horizontal = true)
            boxBlurAxis(scratch, alpha, width, height, radius, horizontal = false)
        }
        return Mask(width, height, alpha)
    }

    /**
     * One axis of a separable box blur over a single-channel [FloatArray], written from scratch
     * (rather than reusing [com.fotoxplorr.app.editor.AdjustmentRenderer]'s packed-ARGB blur)
     * because this operates on a bare alpha plane and this package must not import anything from
     * `editor`, or from Android at all — see the file KDoc.
     */
    private fun boxBlurAxis(
        source: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height

        for (o in 0 until outer) {
            var sum = 0f
            var count = 0
            for (i in 0..radius.coerceAtMost(inner - 1)) {
                sum += source[indexOf(o, i, width, horizontal)]
                count++
            }
            for (i in 0 until inner) {
                val index = indexOf(o, i, width, horizontal)
                destination[index] = sum / count

                val leaving = i - radius
                val entering = i + radius + 1
                if (leaving >= 0) {
                    sum -= source[indexOf(o, leaving, width, horizontal)]
                    count--
                }
                if (entering < inner) {
                    sum += source[indexOf(o, entering, width, horizontal)]
                    count++
                }
            }
        }
    }

    private fun indexOf(outer: Int, inner: Int, width: Int, horizontal: Boolean): Int =
        if (horizontal) outer * width + inner else inner * width + outer

    /**
     * The tight bounding box of everywhere [mask] has any non-zero alpha, or null if nothing was
     * included at all (an out-of-range seed, or a tolerance of 0 on a seed pixel with no
     * identical neighbour).
     *
     * Alpha rather than [Mask.isIncluded] deliberately: the feather pass spreads a little alpha
     * past the hard region's edge, and cropping to the hard region would clip that feather off,
     * putting a visible hard edge back exactly where the soft one was supposed to be.
     */
    fun boundingBox(mask: Mask): BoundingBox? {
        var left = mask.width
        var top = mask.height
        var right = -1
        var bottom = -1
        for (y in 0 until mask.height) {
            val row = y * mask.width
            for (x in 0 until mask.width) {
                if (mask.alpha[row + x] > 0f) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return null
        return BoundingBox(left, top, right + 1, bottom + 1)
    }
}
