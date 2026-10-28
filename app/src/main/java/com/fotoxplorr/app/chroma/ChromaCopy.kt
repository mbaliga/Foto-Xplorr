package com.fotoxplorr.app.chroma

/**
 * The words the Chroma ID tool owns. One short sentence each (owner feedback #6). The pure code
 * returns these, never an exception's text. Colour NAMES are data, not copy: they live in
 * [ColourTable].
 */
internal object ChromaCopy {
    const val TITLE = "Chroma ID"
    const val BLURB = "Read the colour of a surface with the flash."

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
    const val APPROXIMATE = "No card, so the flash is assumed to be neutral white."
    const val LIGHTNESS_NOT_MEASURED = "How light or dark it is was not measured."
    const val SAME_DISTANCE_ASSUMED = "Assumes the card and the surface were the same distance away."
    const val DISTANCE_COMPENSATED = "Corrected for the card being a different distance away."
    const val CLAMPED = "A little above the card's white; clipped to white."
    const val SMALL_SIGNAL = "A weak flash signal; the colour may be off."
    const val TONE_CURVE_UNKNOWN = "This camera's own contrast is in the picture; the colour may be off."

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
    const val TOO_CLOSE_TO_CALL = "Too close to call; readings are only so exact."

    const val B_LIGHTER = "The second is lighter."
    const val B_DARKER = "The second is darker."
    const val B_MORE_VIVID = "The second is more vivid."
    const val B_DULLER = "The second is duller."
    const val HUE_DIFFERS = "The shade of colour differs."

    // The camera, the permission and what is kept.
    const val PRIVACY = "Needs the camera to read a colour; the pictures stay in memory and are never saved or sent."
    const val ALLOW_CAMERA = "Allow the camera"
    const val ASK_AGAIN = "Ask again"
    const val OPEN_SETTINGS = "Settings"
    const val CAMERA_DENIED = "The camera is off for Fotoz; allow it in Settings to use this tool."

    // Why the camera could not do it.
    const val NO_CAMERA = "This phone has no camera this tool can use."
    const val CAMERA_BUSY = "The camera is in use by another app."
    const val CAMERA_LOST = "The camera stopped; try again."
    const val CAMERA_FAILED = "The camera could not take the pictures; try again."
    const val NO_LOCK = "This camera cannot hold its exposure and colours still, so it cannot read a colour."
    const val LOCK_SLIPPED = "The camera changed its exposure between the shots; try again."
    const val MOVED = "You moved between the shots; hold still and try again."
    const val TIMED_OUT = "The camera took too long; try again."

    // Flash.
    const val NO_FLASH = "This phone has no flash, so the surface's own colour cannot be found."
    const val AMBIENT_ONLY = "Read without flash"
    const val AMBIENT_NOTE = "No flash: this is the colour under the room's light, not the surface's own colour."

    // The steps.
    const val USE_CARD = "Use a white card"
    const val CARD_STEP = "Fill the view with the card, then measure it."
    const val SURFACE_STEP = "Card measured; now aim at the surface and measure it."
    const val NO_CARD_STEP = "Aim at a plain, matt surface and measure it."
    const val MEASURE = "Measure"
    const val MEASURE_CARD = "Measure the card"
    const val MEASURE_SURFACE = "Measure the surface"
    const val RETAKE_CARD = "Measure the card again"
    const val AGAIN = "Measure again"
    const val CANCEL = "Cancel"
    const val BACK = "Back"
    const val OPENING = "Opening the camera."
    const val SETTING_LIGHT = "Setting the light; hold still."
    const val SHOOTING = "Measuring; hold still."
    const val CARD_KEPT = "Card measured."
    const val CANCELLED = "Cancelled."
    const val STOPPED_NOTE = "Your card measurement is dropped when you leave or turn the phone."
    const val PREVIEW_DESCRIPTION = "Camera view. Aim the middle of it at the surface."

    // Live guidance.
    const val STEADY = "Steady."
    const val HOLD_STILL = "Hold still."
    const val GOOD_DISTANCE = "Good distance."
    const val MOVE_CLOSER = "Move a little closer."
    const val MOVE_FARTHER = "Move a little farther."
    const val WASHED_OUT_LIVE = "Part of the view is washed out; step back or shade it."

    fun holdAbout(cm: Int): String = "Hold about $cm cm away, keep still."
    fun distanceNow(cm: Int): String = "About $cm cm now."

