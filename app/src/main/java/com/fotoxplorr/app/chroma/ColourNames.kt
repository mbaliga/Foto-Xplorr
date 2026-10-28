package com.fotoxplorr.app.chroma

/** One entry of the name table, with the distance to the colour that was asked about. */
internal class NamedMatch(
    val name: String,
    val argb: Int,
    val lab: Lab,
    /** CIEDE2000 between the asked-about colour and this named one. */
    val deltaE: Double,
) {
    val hex: String get() = ColourSpace.hex(argb)
    val red: Int get() = (argb shr 16) and 0xFF
    val green: Int get() = (argb shr 8) and 0xFF
    val blue: Int get() = argb and 0xFF
}

/**
 * The nearest names to a colour, by CIEDE2000 in Lab, over [ColourTable] (about 340 names).
 * A nearest-name rule gives a sensible word, not a right one: it cannot know that "mustard" and
 * "olive" are both somebody's "yellowy green".
 */
internal object ColourNames {
    private class Entry(val name: String, val argb: Int, val lab: Lab)

    private val entries: List<Entry> by lazy {
        (ColourTable.CSS.lines() + ColourTable.OWN.lines()).filter { it.isNotBlank() }.map { line ->
            val (name, hex) = line.split('|')
            val argb = checkNotNull(ColourSpace.parseHex(hex)) { "bad hex in the name table: $line" }
            Entry(name, argb, ColourSpace.lab(argb))
        }
    }

    /** How many names the table holds. */
    val size: Int get() = entries.size

    /** Every name, in table order. */
    val names: List<String> get() = entries.map { it.name }

    /** Names that say how light the colour is, not which colour; they cannot be offered for a colour whose lightness was not measured. */
    private val lightnessWords = setOf("dark", "light", "pale", "deep", "soft", "jet", "off")

    /**
     * Names fit for a colour whose lightness was not measured, each with its Lab at the reference luminance:
     * the names that carry a lightness word are left out, and so are the near-neutrals other than "grey"
     * (black, white, silver and the like are one and the same grey once their lightness is taken away).
     */
    private val shadeOnly: List<Pair<Entry, Lab>> by lazy {
        entries
            .filter { it.name.substringBefore(' ') !in lightnessWords }
            .map { it to ColourSpace.atLuminance(it.lab, FlashSolver.UNMEASURED_LUMINANCE) }
            .filter { (e, lab) -> lab.chroma >= ColourWords.NEUTRAL_CHROMA || e.name == NEUTRAL_NAME }
    }

    /**
     * The [count] names nearest to [lab], nearest first. With [ignoreLightness] the colour and each name are
     * taken at one luminance first, so only shade and strength count (for a colour whose lightness was not
     * measured), and names that are only about lightness are not offered. Names tied at a shade (maroon and
     * red are one shade) come in the order of their own distance from [lab].
     */
    fun nearest(lab: Lab, count: Int = 3, ignoreLightness: Boolean = false): List<NamedMatch> {
        if (!ignoreLightness) {
            return entries.map { NamedMatch(it.name, it.argb, it.lab, DeltaE2000.between(lab, it.lab)) }
                .sortedBy { it.deltaE }
                .take(count)
        }
        val shade = ColourSpace.atLuminance(lab, FlashSolver.UNMEASURED_LUMINANCE)
        return shadeOnly
            .map { (e, atReference) -> NamedMatch(e.name, e.argb, e.lab, DeltaE2000.between(shade, atReference)) }
            .sortedWith(compareBy({ Math.round(it.deltaE * 10.0) }, { DeltaE2000.between(lab, it.lab) }))
            .take(count)
    }

    private const val NEUTRAL_NAME = "grey"

    fun nearest(argb: Int, count: Int = 3): List<NamedMatch> = nearest(ColourSpace.lab(argb), count)
}

/**
 * A colour in plain words, a rough cousin of the Munsell system's three dimensions (hue family,
 * value, chroma) read off CIELAB: "light muted yellow-green", "very dark vivid blue", "dark grey".
 * It is a description for people, not a Munsell notation: the real system is defined by physical
 * chips and these cut-offs are ours (judged by eye on the sRGB primaries and a few dozen common
 * colours, not on a panel of viewers).
 */
internal object ColourWords {
    /** Chroma under this reads as a neutral: black, white or a grey. */
    const val NEUTRAL_CHROMA = 6.0

