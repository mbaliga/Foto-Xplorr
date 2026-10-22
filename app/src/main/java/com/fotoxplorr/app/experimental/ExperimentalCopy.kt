package com.fotoxplorr.app.experimental

/**
 * The words the Experimental section adds. One short sentence each (owner feedback #6): the
 * per-experiment blurbs stay in `HelpCopy.Hint` where they already lived; what is new here is only
 * what the host itself says.
 */
internal object ExperimentalCopy {
    const val TITLE = "Experimental"
    const val GROUP_MAKE = "Make"
    const val GROUP_EXPLORE = "Explore"
    const val GROUP_SETUP = "Setup"

    /** Said on a card that cannot be opened on this device, so the dimming is never the only cue. */
    const val UNAVAILABLE = "Not available on this device."

    const val PRIVACY_TITLE = "Privacy boundaries"
}
