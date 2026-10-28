package com.fotoxplorr.app.palettebuilder

/**
 * The colour-wheel relationships a palette can be grown from a base colour by.
 *
 * The first five rotate the hue in HSL (the wheel designers mean by "complementary"); the last two keep
 * the hue and move lightness in LCh, because HSL lightness is not perceptual (a yellow and a blue of the
 * same HSL lightness are far apart in how light they look) and a "shades" ramp should step evenly.
 */
enum class HarmonyKind(val label: String, internal val hueOffsets: List<Double>) {
    /** The base and the hue opposite it. */
    COMPLEMENTARY(PaletteBuilderCopy.HARMONY_COMPLEMENTARY, listOf(0.0, 180.0)),

    /** The base and the two hues beside its opposite. */
    SPLIT_COMPLEMENTARY(PaletteBuilderCopy.HARMONY_SPLIT, listOf(0.0, 150.0, 210.0)),

    /** Three hues a third of the wheel apart. */
    TRIAD(PaletteBuilderCopy.HARMONY_TRIAD, listOf(0.0, 120.0, 240.0)),

    /** Four hues a quarter of the wheel apart (the square tetrad, not the rectangle). */
    TETRAD(PaletteBuilderCopy.HARMONY_TETRAD, listOf(0.0, 90.0, 180.0, 270.0)),

    /** Hues stepping 30 degrees either side of the base. */
    ANALOGOUS(PaletteBuilderCopy.HARMONY_ANALOGOUS, emptyList()),

    /** One hue and chroma at evenly spread lightness. */
    MONOCHROME(PaletteBuilderCopy.HARMONY_MONOCHROME, emptyList()),

    /** The base, then the same hue and chroma stepping toward black. */
    SHADES(PaletteBuilderCopy.HARMONY_SHADES, emptyList()),
}

/**
 * Harmony generators: a list of colours from one base colour.
 *
 * Every generator returns exactly `count` colours (1..12) with the base itself, unchanged, first. Pure
 * arithmetic on [ColorMath], so the same base gives the same list on every run. A grey base has no hue,
 * so the hue-rotating kinds hand back greys that differ only by the lightness rings below.
 */
object Harmony {

    /** The angle between neighbours of [HarmonyKind.ANALOGOUS], in degrees. */
    const val ANALOGOUS_STEP_DEGREES = 30.0

    /** How far each extra ring of a hue-rotating kind moves HSL lightness, 0..1. */
    private const val RING_LIGHTNESS_STEP = 0.15

    private const val MONOCHROME_DARKEST = 15.0
    private const val MONOCHROME_LIGHTEST = 92.0

    fun generate(base: Int, kind: HarmonyKind, count: Int): List<Int> {
        val n = count.coerceIn(1, Palette.MAX_COLORS)
        val opaque = ColorMath.opaque(base)
        return when (kind) {
            HarmonyKind.ANALOGOUS -> wheel(opaque, n) { i -> analogousOffset(i) to 0 }
            HarmonyKind.MONOCHROME -> monochrome(opaque, n)
            HarmonyKind.SHADES -> shades(opaque, n)
            else -> wheel(opaque, n) { i -> kind.hueOffsets[i % kind.hueOffsets.size] to i / kind.hueOffsets.size }
        }
    }

    /** 0, +30, -30, +60, -60, ... so a count of 3 is the base with one neighbour each side. */
    internal fun analogousOffset(i: Int): Double =
        if (i == 0) 0.0 else if (i % 2 == 1) ANALOGOUS_STEP_DEGREES * ((i + 1) / 2) else -ANALOGOUS_STEP_DEGREES * (i / 2)

    /** Ring 0 keeps the base's lightness; 1 is lighter, 2 darker, 3 lighter still ... */
    private fun ringShift(ring: Int): Double =
        if (ring == 0) 0.0 else if (ring % 2 == 1) RING_LIGHTNESS_STEP * ((ring + 1) / 2) else -RING_LIGHTNESS_STEP * (ring / 2)

    private fun wheel(base: Int, n: Int, slot: (Int) -> Pair<Double, Int>): List<Int> {
        val hsl = ColorMath.hsl(base)
        return List(n) { i ->
            if (i == 0) {
                base
            } else {
                val (offset, ring) = slot(i)
                ColorMath.fromHsl(hsl.h + offset, hsl.s, (hsl.l + ringShift(ring)).coerceIn(0.05, 0.95))
            }
        }
    }

    private fun monochrome(base: Int, n: Int): List<Int> {
        if (n == 1) return listOf(base)
        val lch = ColorMath.lch(base)
        val slots = List(n) { MONOCHROME_DARKEST + (MONOCHROME_LIGHTEST - MONOCHROME_DARKEST) * it / (n - 1) }
        // The base takes the slot nearest its own lightness, so the ramp does not hold two almost equal steps.
        val own = slots.indices.minByOrNull { kotlin.math.abs(slots[it] - lch.l) } ?: 0
        return listOf(base) + slots.filterIndexed { i, _ -> i != own }.map { ColorMath.fromLch(it, lch.c, lch.h) }
    }

    private fun shades(base: Int, n: Int): List<Int> {
        val lch = ColorMath.lch(base)
        return List(n) { i -> if (i == 0) base else ColorMath.fromLch(lch.l * (n - i) / n, lch.c, lch.h) }
    }

    /**
     * [palette] regrown around the swatch at [baseIndex], which keeps its colour. Every other swatch that
     * is not locked takes the colour [generate] gives its position (the n-th other slot gets the n-th
     * generated colour, locked or not, so locking one swatch does not shift the pattern of the rest);
     * locked swatches keep their colours and their lock. An out-of-range [baseIndex] is clamped.
     */
    fun regenerate(palette: Palette, kind: HarmonyKind, baseIndex: Int): Palette {
        val base = baseIndex.coerceIn(0, palette.size - 1)
        val generated = generate(palette.colors[base].argb, kind, palette.size)
        var next = 1
        val colors = palette.colors.mapIndexed { i, color ->
            if (i == base) {
                color
            } else {
                val fresh = generated[next++]
                if (color.locked) color else color.copy(argb = fresh)
            }
        }
        return palette.copy(colors = colors)
    }
}
