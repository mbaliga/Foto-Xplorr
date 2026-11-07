package com.fotoxplorr.app.vision

/**
 * A plain colour name for a pixel.
 *
 * @property name the nearest entry of [VisionCopy.COLOUR_TABLE], e.g. "dusty pink".
 * @property seems true when the colour is so close to grey that the name is a guess; show
 *   [label] instead of [name].
 */
internal class ColourName(val name: String, val seems: Boolean) {
    /** What to show or speak: the name, or "Seems <name>" for a washed-out colour. */
    val label: String get() = if (seems) VisionCopy.seems(name) else name
}

/**
 * Names an sRGB colour by the nearest entry (CIE76 distance in Lab) of a table of about 160 plain
 * colour names, a list of our own in the spirit of the CC0 xkcd colour survey (the words are common
 * English colour words and the hex values were written by us, by eye, not copied from any survey,
 * Pantone or RAL list).
 *
 * The names are only as good as that eye and as a nearest-neighbour rule can be: Lab is not
 * perfectly uniform (blues and purples are the weak spot, and a dark blue can name as purple), and
 * people disagree about colour words anyway. A colour with a Lab chroma under [SEEMS_CHROMA] that
 * is not named a grey gets [ColourName.seems], because at that saturation the hue is not reliable.
 * It names what the pixel is, not what a colour-blind person sees; run [Simulation] first for that.
 */
internal object ColourNamer {
    /** Below this Lab chroma a non-neutral name is only a guess. A pale pastel is about 20 to 30. */
    const val SEEMS_CHROMA = 18.0

    /** A table entry within this chroma counts as a neutral (grey, black, white) and is never qualified. */
    private const val NEUTRAL_CHROMA = 8.0

    private class Entry(val name: String, val lab: Lab) {
        val neutral: Boolean get() = lab.chroma < NEUTRAL_CHROMA
    }

    private val entries: List<Entry> by lazy {
        VisionCopy.COLOUR_TABLE.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val (name, hex) = line.split('|')
            Entry(name, ColourSpace.lab(0xFF000000.toInt() or hex.toInt(16)))
        }.toList()
    }

    /** Every name in the table, in table order. */
    val names: List<String> get() = entries.map { it.name }

    fun name(argb: Int): ColourName {
        val lab = ColourSpace.lab(argb)
        var best = entries[0]
        var bestDistance = Double.MAX_VALUE
        for (e in entries) {
            val d = ColourSpace.deltaE(lab, e.lab)
            if (d < bestDistance) {
                bestDistance = d
                best = e
            }
        }
        return ColourName(best.name, seems = !best.neutral && lab.chroma < SEEMS_CHROMA)
    }

    /** Shorthand for `name(argb).label`. */
    fun label(argb: Int): String = name(argb).label
}
