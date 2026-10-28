package com.fotoxplorr.app.viewer

/**
 * Which full-screen mode, if any, owns the viewer right now. ONE value rather than a flag per
 * mode, because the modes are mutually exclusive by nature: lifting a subject and selecting text
 * both take every one-finger touch on the photo, both bring toolbars of their own, and both need
 * the chrome, the rooms, the slideshow and the zoom minimap out of the way. Two booleans would
 * allow the state nobody can use (both on) and would need the same "and not the other" written at
 * every call site; a sealed value makes it unrepresentable and gives each predicate one question
 * to ask ([ownsScreen]).
 *
 * Opening one mode clears the other by replacing it ([open]); leaving is [Browsing]. The state is
 * kept per photo (`remember(asset.id)`), so paging to another photo can never carry a half-made
 * selection onto it.
 */
internal sealed interface ViewerMode {
    /** Nothing owns the screen: the photo, its gestures and its chrome as usual. */
    data object Browsing : ViewerMode

    /** The lift-a-subject editor (long press, or the actions room's "Lift subject"). */
    data object Lifting : ViewerMode

    /** Selecting text off the photo: block outlines, tap, stroke and loop, a toolbar of its own. */
    data object SelectingText : ViewerMode

    /** Whether some mode owns the screen, which is what hides the chrome and withdraws the rooms. */
    val ownsScreen: Boolean get() = this !== Browsing

    val isLifting: Boolean get() = this === Lifting

    val isSelectingText: Boolean get() = this === SelectingText

    companion object {
        /**
         * The mode after [wanted] is opened from [current]: [wanted], whatever was open before.
         * Asking for the mode already open changes nothing, and [Browsing] is how a mode is left.
         */
        fun open(current: ViewerMode, wanted: ViewerMode): ViewerMode = if (current == wanted) current else wanted

        /** The mode after Back or Done: leaving [current], which only ever lands on [Browsing]. */
        fun leave(current: ViewerMode): ViewerMode = if (current.ownsScreen) Browsing else current
    }
}
