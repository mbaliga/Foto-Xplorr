package com.fotoxplorr.app.wall

import kotlin.math.roundToInt

/**
 * Every word the wall mock-up shows. One short sentence each (owner feedback #6); a figure goes in
 * through a function so the sentence stays whole. Names no library, sensor or file format.
 */
internal object WallCopy {
    const val TITLE = "See it on your wall"

    /** The card's blurb. */
    const val BLURB = "Try pictures on a photo of your wall."

    const val PRIVACY = "Nothing is saved or sent unless you ask."
    const val CLOSE = "Close"
    const val BACK = "Back"

    const val STEP_WALL = "Wall"
    const val STEP_SCALE = "Scale"
    const val STEP_PICTURES = "Pictures"
    const val STEP_HANG = "Hang"

    const val PICK_WALL = "Pick a photo of your wall."
    const val NO_PHOTOS = "There are no photos to use yet."
    const val LOADING = "Loading…"
    const val CANT_SHOW = "This picture can't be shown."

    const val SCALE_HEADER = "Tell us how big the wall is."
    const val MARK_FIRST = "Tap one end of something you know the size of."
    const val MARK_SECOND = "Now tap its other end."
    const val MARK_DONE = "Tap again to mark it anew."
    const val REFERENCE_LENGTH = "Its length (cm)"
    const val DISTANCE = "Or: how far you stood from the wall (cm)"
    const val WALL_WIDTH = "Width of the wall in the photo (cm)"
    const val LENS_GUESSED = "No lens details, so a common lens is assumed."
    const val NEED_SCALE = "Set the scale to go on."
    const val NEXT = "Next"
    const val MARKS_SHOWN = "Marked ends"

    const val REF_A4_LONG = "A4 sheet, long side"
    const val REF_A4_SHORT = "A4 sheet, short side"
    const val REF_CARD = "Bank card"
    const val REF_DOOR = "Door, about 203 cm"

    const val PICK_PICTURES = "Pick the pictures to hang."
    const val LAYOUT = "Layout"
    const val HANG_THEM = "Hang them"
    const val ARRANGE_AGAIN = "Arrange again"

    const val KIND_SINGLE = "One"
    const val KIND_POSTER = "Poster"
    const val KIND_PAIR = "Pair"
    const val KIND_TRIO = "Trio"
    const val KIND_GRID = "Gallery wall"

    const val HANG_HINT = "Drag, pinch and twist, or use the buttons."
    const val SIZE = "Size"
    const val STANDARD_SIZES = "Standard sizes"
    const val SMALLER = "Smaller"
    const val BIGGER = "Bigger"
    const val TURN = "Switch portrait and landscape"
    const val TILT_LEFT = "Tilt left"
    const val TILT_RIGHT = "Tilt right"
    const val LEVEL = "Level"
    const val MOVE_LEFT = "Move left"
    const val MOVE_RIGHT = "Move right"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val LESS_MAT = "Less mat"
    const val MORE_MAT = "More mat"
    const val CHANGE_PICTURES = "Change pictures"
    const val REDO_SCALE = "Set scale again"
    const val UNIT_CM = "cm"
    const val UNIT_IN = "in"
    const val CROPPED = "Part of the photo is cut off."

    // Print file and system print.
    const val PRINT_HEADER = "Print it"
    const val SAVE_PRINT = "Save print file"
    const val PRINT = "Print"
    const val CANCEL = "Cancel"
    const val MAKING = "Making your print file…"
    const val PLACE_LEFT_OUT = "The place it was taken is left out."
    const val PRINT_REAL_SIZE = "Prints at real size when the paper is big enough."

    /** The job's name in the activity list, and the fallback for "finished" or "could not finish". */
    const val PRINT_JOB_TITLE = "Making a print file"
    const val SAVED = "Saved in Pictures/Fotoz/Prints."
    const val PRINT_DOC_NAME = "Fotoz print"
    const val PRINT_CLOSED = "This print is no longer available."
    const val PRINT_FAILED = "The print could not be prepared."

    // Why a print file was not made. Each is shown as it stands, so each is a whole sentence.
    const val CANT_EXPORT = "This photo can't be exported right now."
    const val CANT_READ_PHOTO = "This photo can't be read."
    const val CANT_CREATE_FILE = "The new file could not be saved."
    const val NOT_ENOUGH_MEMORY = "There is not enough memory for this size."
    const val LOCATION_NOT_REMOVED = "The place could not be taken out, so nothing was saved."
    const val FILE_NOT_AS_PLANNED = "The file did not come out right, so nothing was saved."

    const val STYLE_THIN_BLACK = "Thin black"
    const val STYLE_WHITE = "White"
    const val STYLE_WOOD = "Wood"
    const val STYLE_NONE = "No frame"

    fun style(style: FrameStyle): String = when (style) {
        FrameStyle.THIN_BLACK -> STYLE_THIN_BLACK
        FrameStyle.WHITE -> STYLE_WHITE
        FrameStyle.WOOD -> STYLE_WOOD
        FrameStyle.NONE -> STYLE_NONE
    }

    fun kind(kind: SetKind): String = when (kind) {
        SetKind.SINGLE -> KIND_SINGLE
        SetKind.POSTER -> KIND_POSTER
        SetKind.PAIR -> KIND_PAIR
        SetKind.TRIO -> KIND_TRIO
        SetKind.GRID -> KIND_GRID
    }

    fun reference(ref: ReferenceObject): String = when (ref) {
        ReferenceObject.A4_LONG -> REF_A4_LONG
        ReferenceObject.A4_SHORT -> REF_A4_SHORT
        ReferenceObject.CARD -> REF_CARD
        ReferenceObject.DOOR -> REF_DOOR
    }

    fun quality(quality: PrintQuality): String = when (quality) {
        PrintQuality.EXCELLENT -> "excellent"
        PrintQuality.GOOD -> "good"
        PrintQuality.FINE -> "fine from a distance"
        PrintQuality.SOFT -> "soft"
    }

    fun wallIs(wallWidthCm: Float): String = "The photo shows about ${FramePresets.number((wallWidthCm / 10).roundToInt() / 10f)} m of wall."

    fun fileWillBe(widthPx: Int, heightPx: Int, dpi: Int): String = "Saves a JPEG, $widthPx x $heightPx px, $dpi dpi."

    fun chosen(count: Int, capacity: Int): String = "$count of $capacity chosen"

    fun picture(number: Int): String = "Picture $number"

    fun thisSize(dpi: Double, quality: PrintQuality): String = "This size: ${dpi.roundToInt()} dpi, ${quality(quality)}"

    fun printsWellUpTo(wCm: Int, hCm: Int, inches: Boolean): String {
        val (w, h) = if (inches) {
            (wCm / PrintMath.CM_PER_INCH).toInt() to (hCm / PrintMath.CM_PER_INCH).toInt()
        } else {
            wCm to hCm
        }
        return "Prints well up to $w x $h ${if (inches) UNIT_IN else UNIT_CM}"
    }

    fun tilt(deg: Float): String = "Tilt ${FramePresets.number(deg)}°"

    fun mat(cm: Float, inches: Boolean): String =
        "Mat ${FramePresets.number(if (inches) cm / PrintMath.CM_PER_INCH.toFloat() else cm)} ${if (inches) UNIT_IN else UNIT_CM}"

    fun canvasDescription(count: Int): String = "Your wall with $count pictures"
}
