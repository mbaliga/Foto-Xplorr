package com.fotoxplorr.app.experimental

/**
 * The words the Experimental hub says itself: its title and header, the card titles and badges, the
 * group headings. One short sentence or phrase each (owner feedback #6): the per-experiment blurbs
 * and the privacy sentences stay in `HelpCopy` where they already lived.
 */
internal object ExperimentalCopy {
    const val TITLE = "Experimental"

    /** Under the hub title. */
    const val HEADER = "Optional extras the gallery works without."
    const val GROUP_MAKE = "Make"
    const val GROUP_EXPLORE = "Explore"
    const val GROUP_SETUP = "Setup"

    /** Said on a card that cannot be opened on this device, so the dimming is never the only cue. */
    const val UNAVAILABLE = "Not available on this device."

    const val PRIVACY_TITLE = "Privacy boundaries"

    // Titles of the entries whose screens live in other packages (the Make entries take theirs from
    // their own copy objects).
    const val SIMILARITY_TITLE = "Organise by similarity"
    const val WALL_TITLE = "3D photo wall"
    const val PREVIEW_TITLE = "Gallery preview"
    const val COMPASS_TITLE = "Compass view"
    const val AI_TITLE = "AI and provider keys"

    // Badges: a chip on the card, never the only cue (the title names the card).
    const val BADGE_ON_DEVICE = "On-device"
    const val BADGE_OPENGL = "OpenGL ES"
    const val BADGE_TOUCH_DPAD = "Touch + D-pad"
    const val BADGE_KEYSTORE = "Keystore"
    const val BADGE_INDEXING = "Indexing…"

    /** The compass card's badge once a read has finished: "12 located". */
    fun located(count: Int): String = "$count located"
}
