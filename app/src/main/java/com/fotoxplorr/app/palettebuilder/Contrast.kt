package com.fotoxplorr.app.palettebuilder

/** How readable text of one colour is on another, by the WCAG 2.x contrast thresholds. */
enum class ContrastLevel(val label: String, val minRatio: Double) {
    FAIL(PaletteBuilderCopy.CONTRAST_FAIL, 0.0),

    /** At least 3:1 -- WCAG AA for large text (18 pt, or 14 pt bold) and for graphics. */
    AA_LARGE(PaletteBuilderCopy.CONTRAST_LARGE, 3.0),

    /** At least 4.5:1 -- WCAG AA for normal text. */
    AA(PaletteBuilderCopy.CONTRAST_AA, 4.5),

    /** At least 7:1 -- WCAG AAA for normal text. */
    AAA(PaletteBuilderCopy.CONTRAST_AAA, 7.0),
}

/**
 * WCAG 2.x relative luminance and contrast ratio, and a text-on-swatch matrix over a palette.
 *
 * Alpha is ignored (palette colours are opaque). The sRGB decode uses the corrected 0.04045 knee; the
 * WCAG text prints 0.03928, which differs by under 0.0001 in luminance and never moves a ratio across a
 * threshold the tests pin. Contrast says nothing about colour blindness: see [ColorBlindness].
 */
object Contrast {

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** 0 (black) .. 1 (white), the WCAG relative luminance of [argb]. */
    fun luminance(argb: Int): Double =
        0.2126 * ColorMath.channelToLinear(ColorMath.red(argb)) +
            0.7152 * ColorMath.channelToLinear(ColorMath.green(argb)) +
            0.0722 * ColorMath.channelToLinear(ColorMath.blue(argb))

    /** 1 (identical) .. 21 (black on white), symmetric in its arguments. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun level(ratio: Double): ContrastLevel =
        ContrastLevel.entries.last { ratio >= it.minRatio }

    /** Black or white, whichever reads better on [background] (white on a tie). */
    fun plainTextOn(background: Int): Int =
        if (ratio(BLACK, background) > ratio(WHITE, background)) BLACK else WHITE
}

/**
 * Every colour of a palette as text on every colour of the same palette: `ratio(text, background)`.
 *
 * Square, symmetric (contrast does not care which is text), diagonal 1:1. `bestText` answers the question
 * the matrix exists for: which swatch, or plain black or white, can carry words on this swatch.
 */
class ContrastMatrix(val colors: List<Int>) {
    private val ratios: Array<DoubleArray> = Array(colors.size) { t -> DoubleArray(colors.size) { b -> Contrast.ratio(colors[t], colors[b]) } }

    constructor(palette: Palette) : this(palette.argbs)

    val size: Int get() = colors.size

    fun ratio(text: Int, background: Int): Double = ratios[text][background]

    fun level(text: Int, background: Int): ContrastLevel = Contrast.level(ratios[text][background])

    /** True when text swatch [text] on swatch [background] reaches [atLeast] (AA by default). */
    fun passes(text: Int, background: Int, atLeast: ContrastLevel = ContrastLevel.AA): Boolean =
        level(text, background).ordinal >= atLeast.ordinal

    /** How many ordered (text, background) pairs of different swatches reach [atLeast]. */
    fun passCount(atLeast: ContrastLevel = ContrastLevel.AA): Int =
        (0 until size).sumOf { t -> (0 until size).count { b -> t != b && passes(t, b, atLeast) } }

    /**
     * The index of the swatch that reads best as text on swatch [background], or null when no OTHER swatch
     * reaches [atLeast]; then [Contrast.plainTextOn] is the fallback.
     */
    fun bestText(background: Int, atLeast: ContrastLevel = ContrastLevel.AA): Int? =
        (0 until size).filter { it != background && passes(it, background, atLeast) }
            .maxByOrNull { ratios[it][background] }
}
