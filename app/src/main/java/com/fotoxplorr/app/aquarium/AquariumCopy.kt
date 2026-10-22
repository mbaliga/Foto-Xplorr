package com.fotoxplorr.app.aquarium

/**
 * The words the aquarium owns. One short sentence each (owner feedback #6). Colour and brush names
 * are here, not in the model, because they are the TalkBack labels of the swatches: a colour is
 * never the only way to tell them apart.
 *
 * The UI unit appends its own strings to this object; the pure model only needs these.
 */
internal object AquariumCopy {
    const val TITLE = "Draw an aquarium"
    const val BLURB = "Draw a fish and watch it swim."

    const val COLOR_RED = "Red"
    const val COLOR_ORANGE = "Orange"
    const val COLOR_YELLOW = "Yellow"
    const val COLOR_GREEN = "Green"
    const val COLOR_BLUE = "Blue"
    const val COLOR_PURPLE = "Purple"
    const val COLOR_PINK = "Pink"
    const val COLOR_BLACK = "Black"

    const val BRUSH_THIN = "Thin"
    const val BRUSH_MEDIUM = "Medium"
    const val BRUSH_THICK = "Thick"
}
