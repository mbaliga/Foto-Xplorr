package com.fotoxplorr.app.adaptive

import kotlin.math.exp

/**
 * The physical keys the full-screen viewer gives a meaning to, named independently of Compose's
 * `Key` for the same reason [GalleryShortcutKey] is: the table below stays a plain `when` a JVM
 * test can drive, and the call site in ViewerScreen does the one-line mapping.
 */
enum class ViewerShortcutKey {
    ARROW_LEFT,
    ARROW_RIGHT,
    PLUS,
    MINUS,
    ZERO,
    ESCAPE,
}

/** What a keystroke means to the viewer, decoupled from which physical key produced it. */
sealed interface ViewerShortcut {
    /** Page to the next ([forward] true) or previous photo. */
    data class Page(val forward: Boolean) : ViewerShortcut
    data object ZoomIn : ViewerShortcut
    data object ZoomOut : ViewerShortcut
    data object ResetZoom : ViewerShortcut

    /** Back out of an open room if one is up, otherwise close the viewer; the call site decides. */
    data object Close : ViewerShortcut
}

/**
 * The viewer's keyboard shortcut table: Left/Right page, `+`/`-` zoom, `0` resets zoom, Escape
 * closes. Returns `null` for a key it has no opinion about so that key falls through untouched.
 *
 * Unlike [galleryShortcutFor] there is no modifier parameter: Ctrl+0 and a bare 0 both reset,
 * as in every browser, and nothing here changes meaning with Ctrl.
 */
fun viewerShortcutFor(key: ViewerShortcutKey): ViewerShortcut? = when (key) {
    ViewerShortcutKey.ARROW_LEFT -> ViewerShortcut.Page(forward = false)
    ViewerShortcutKey.ARROW_RIGHT -> ViewerShortcut.Page(forward = true)
    ViewerShortcutKey.PLUS -> ViewerShortcut.ZoomIn
    ViewerShortcutKey.MINUS -> ViewerShortcut.ZoomOut
    ViewerShortcutKey.ZERO -> ViewerShortcut.ResetZoom
    ViewerShortcutKey.ESCAPE -> ViewerShortcut.Close
}

/** Multiplicative zoom step for one press of `+` (its reciprocal is `-`). */
const val VIEWER_KEY_ZOOM_STEP = 1.25f

/** Wheel travel (Android raw `scrollDelta` units) to log-zoom units, as for the grid's wheel zoom. */
const val VIEWER_WHEEL_ZOOM_SENSITIVITY = 0.1f

/** Zoom factor for one Ctrl+wheel tick; scrolling up (negative delta) zooms in. 1 for no travel. */
fun viewerWheelZoomFactor(scrollY: Float): Float = exp(-scrollY * VIEWER_WHEEL_ZOOM_SENSITIVITY)

/** The zoom factor a zoom shortcut asks for, or null for a shortcut that is not a relative zoom. */
fun viewerZoomFactorFor(shortcut: ViewerShortcut): Float? = when (shortcut) {
    ViewerShortcut.ZoomIn -> VIEWER_KEY_ZOOM_STEP
    ViewerShortcut.ZoomOut -> 1f / VIEWER_KEY_ZOOM_STEP
    else -> null
}
