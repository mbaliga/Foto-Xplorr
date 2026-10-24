package com.fotoxplorr.app.pano

/**
 * The words the panorama maker owns. One short sentence each (owner feedback #6). The job returns
 * these, never an exception's text, so what a person reads is the same whatever the platform threw.
 *
 * The messages that name a photo count from 1, in the order the person arranged them, because that
 * is how the strip shows them. Nothing in the other panorama files says a word to the person.
 */
internal object PanoCopy {
    const val TITLE = "Panorama"
    const val BLURB = "Join photos taken side to side into one wide picture."

    /** The name of the running job in the activity shade and the notification. */
    const val JOB_TITLE = "Joining a panorama"

    // What the job reports when it works.
    const val SAVED = "Panorama saved."
    const val SAVED_SMALLER = "Saved smaller, to fit the memory."
    const val LENS_GUESSED = "Some photos did not say their lens, so straight lines may look curved."
    const val DRIFTING = "The photos drift up or down, so some of the top and bottom is cut."

    // What the job reports when it cannot.
    const val NEED_PHOTOS = "Pick two to twelve photos."
    const val PROTECTED = "A protected photo cannot be joined."
    const val NO_PAIR = "No two photos matched well enough."
    const val MIXED = "These photos do not share one size and lens."
    const val NO_PICTURE = "The photos leave no clean picture to save."
    const val OUT_OF_MEMORY = "There is not enough memory to join these."
    const val SAVE_FAILED = "The panorama could not be saved."
    const val FAILED = "The panorama could not be made."

    fun cannotRead(photo: Int): String = "Photo $photo could not be read."

    fun cannotJoin(photo: Int): String = "Photo $photo does not match the one before it."
}
