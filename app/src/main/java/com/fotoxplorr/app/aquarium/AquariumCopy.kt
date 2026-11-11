package com.fotoxplorr.app.aquarium

/**
 * The words the aquarium owns. One short sentence each (owner feedback #6). Colour and brush names
 * are here, not in the model, because they are the TalkBack labels of the swatches: a colour is
 * never the only way to tell them apart.
 *
 * The screens' strings are in the same object so the experiment has one place for its words.
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

    const val SHAPE_ROUND_FISH = "Round fish"
    const val SHAPE_LONG_FISH = "Long fish"
    const val SHAPE_TADPOLE = "Tadpole"

    // The tank.
    const val CLOSE = "Close"
    const val PAUSE = "Pause"
    const val PLAY = "Play"
    const val PAUSED = "Paused."
    const val FEED = "Feed"
    const val CREATURES = "Creatures"
    const val HIDE_CREATURES = "Hide the list"
    const val DRAW = "Draw"
    const val DRAW_ANOTHER = "Draw another"
    const val EMPTY_PROMPT = "Draw your first fish."
    const val TANK_FULL = "The tank is full. Remove a creature first."

    // The drawing board.
    const val DRAW_TITLE = "Draw a creature"
    const val BACK = "Back"
    const val BOARD_HINT = "Draw with a finger, or pick a shape."
    const val UNDO = "Undo"
    const val REDO = "Redo"
    const val CLEAR = "Start over"
    const val CLEAR_KEEP = "Keep drawing"
    const val CLEAR_QUESTION = "Start over?"
    const val SWIM = "Swim"
    const val WORKING = "Making your creature."
    const val HEAD_LEFT = "Head faces left"
    const val COLOUR = "Colour"
    const val BRUSH = "Brush"
    const val SHAPES = "Shapes"
    const val STROKES_FULL = "No room for more strokes."
    const val SAVE_FAILED = "Could not save. Try again."

    /** The tank's TalkBack summary. */
    fun tank(count: Int): String = when (count) {
        0 -> "Aquarium, empty"
        1 -> "Aquarium, 1 creature"
        else -> "Aquarium, $count creatures"
    }

    fun board(strokes: Int): String = when (strokes) {
        0 -> "Drawing board, empty"
        1 -> "Drawing board, 1 stroke"
        else -> "Drawing board, $strokes strokes"
    }

    fun creature(number: Int): String = "Creature $number"
    fun remove(number: Int): String = "Remove creature $number"
}
