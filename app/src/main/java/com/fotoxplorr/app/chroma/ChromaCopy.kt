package com.fotoxplorr.app.chroma

/**
 * The words the Chroma ID tool owns. One short sentence each (owner feedback #6). The pure code
 * returns these, never an exception's text. Colour NAMES are data, not copy: they live in
 * [ColourTable].
 */
internal object ChromaCopy {
    const val TITLE = "Chroma ID"
    const val BLURB = "Read the true colour of a surface with the flash."

    // Why a reading was refused.
    const val CLIPPED = "Part of the picture is washed out to white; step back or dim the flash."
    const val TOO_DARK = "The picture is too dark to read a colour."
    const val UNEVEN = "The patch is not one colour; aim at a plain area."
    const val TOO_SMALL = "The picture is too small to measure."
    const val SIZE_MISMATCH = "The two pictures must be the same size."
    const val TOO_BRIGHT_ROOM = "Too bright in the room for the flash to count; dim the room or shade it."
    const val TOO_FAR = "Too far for the flash to reach; move closer."
    const val LIGHT_CHANGED = "The light changed between the two shots; hold still and try again."
    const val CARD_TOO_DIM = "The card is too dim in the flash picture; fill the view with it."
    const val DISTANCE_TOO_DIFFERENT = "Hold the card and the surface at about the same distance."
    const val NOT_ADDING_UP = "The numbers do not add up; avoid shiny surfaces and keep the card flat."

    // What a reading admits.
    const val APPROXIMATE = "Approximate: no card, so the flash is assumed to be neutral white."
    const val MEASURED = "Measured against your card."
    const val LIGHTNESS_GUESSED = "Without a card, how light or dark it is is only a guess."
    const val SAME_DISTANCE_ASSUMED = "Assumes the card and the surface were the same distance away."
    const val DISTANCE_COMPENSATED = "Corrected for the card being a different distance away."
    const val CLAMPED = "A little above the card's white; clipped to white."
    const val SMALL_SIGNAL = "A weak flash signal; the colour may be off."

    // Plain words for a colour.
    const val BLACK = "black"
    const val WHITE = "white"
    const val GREY = "grey"
    const val GREYISH = "greyish"

    const val VERY_DARK = "very dark"
    const val DARK = "dark"
    const val MID = "mid"
    const val LIGHT = "light"
    const val VERY_LIGHT = "very light"

    const val MUTED = "muted"
    const val MODERATE = "soft"
    const val STRONG = "strong"
    const val VIVID = "vivid"

    const val HUE_RED = "red"
    const val HUE_ORANGE = "orange"
    const val HUE_YELLOW = "yellow"
    const val HUE_YELLOW_GREEN = "yellow-green"
    const val HUE_GREEN = "green"
    const val HUE_BLUE_GREEN = "blue-green"
    const val HUE_BLUE = "blue"
    const val HUE_PURPLE = "purple"
    const val HUE_ROSE = "rose"

    // Comparing two swatches.
    const val SAME = "Looks the same."
    const val CLOSE = "Very close; hard to tell apart."
    const val NOTICEABLE = "A small, visible difference."
    const val DIFFERENT = "A clear difference."
    const val VERY_DIFFERENT = "A big difference."
    const val TOO_CLOSE_TO_CALL = "Too close to call with an approximate reading."

    const val B_LIGHTER = "The second is lighter."
    const val B_DARKER = "The second is darker."
    const val B_MORE_VIVID = "The second is more vivid."
    const val B_DULLER = "The second is duller."
    const val HUE_DIFFERS = "The shade of colour differs."
}
