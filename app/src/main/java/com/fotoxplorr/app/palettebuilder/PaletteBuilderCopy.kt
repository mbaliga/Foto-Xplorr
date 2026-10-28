package com.fotoxplorr.app.palettebuilder

/**
 * Every word the palette builder shows. One short sentence each (owner feedback #6). Names no library,
 * sensor or file format beyond the file the person asked for.
 */
internal object PaletteBuilderCopy {
    const val DEFAULT_NAME = "My palette"

    // Harmonies.
    const val HARMONY_COMPLEMENTARY = "Opposite"
    const val HARMONY_SPLIT = "Split opposite"
    const val HARMONY_TRIAD = "Three apart"
    const val HARMONY_TETRAD = "Four apart"
    const val HARMONY_ANALOGOUS = "Neighbours"
    const val HARMONY_MONOCHROME = "One hue"
    const val HARMONY_SHADES = "Darker steps"

    // Contrast.
    const val CONTRAST_FAIL = "Hard to read"
    const val CONTRAST_LARGE = "Big text only"
    const val CONTRAST_AA = "Good for text"
    const val CONTRAST_AAA = "Great for text"

    // Colour blindness.
    const val VISION_PROTAN = "Red-weak eyes"
    const val VISION_DEUTAN = "Green-weak eyes"
    const val VISION_TRITAN = "Blue-weak eyes"
    const val VISION_ALL_CLEAR = "These stay apart for every kind of eye."
    const val VISION_BLUR = "Some of these blur together."

    // Export.
    const val FORMAT_GPL = "GIMP palette"
    const val FORMAT_CSS = "CSS colours"
    const val FORMAT_JSON = "JSON"
    const val FORMAT_CARD = "Swatch card"

    /** The line a swatch card carries under its colours when the palette has a name. */
    fun cardTitle(name: String): String = name
}
