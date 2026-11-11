package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.palette.PaletteExtractor
import com.fotoxplorr.app.palette.PaletteSwatch

/** One photo's extracted swatches and how much that photo counts, 0 or less meaning "not at all". */
data class WeightedSwatches(val swatches: List<PaletteSwatch>, val weight: Float = 1f)

/** One photo's pixels (packed ARGB, as `Bitmap.getPixels` gives them) and how much it counts. */
class WeightedPixels(val pixels: IntArray, val weight: Float = 1f)

/**
 * One palette from several photos: each photo's swatches (from [PaletteExtractor], which stays the one
 * place a photo becomes colours) pooled, weighted, and de-duplicated by distance in Lab.
 *
 * Deterministic: no random choice anywhere; ties break on the colour value. Photos arrive here as
 * pixel arrays or swatch lists, never as files, so a protected photo is only ever read by the caller
 * through `env.decode` and `env.mayExport` (TRAPS 33) before it reaches this file.
 */
object PaletteMerge {

    /**
     * Two swatches closer than this in CIE76 Lab are one colour. About the smallest step a person
     * calls "a different colour" at a glance; a rule of thumb, not a measurement of any screen.
     */
    const val DEFAULT_MERGE_DELTA_E = 12.0

    /** How many swatches each photo contributes before pooling, when [extractMerged] does the extraction. */
    const val PER_PHOTO_COLORS = 8

    /**
     * The merged swatches, heaviest first, at most [maxColors], proportions rescaled to add up to 1.
     *
     * A swatch's weight is its proportion in its photo times the photo's weight. Heaviest-first, each swatch
     * joins the nearest group already formed when that group's colour is within [mergeDeltaE], otherwise it
     * starts a group; a group is shown as its heaviest member's exact colour (an averaged colour could be
     * one nobody photographed) and carries the summed weight. Photos with a weight of 0 or less, and
     * swatches with no usable proportion, are ignored. Empty in, or nothing usable in, gives empty out.
     */
    fun merge(inputs: List<WeightedSwatches>, maxColors: Int, mergeDeltaE: Double = DEFAULT_MERGE_DELTA_E): List<PaletteSwatch> {
        if (maxColors <= 0) return emptyList()
        class Entry(val argb: Int, val mass: Double)

        val entries = inputs.flatMap { input ->
            val w = input.weight.toDouble()
            if (!(w > 0.0) || w.isInfinite()) emptyList()
            else input.swatches.mapNotNull { s ->
                val mass = s.proportion.toDouble() * w
                if (mass > 0.0 && mass.isFinite()) Entry(ColorMath.opaque(s.argb), mass) else null
            }
        }.sortedWith(compareByDescending<Entry> { it.mass }.thenBy { it.argb })
        if (entries.isEmpty()) return emptyList()

        class Group(val argb: Int, val lab: Lab, var mass: Double)

        val groups = mutableListOf<Group>()
        for (e in entries) {
            val lab = ColorMath.lab(e.argb)
            var nearest: Group? = null
            var nearestDistance = Double.MAX_VALUE
            for (g in groups) {
                val d = ColorMath.deltaE(lab, g.lab)
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearest = g
                }
            }
            if (nearest != null && nearestDistance <= mergeDeltaE) nearest.mass += e.mass else groups += Group(e.argb, lab, e.mass)
        }

        val kept = groups.sortedWith(compareByDescending<Group> { it.mass }.thenBy { it.argb }).take(maxColors)
        val total = kept.sumOf { it.mass }
        return kept.map { PaletteSwatch(argb = it.argb, proportion = (it.mass / total).toFloat()) }
    }

    /**
     * [merge] over photos given as pixels: each is run through [PaletteExtractor.extract] for
     * [perPhotoColors] swatches first. A photo with nothing visible contributes nothing.
     */
    fun extractMerged(
        photos: List<WeightedPixels>,
        maxColors: Int,
        perPhotoColors: Int = PER_PHOTO_COLORS,
        mergeDeltaE: Double = DEFAULT_MERGE_DELTA_E,
    ): List<PaletteSwatch> = merge(
        photos.map { WeightedSwatches(PaletteExtractor.extract(it.pixels, perPhotoColors.coerceAtLeast(1)), it.weight) },
        maxColors,
        mergeDeltaE,
    )

    /** A [Palette] of the merged swatches, or null when fewer than two distinct colours came out. */
    fun toPalette(name: String, swatches: List<PaletteSwatch>, maxColors: Int = Palette.MAX_COLORS): Palette? =
        Palette.of(name, swatches.take(maxColors.coerceIn(Palette.MIN_COLORS, Palette.MAX_COLORS)).map { it.argb })
}
