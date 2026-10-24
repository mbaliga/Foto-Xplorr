package com.fotoxplorr.app.pano

/**
 * The words the panorama maker owns. One short sentence each (owner feedback #6). The job returns
 * these, never an exception's text, so what a person reads is the same whatever the platform threw.
 *
 * The messages that name a photo count from 1, in the order the person arranged them, because that
 * is how the strip shows them. Nothing in the other panorama files says a word to the person.
 */
internal object PanoCopy {
    const val TITLE = "Stitch a panorama"
    const val BLURB = "Join photos taken side to side into one wide picture."

    /** The name of the running job in the activity shade and the notification. */
    const val JOB_TITLE = "Joining a panorama"

    // What the job reports when it works.
    const val SAVED = "Panorama saved."
    const val SAVED_SMALLER = "Saved smaller, to fit the memory."
    const val LENS_GUESSED = "Some photos did not say their lens, so straight lines may look curved."
    const val CROPPED = "Some photos look cropped, so straight lines may look curved."
    const val DRIFTING = "The photos drift up or down, so some of the top and bottom is cut."

    // What the job reports when it cannot.
    const val NEED_PHOTOS = "Pick two to twelve photos."
    const val PROTECTED = "A protected photo cannot be joined."
    const val NO_PAIR = "No two photos matched well enough."
    const val MIXED = "These photos do not share one size and lens."
    const val NO_PICTURE = "The photos leave no clean picture to save."
    const val OUT_OF_MEMORY = "There is not enough memory to join these."
    const val NO_SPACE = "There is not enough space on the phone."
    const val SAVE_FAILED = "The panorama could not be saved."
    const val FAILED = "The panorama could not be made."

    fun cannotRead(photo: Int): String = "Photo $photo could not be read."

    fun cannotJoin(photo: Int): String = "Photo $photo does not match the one before it."

    // The screen. The strip counts photos from 1 too, left to right.
    const val PICK_HINT = "Pick the photos, taken side to side."
    const val ORDER_HINT = "Put them in the order you took them."
    const val NO_PHOTOS = "There are no photos to join here."
    const val BACK = "Back"
    const val NEXT = "Next"
    const val SORT_BY_DATE = "Sort by date"
    const val PREVIEW = "Preview"
    const val PREVIEWING = "Checking the photos."
    const val SAVE = "Save panorama"
    const val SAVING = "Saving the panorama."
    const val CANCEL = "Cancel"
    const val CANCELLED = "Cancelled."
    const val PREVIEW_DESCRIPTION = "Preview of the joined panorama."
    const val OPEN = "Open in the gallery"
    const val NOT_LISTED_YET = "It will show in the gallery soon."
    const val LEAVING_CANCELS = "Leaving this screen stops the save."

    fun chosen(count: Int): String = "$count of ${PanoGeometry.MAX_PHOTOS} chosen."

    fun photoInStrip(position: Int, name: String): String = "Photo $position, $name"

    fun moveLeft(position: Int): String = "Move photo $position left"

    fun moveRight(position: Int): String = "Move photo $position right"

    fun remove(position: Int): String = "Remove photo $position"

    /** What a photo that did not join is called to the person ([cannotJoin] is the job's word for it). */
    fun noOverlap(position: Int): String = "Photo $position doesn't overlap enough."

    /** The size a save is capped at, in whole megapixels. */
    fun upToSize(maxPixels: Long): String = "Saves at up to ${maxPixels / 1_000_000L} megapixels."

    /** The estimated size of the saved picture, to the nearest ten pixels so it does not read as exact. */
    fun aboutSize(width: Int, height: Int): String = "About ${nearestTen(width)} by ${nearestTen(height)} pixels."

    private fun nearestTen(value: Int): Int = ((value + 5) / 10) * 10
}
