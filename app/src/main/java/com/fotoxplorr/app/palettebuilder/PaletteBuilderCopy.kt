package com.fotoxplorr.app.palettebuilder

import java.util.Locale
import kotlin.math.floor

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
    const val VISION_ALL_CLEAR = "No pair blurs for the three kinds of colour blindness checked."
    const val VISION_BLUR = "Some of these blur together."

    // Export.
    const val FORMAT_GPL = "GIMP palette"
    const val FORMAT_CSS = "CSS colours"
    const val FORMAT_JSON = "JSON"
    const val FORMAT_CARD = "Swatch card"

    /** The line a swatch card carries under its colours when the palette has a name. */
    fun cardTitle(name: String): String = name

    // The entry's card.
    const val TITLE = "Palette builder"
    const val BLURB = "Pull a colour palette out of your photos."

    /** The name of the running job in the activity shade and the notification. */
    const val JOB_TITLE = "Recolouring a photo"

    // Where the person is.
    const val BACK = "Back"
    const val START_HINT = "Start from your photos or from one colour."
    const val FROM_PHOTOS = "From photos"
    const val FROM_COLOUR = "From a colour"
    const val KEEP_EDITING = "Keep editing"
    const val SAVED_TITLE = "Saved palettes"
    const val NO_SAVED = "Nothing saved yet."
    const val SAVED_DAMAGED = "Some saved palettes could not be read."
    const val SAVED_UNREADABLE = "Saved palettes could not be read."
    const val OPEN = "Open"
    const val DELETE = "Delete"
    const val DELETE_YES = "Yes, delete"
    const val DELETE_KEEP = "Keep it"
    const val UNSAVED_LEAVE = "Not saved yet; press Back again to leave."

    fun openSaved(name: String): String = "Open $name"

    fun deleteSaved(name: String): String = "Delete $name"

    fun deleteAsk(name: String): String = "Delete $name for good?"

    fun savedRow(name: String, count: Int): String = "$name, $count colours"

    // Choosing photos.
    const val PICK_HINT = "Pick one photo, or up to six to blend."
    const val NO_PHOTOS = "There are no photos to use here."
    const val MAKE_PALETTE = "Make palette"
    const val READING = "Reading the colours."
    const val CANCEL = "Cancel"
    const val PHOTO_PROTECTED = "A protected photo cannot be used."
    const val PHOTO_UNREADABLE = "A photo could not be read."
    const val TOO_FEW_COLOURS = "These photos have too few different colours."
    const val READ_FAILED = "The colours could not be read."

    fun chosen(count: Int): String = "$count of $MAX_PHOTOS chosen."

    const val MAX_PHOTOS = 6

    // Starting from a colour.
    const val COLOUR_HINT = "Type a colour like #3A7BD5."
    const val COLOUR_LABEL = "Colour"
    const val COLOUR_BAD = "That is not a colour like #RRGGBB."
    const val COLOUR_START = "Start"
    const val COLOUR_DEFAULT = "#3A7BD5"

    // Editing.
    const val NAME_LABEL = "Name"
    const val PICKED = "Picked"
    const val LOCKED = "Locked"
    const val HUE = "Hue"
    const val SATURATION = "Saturation"
    const val LIGHTNESS = "Lightness"
    const val LOCK = "Lock"
    const val UNLOCK = "Unlock"
    const val ADD = "Add a colour"
    const val REMOVE = "Remove"
    const val MOVE_LEFT = "Move left"
    const val MOVE_RIGHT = "Move right"
    const val UNDO = "Undo"
    const val HUE_GREY = "A grey has no hue."
    const val AT_MAX = "A palette holds up to 12 colours."
    const val AT_MIN = "A palette needs at least 2 colours."
    const val HARMONY_HINT = "Rebuild around the picked colour, keeping locked ones."

    fun swatch(position: Int, count: Int, hex: String, locked: Boolean, picked: Boolean): String =
        "Colour $position of $count, $hex" + (if (locked) ", locked" else "") + (if (picked) ", picked" else "")

    fun degrees(value: Int): String = "$value degrees"

    fun percent(value: Int): String = "$value percent"

    fun lower(label: String, by: Int): String = "$label down $by"

    fun raise(label: String, by: Int): String = "$label up $by"

    // Contrast and colour blindness.
    const val CONTRAST_TITLE = "Text on colour"
    const val CONTRAST_PASS = "Reads well"
    const val CONTRAST_NO = "Hard to read"
    const val VISION_TITLE = "Colour-blind check"
    const val CONTRAST_KEY = "A tick means it reads well; a cross means it does not."
    const val AND_MORE = "And more."

    fun contrastSummary(pass: Int, total: Int): String = "$pass of $total pairs read well."

    fun contrastCell(text: Int, background: Int, ratio: Double, level: String): String =
        "Colour $text on colour $background, ${shownRatio(ratio)} to 1, $level"

    /**
     * [ratio] cut (not rounded) to one decimal, so a shown 4.5 is always at least the 4.5 a tick needs: 4.47 reads
     * 4.4, never "4.5, hard to read". Latin digits whatever the phone's language, as the words around them are.
     */
    internal fun shownRatio(ratio: Double): String {
        var tenths = floor(ratio * 10)
        if (tenths / 10.0 > ratio) tenths -= 1
        return String.format(Locale.ROOT, "%.1f", tenths / 10.0)
    }

    fun collision(first: Int, second: Int, type: String): String = "Colours $first and $second look alike to ${type.lowercase()}."

    // Recolouring a photo.
    const val RECOLOUR_OPEN = "Recolour a photo to this palette"
    const val RECOLOUR_HINT = "Pick a photo; a new copy is saved and the original stays."
    const val STRENGTH = "Strength"
    const val KEEP_SHADING = "Keep the light and dark of the photo"
    const val PREVIEW = "Preview"
    const val PREVIEWING = "Making a preview."
    const val PREVIEW_DESCRIPTION = "Preview of the recoloured photo."
    const val SAVE_COPY = "Save a recoloured copy"
    const val COPYING = "Saving the recoloured copy."
    const val COPIED = "Saved a recoloured copy."
    const val COPIED_SMALLER = "Saved a recoloured copy, smaller than the original."
    const val COPY_OPEN = "Open in the gallery"
    const val COPY_NOT_LISTED = "It will show in the gallery soon."
    const val CANCELLED = "Cancelled."
    const val LEAVING_CANCELS = "Leaving this screen stops the copy."
    const val COPY_PROTECTED = "A protected photo cannot be recoloured."
    const val COPY_UNREADABLE = "The photo could not be read."
    const val COPY_OUT_OF_MEMORY = "There is not enough memory for this photo."
    const val COPY_NO_SPACE = "There is not enough space on the phone."
    const val COPY_FAILED = "The copy could not be saved."
    const val NEED_PHOTO = "Pick a photo first."

    const val WEAKER = "Weaker"
    const val STRONGER = "Stronger"

    fun strengthValue(percent: Int): String = "$percent percent"

    fun stripDescription(hexes: List<String>): String = "Palette: " + hexes.joinToString(", ")

    // Saving and sharing.
    const val SAVE = "Save palette"
    const val SAVED = "Saved."
    const val SAVED_REPLACED = "Saved over the palette with this name."
    const val SAVE_FULL = "Delete a saved palette first; 200 is the limit."
    const val SAVE_FAILED = "The palette could not be saved."
    const val SHARE_TITLE = "Share"
    const val SHARE_FAILED = "Nothing could be shared."
    const val SHARE_CHOOSER = "Share palette"
    const val SOURCE_PROTECTED = "A photo these colours came from is protected or missing."

    fun shareAs(label: String): String = "Share as $label"
}
