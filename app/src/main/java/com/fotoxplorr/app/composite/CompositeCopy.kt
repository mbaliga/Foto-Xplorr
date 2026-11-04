package com.fotoxplorr.app.composite

import com.fotoxplorr.core.formats.composite.StereoMode

/** Every user-facing sentence of composite media (motion photos, Live Photos, bursts, stereo): one short sentence each. */
internal object CompositeCopy {
    const val PLAY_MOTION = "Play the moving part of this photo, without sound."
    const val STOP_MOTION = "Stop the moving part."
    const val PLAY_LIVE = "Play this Live Photo, without sound."
    const val MOTION_FAILED = "The moving part of this photo could not be played."
    const val EXPORT_STILL = "Save the still as a new photo."
    const val EXPORT_VIDEO = "Save the moving part as a new video."
    const val EXPORTED_STILL = "The still was saved as a new photo."
    const val EXPORTED_VIDEO = "The moving part was saved as a new video."
    const val EXPORT_FAILED = "That could not be saved."
    const val EXPORT_REFUSED = "This photo is protected, so nothing was saved."

    const val OPEN_STEREO = "View this 3D photo."
    const val CLOSE_STEREO = "Close the 3D view."
    const val STEREO_LOADING = "Opening both eyes of the 3D photo."
    const val STEREO_FAILED = "The 3D photo could not be opened."
    const val SPATIAL_ONLY = "This is a spatial photo; only one eye can be shown here."

    const val GROUP_BURSTS = "Show each burst as one stack; Shift+Enter opens it."

    /** The tile's right-click menu entries for a burst stack. */
    const val MENU_OPEN_STACK = "Open stack"
    const val MENU_CLOSE_STACK = "Close stack"
    const val PAIR_LIVE = "Show a Live Photo and its movie as one photo."

    fun stackCount(count: Int, expanded: Boolean): String =
        if (expanded) "Close this burst of $count photos." else "Open this burst of $count photos."

    fun modeLabel(mode: StereoMode): String = when (mode) {
        StereoMode.SIDE_BY_SIDE -> "Side by side"
        StereoMode.CROSS_EYE -> "Cross-eye"
        StereoMode.ANAGLYPH -> "Red-cyan"
        StereoMode.WIGGLE -> "Wiggle"
    }

    fun modeDescription(mode: StereoMode): String = when (mode) {
        StereoMode.SIDE_BY_SIDE -> "Show the two eyes side by side for parallel viewing."
        StereoMode.CROSS_EYE -> "Swap the eyes for cross-eyed viewing."
        StereoMode.ANAGLYPH -> "Blend the eyes for red-cyan glasses."
        StereoMode.WIGGLE -> "Flick between the two eyes."
    }
}
