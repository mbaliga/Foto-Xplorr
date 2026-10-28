package com.fotoxplorr.app.board

/** A colour the pickers offer, with the name a screen reader says. */
class NamedColour(val name: String, val argb: Int)

/**
 * The fixed colours every picker offers (a colour block, a shape, a text, the background). Opaque, plain
 * and easy to tell apart; the photos' own colours are offered beside them by the palette aid.
 */
object BoardColours {
    val BASICS: List<NamedColour> = listOf(
        NamedColour(BoardCopy.COLOUR_WHITE, 0xFFFFFFFF.toInt()),
        NamedColour(BoardCopy.COLOUR_LIGHT_GREY, 0xFFD9D9D9.toInt()),
        NamedColour(BoardCopy.COLOUR_GREY, 0xFF8C8C8C.toInt()),
        NamedColour(BoardCopy.COLOUR_BLACK, 0xFF000000.toInt()),
        NamedColour(BoardCopy.COLOUR_RED, 0xFFD32F2F.toInt()),
        NamedColour(BoardCopy.COLOUR_ORANGE, 0xFFF57C00.toInt()),
        NamedColour(BoardCopy.COLOUR_YELLOW, 0xFFFBC02D.toInt()),
        NamedColour(BoardCopy.COLOUR_GREEN, 0xFF388E3C.toInt()),
        NamedColour(BoardCopy.COLOUR_TEAL, 0xFF00897B.toInt()),
        NamedColour(BoardCopy.COLOUR_BLUE, 0xFF1976D2.toInt()),
        NamedColour(BoardCopy.COLOUR_PURPLE, 0xFF7B1FA2.toInt()),
        NamedColour(BoardCopy.COLOUR_PINK, 0xFFEC407A.toInt()),
        NamedColour(BoardCopy.COLOUR_BROWN, 0xFF6D4C41.toInt()),
    )

    /** The ink a new text starts in when the person has not chosen one. */
    val DEFAULT_INK: Int = BASICS.first { it.name == BoardCopy.COLOUR_BLACK }.argb
}
