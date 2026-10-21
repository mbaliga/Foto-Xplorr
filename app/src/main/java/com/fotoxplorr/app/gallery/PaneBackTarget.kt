package com.fotoxplorr.app.gallery

/**
 * What the system Back gesture undoes next while browsing.
 *
 * Pulled out of the composable so the order can be pinned by a test: it is a priority list across
 * seven independent pieces of state, and a new one added in the wrong place is a Back press that
 * exits the app, or leaves a pane sitting over a closed room.
 */
internal enum class PaneBackTarget {
    /** A room is open (or mid-drag): close it. A room is not a back-stack entry, but Back is the
     *  gesture people reach for to leave one. */
    CLOSE_ROOM,

    /** The Settings pane has taken over the place the photos are: hand it back. */
    CLOSE_SETTINGS,

    /** A destination screen (Albums, Calendar, Discover, Library, Tidy up) is on top. */
    CLOSE_LEGACY_SCREEN,

    /** The most specific thing to back out of once the grid is showing. */
    CLEAR_SELECTION,
    CLOSE_SEARCH,

    /** Inside an album, a collection or a tag: up to the destination's root. */
    LEAVE_ROUTE,

    /** Nothing here to undo; Back belongs to the system. */
    NONE,
}

/**
 * Settings and a destination screen are both "panes": they replace the grid in place rather than
 * overlaying it, and they are mutually exclusive by construction (opening one clears the other).
 * Anything that draws over the grid -- the pill, the search disc, the edge scrubber, the view
 * switcher, the drill-down bar -- has to stay out of both, which is why this is one definition.
 */
internal fun paneTakenOver(settingsOpen: Boolean, legacyScreenOpen: Boolean): Boolean =
    settingsOpen || legacyScreenOpen

/**
 * The first thing Back should undo. A room wins over a pane, a pane over the grid's own state,
 * and a selection over a search over a route, most specific first -- the same order the keyboard's
 * Escape follows for the part of it that applies there.
 */
internal fun paneBackTarget(
    roomOpen: Boolean,
    settingsOpen: Boolean,
    legacyScreenOpen: Boolean,
    selectionActive: Boolean,
    searchVisible: Boolean,
    routeIsRoot: Boolean,
): PaneBackTarget = when {
    roomOpen -> PaneBackTarget.CLOSE_ROOM
    settingsOpen -> PaneBackTarget.CLOSE_SETTINGS
    legacyScreenOpen -> PaneBackTarget.CLOSE_LEGACY_SCREEN
    selectionActive -> PaneBackTarget.CLEAR_SELECTION
    searchVisible -> PaneBackTarget.CLOSE_SEARCH
    !routeIsRoot -> PaneBackTarget.LEAVE_ROUTE
    else -> PaneBackTarget.NONE
}