    /** From [NEUTRAL_CHROMA] up to here a colour is mostly grey with a tint, said as "greyish <hue>". */
    private const val TINT_CHROMA = 12.0

    fun lightness(l: Double): String = when {
        l < 15.0 -> ChromaCopy.VERY_DARK
        l < 35.0 -> ChromaCopy.DARK
        l < 65.0 -> ChromaCopy.MID
        l < 85.0 -> ChromaCopy.LIGHT
        else -> ChromaCopy.VERY_LIGHT
    }

    fun strength(c: Double): String = when {
        c < 20.0 -> ChromaCopy.MUTED
        c < 45.0 -> ChromaCopy.MODERATE
        c < 70.0 -> ChromaCopy.STRONG
        else -> ChromaCopy.VIVID
    }

    /** The hue family word for a CIELAB hue angle in degrees. */
    fun hueFamily(h: Double): String {
        val d = ((h % 360.0) + 360.0) % 360.0
        return when {
            d >= 345.0 || d < 15.0 -> ChromaCopy.HUE_ROSE
            d < 50.0 -> ChromaCopy.HUE_RED
            d < 85.0 -> ChromaCopy.HUE_ORANGE
            d < 110.0 -> ChromaCopy.HUE_YELLOW
            d < 130.0 -> ChromaCopy.HUE_YELLOW_GREEN
            d < 175.0 -> ChromaCopy.HUE_GREEN
            d < 215.0 -> ChromaCopy.HUE_BLUE_GREEN
            d < 315.0 -> ChromaCopy.HUE_BLUE
            else -> ChromaCopy.HUE_PURPLE
        }
    }

    fun describe(lab: Lab): String {
        val c = lab.chroma
        if (c < NEUTRAL_CHROMA) {
            return when {
                lab.l < 10.0 -> ChromaCopy.BLACK
                lab.l > 95.0 -> ChromaCopy.WHITE
                else -> "${lightness(lab.l)} ${ChromaCopy.GREY}"
            }
        }
        if (c < TINT_CHROMA) {
            return "${lightness(lab.l)} ${ChromaCopy.GREYISH} ${hueFamily(lab.hueDegrees)}"
        }
        return "${lightness(lab.l)} ${strength(c)} ${hueFamily(lab.hueDegrees)}"
    }

    /** [describe] without the lightness word (and without black or white), for a colour whose lightness was not measured. */
    fun describeWithoutLightness(lab: Lab): String {
        // Strength is judged at the one luminance a no-card reading sits at, so a dark room reading is not worded stronger than its no-card twin.
        val at = ColourSpace.atLuminance(lab, FlashSolver.UNMEASURED_LUMINANCE)
        val c = at.chroma
        return when {
            c < NEUTRAL_CHROMA -> ChromaCopy.GREY
            c < TINT_CHROMA -> "${ChromaCopy.GREYISH} ${hueFamily(at.hueDegrees)}"
            else -> "${strength(c)} ${hueFamily(at.hueDegrees)}"
        }
    }
}

/** Everything the tool shows about one colour. */
internal class ColourReport(
    val argb: Int,
    val lab: Lab,
    val lch: Lch,
    val description: String,
    val nearest: List<NamedMatch>,
) {
    val hex: String get() = ColourSpace.hex(argb)
    val red: Int get() = (argb shr 16) and 0xFF
    val green: Int get() = (argb shr 8) and 0xFF
    val blue: Int get() = argb and 0xFF

    companion object {
        /** [lightnessMeasured] false leaves lightness out of the words and the names (see [ReadingKind.measuresLightness]). */
        fun of(lab: Lab, nameCount: Int = 3, lightnessMeasured: Boolean = true): ColourReport = ColourReport(
            argb = ColourSpace.argbOf(lab),
            lab = lab,
            lch = ColourSpace.lch(lab),
            description = if (lightnessMeasured) ColourWords.describe(lab) else ColourWords.describeWithoutLightness(lab),
            nearest = ColourNames.nearest(lab, nameCount, ignoreLightness = !lightnessMeasured),
        )

        fun of(argb: Int, nameCount: Int = 3, lightnessMeasured: Boolean = true): ColourReport =
            of(ColourSpace.lab(argb), nameCount, lightnessMeasured)
    }
}