    // The three steps.
    fun step(number: Int): String = "Step $number of 3"
    const val STEP_CARD = "Use plain white paper beside the surface; a grey or coloured card gives a wrong colour. Optional."
    const val STEP_RESULT = "Here is the colour."

    // The result.
    const val RESULT_HEADING = "Colour"
    const val NEAREST = "Nearest names"
    fun nearest(name: String, deltaE: Double): String = "%s (difference %.1f)".format(name, deltaE)
    const val ACCURACY_APPROXIMATE = "Approximate: good for the shade of colour, not for how light it is."
    const val ACCURACY_CALIBRATED = "Corrected with your card; off by about 3 to 6 at best."
    fun rgbLine(r: Int, g: Int, b: Int): String = "RGB $r $g $b"
    fun labLine(l: Double, a: Double, b: Double): String = "Lab %.1f %.1f %.1f".format(l, a, b)
    fun lchLine(l: Double, c: Double, h: Double): String = "LCh %.1f %.1f %.0f\u00B0".format(l, c, h)

    // The same lines where how light it is was not measured: no lightness number is shown.
    fun labLineNoLightness(a: Double, b: Double): String = "Lab a %.1f b %.1f".format(a, b)
    fun hueLine(h: Double): String = "Hue %.0f\u00B0".format(h)
    const val COPY_HEX = "Copy hex"
    fun copied(hex: String): String = "Copied $hex."
    const val COMPARE = "Compare with another reading"
    const val SAVE_TO_PALETTE = "Save to a palette"
    const val HISTORY = "Past readings"

    // Comparing, in the tool's own screen.
    const val COMPARE_TITLE = "Compare two readings"
    const val COMPARE_THIS = "This one"
    const val COMPARE_OTHER = "The other"
    const val COMPARE_PICK = "Pick a reading to compare with."
    const val COMPARE_NOTHING = "Measure another colour first; there is nothing to compare with yet."
    const val COMPARE_PICK_OTHER = "Pick a different reading"
    fun differenceLine(deltaE: Double): String = "Difference %.1f".format(deltaE)
    const val COMPARE_NOTE = "Without a card, how light it is is not compared, and a small difference may not be real."

    // The palette list.
    const val SAVE_PICK = "Pick a palette to add this colour to."
    const val SAVE_NONE = "No saved palettes yet."
    fun addTo(name: String, size: Int): String = "Add to $name ($size colours)"
    fun paletteFull(name: String): String = "$name is full."
    fun paletteHasIt(name: String): String = "$name already has this colour."
    fun newPaletteWith(name: String): String = "New palette $name with this and the last reading"
    const val NEW_NEEDS_TWO = "A new palette needs two colours; measure another first."
    fun addedTo(name: String): String = "Added to $name."
    fun createdPalette(name: String): String = "Saved as $name."
    const val PALETTE_LIST_FULL = "The palette list is full."
    const val PALETTE_FAILED = "The palette could not be saved."

    // The history.
    const val HISTORY_KEPT = "Colours and times stay on this phone; no picture is kept."
    const val HISTORY_EMPTY = "No readings yet."
    const val HISTORY_DAMAGED = "Some lines of the history could not be read and were left out."
    const val HISTORY_UNREADABLE = "The history could not be read; delete it to start again."
    const val HISTORY_NOT_ADDED = "This reading could not be added to the history."
    const val DELETE_ALL = "Delete all readings"
    const val DELETE_SURE = "Delete every past reading?"
    const val DELETE_YES = "Yes, delete them"
    const val DELETE_NO = "Keep them"
    const val DELETED = "All readings deleted."
    const val DELETE_FAILED = "The readings could not be deleted."
    const val MEASURE_NEW = "Back to measuring"
    const val KIND_APPROXIMATE = "Approximate"
    const val KIND_CALIBRATED = "With card"
    const val KIND_ROOM = "Room light"
    const val COMPARE_SHORT = "Compare"
    const val SAVE_SHORT = "Save"
    const val COMPARE_WITH_THIS = "Compare with this one"
    fun copyHexOf(hex: String): String = "Copy $hex"
    fun compareHex(hex: String): String = "Compare $hex with another reading"
    fun compareWithHex(hex: String): String = "Compare with $hex"
    fun saveHex(hex: String): String = "Save $hex to a palette"
}
