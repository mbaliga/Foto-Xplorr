package com.fotoxplorr.app.palette

/**
 * One colour in an extracted palette: its packed ARGB value and the share of the sampled pixels
 * it represents, 0..1.
 *
 * The proportion is not decoration. It is what turns a bare list of colours into the segmented
 * bar the owner asked for — a bar drawn from five same-width chips would say "these five colours
 * are present", where the actual, useful claim is "this photo is 62% teal, 20% sand and a
 * scattering of everything else", and only the proportion carries that.
 */
data class PaletteSwatch(val argb: Int, val proportion: Float) {
    val red: Int get() = (argb shr 16) and 0xFF
    val green: Int get() = (argb shr 8) and 0xFF
    val blue: Int get() = argb and 0xFF

    /** `#RRGGBB`, upper-case — the value shown under a swatch in the info panel. */
    val hex: String get() = "#%02X%02X%02X".format(red, green, blue)
}

/**
 * Dominant-colour extraction over a plain ARGB pixel array, by median-cut quantisation.
 *
 * Pure Kotlin, no Android import anywhere in this file — the same reasoning as [com.fotoxplorr
 * .app.editor.AutoFix]: the caller hands over `Bitmap.getPixels()` output (a plain `IntArray`),
 * this operates on it as arithmetic, and the arithmetic is therefore something a JVM unit test can
 * pin down exactly rather than something that can only be eyeballed on a device.
 *
 * Median-cut, not k-means: k-means needs an initial guess at where the clusters are and can
 * converge to a different answer depending on that guess (or on iteration order), which would
 * make "50% red, 50% blue" a flaky assertion instead of a guaranteed one. Median-cut has no random
 * seed and no iteration limit to tune — it recursively bisects the RGB cube along its longest axis
 * at the exact population median, so the same pixels always produce the same buckets, and the
 * degenerate cases (one colour, two colours, no colour) fall out of the recursion's own stopping
 * rule rather than needing to be special-cased.
 */
object PaletteExtractor {

    /** Swatches offered when the caller does not ask for a specific count. */
    const val DEFAULT_MAX_COLORS = 5

    /**
     * Pixels at or below this alpha are treated as not part of the image at all, rather than as a
     * colour in their own right.
     *
     * This matters for PNGs and screenshots with real transparency: many decoders leave the RGB
     * channels of a fully transparent pixel at (0,0,0), and without this filter a large
     * transparent margin would quantise as "this image is mostly black", which is a fact about the
     * codec, not about the photograph.
     */
    private const val ALPHA_VISIBLE_THRESHOLD = 16

    /**
     * The top [maxColors] colours in [pixels] (packed ARGB, as `Bitmap.getPixels` returns), each
     * with its share of the pixels actually counted.
     *
     * Returns fewer than [maxColors] swatches whenever the image genuinely has fewer distinct
     * colours to offer — a solid-colour image always returns exactly one swatch at proportion
     * 1.0, never [maxColors] near-identical ones invented to fill the count. Returns an empty list
     * for an empty or fully-transparent [pixels], rather than dividing by a pixel count of zero.
     */
    fun extract(pixels: IntArray, maxColors: Int = DEFAULT_MAX_COLORS): List<PaletteSwatch> {
        require(maxColors > 0) { "maxColors must be positive, was $maxColors" }
        if (pixels.isEmpty()) return emptyList()

        val visible = pixels.filter { alphaOf(it) >= ALPHA_VISIBLE_THRESHOLD }
        if (visible.isEmpty()) return emptyList()

        val buckets = mutableListOf(Bucket(visible))
        while (buckets.size < maxColors) {
            // Split the LARGEST bucket by population, not the one with the widest colour range —
            // population is what "dominant" means. A single stray blown-white pixel would have an
            // enormous range but a population of one, and splitting on range first would waste a
            // slot on it before the buckets that actually describe most of the photograph exist.
            val splitIndex = buckets.indices
                .filter { buckets[it].size >= 2 && buckets[it].widestChannelRange() > 0 }
                .maxByOrNull { buckets[it].size }
                ?: break // Every remaining bucket is a single colour (or a single pixel) — nothing left worth dividing.

            val bucket = buckets.removeAt(splitIndex)
            val axis = bucket.widestChannel()
            val sorted = bucket.colors.sortedBy { channelValue(it, axis) }
            // Split at the population median, snapped to the nearest point where the channel
            // value actually changes. A blind cut at size/2 would, for an unequal mix (say 300
            // green pixels and 200 blue), land INSIDE the larger group instead of on the seam
            // between the two colours — the true median of 500 items is index 250, but the
            // green/blue boundary sits at 200, so a plain midpoint split hands one bucket 200 pure
            // blue plus 50 stolen green pixels. Snapping to the boundary nearest the midpoint keeps
            // every bucket a single true colour whenever the data allows it, which is what makes
            // "50% red, 50% blue" — and the three- and five-colour cases — come out exact rather
            // than blurred at the edges.
            val cut = nearestValueBoundary(sorted, axis, target = sorted.size / 2)
            buckets += Bucket(sorted.subList(0, cut))
            buckets += Bucket(sorted.subList(cut, sorted.size))
        }

        val total = visible.size.toFloat()
        return buckets
            .map { PaletteSwatch(argb = it.averageColor(), proportion = it.size / total) }
            .sortedByDescending { it.proportion }
    }

    private fun alphaOf(color: Int): Int = (color shr 24) and 0xFF
    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> (color shr 16) and 0xFF
        1 -> (color shr 8) and 0xFF
        else -> color and 0xFF
    }

    /**
     * The index in [sorted] (already ascending by [axis]) closest to [target] at which the
     * channel value actually changes from the previous element.
     *
     * Guaranteed to find one: the caller only reaches here when [Bucket.widestChannelRange] on
     * this exact axis was found to be greater than zero, which by definition means [sorted]'s
     * first and last elements differ on [axis] — so at least one boundary exists between them.
     */
    private fun nearestValueBoundary(sorted: List<Int>, axis: Int, target: Int): Int {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in 1 until sorted.size) {
            if (channelValue(sorted[i - 1], axis) != channelValue(sorted[i], axis)) {
                val distance = kotlin.math.abs(i - target)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
        }
        // The fallback never actually fires given the guarantee above; it exists so this function
        // has a total, crash-free result rather than relying on that guarantee never changing.
        return if (best == -1) target.coerceIn(1, sorted.size - 1) else best
    }

    /** A group of pixels not yet split, or a finished swatch once the recursion is done with it. */
    private class Bucket(val colors: List<Int>) {
        val size: Int get() = colors.size

        fun channelRange(channel: Int): Int {
            var min = 255
            var max = 0
            for (color in colors) {
                val v = channelValue(color, channel)
                if (v < min) min = v
                if (v > max) max = v
            }
            return max - min
        }

        fun widestChannelRange(): Int = (0..2).maxOf { channelRange(it) }

        fun widestChannel(): Int {
            val ranges = IntArray(3) { channelRange(it) }
            // maxByOrNull keeps the FIRST index on a tie (R before G before B), so a perfectly
            // symmetric split (e.g. pure red vs. pure blue, both range 255 with G untouched) is
            // still deterministic rather than depending on iteration order.
            return ranges.indices.maxByOrNull { ranges[it] } ?: 0
        }

        /** Mean of every channel across the bucket — the one representative colour it collapses to. */
        fun averageColor(): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            for (color in colors) {
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
            }
            val n = colors.size
            return (0xFF shl 24) or
                ((r / n).toInt() shl 16) or
                ((g / n).toInt() shl 8) or
                (b / n).toInt()
        }
    }
}
