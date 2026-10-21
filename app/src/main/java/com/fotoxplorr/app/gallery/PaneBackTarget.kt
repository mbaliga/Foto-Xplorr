package com.fotoxplorr.app.gallery

/**
 * What the system Back gesture undoes next while browsing.
 *
 * Pulled out of the composable so the order can be pinned by a test: it is a priority list across
 * eight independent pieces of state, and a new one added in the wrong place is a Back press that
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

    /** The time range panel is up over the grid or the map: it is the topmost thing there. */
    CLOSE_TIME_EDITOR,

    /** The most specific thing to back out of once the grid is showing. */
    CLEAR_SELECTION,
    CLOSE_SEARCH,

    /** The map is showing in place of the grid: back to the grid, route and destination kept. */
    CLOSE_MAP,

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
 * and a selection over a search over the map over a route, most specific first -- the same order
 * the keyboard's Escape follows for the part of it that applies there.
 *
 * The map sits below the search because a search is typed over it, and above the route because
 * leaving the map is a smaller step than leaving the album the map is showing. The map screens
 * register no Back handling of their own when embedded; if one did, it would outrank this order.
 *
 * The time panel is first among the grid's own state: it is a transient panel over the grid or
 * the map, so nothing under it is the next thing to undo. [timeEditorShown] is whether it is
 * DRAWN, not whether the flag that opens it is set -- see [timeEditorShown] -- so a flag left set
 * under a selection or a pane never makes Back do nothing.
 *
 * [mapOpen] and [timeEditorShown] are last, and defaulted, so a caller that predates them keeps
 * compiling.
 */
internal fun paneBackTarget(
    roomOpen: Boolean,
    settingsOpen: Boolean,
    legacyScreenOpen: Boolean,
    selectionActive: Boolean,
    searchVisible: Boolean,
    routeIsRoot: Boolean,
    mapOpen: Boolean = false,
    timeEditorShown: Boolean = false,
): PaneBackTarget = when {
    roomOpen -> PaneBackTarget.CLOSE_ROOM
    settingsOpen -> PaneBackTarget.CLOSE_SETTINGS
    legacyScreenOpen -> PaneBackTarget.CLOSE_LEGACY_SCREEN
    timeEditorShown -> PaneBackTarget.CLOSE_TIME_EDITOR
    selectionActive -> PaneBackTarget.CLEAR_SELECTION
    searchVisible -> PaneBackTarget.CLOSE_SEARCH
    mapOpen -> PaneBackTarget.CLOSE_MAP
    !routeIsRoot -> PaneBackTarget.LEAVE_ROUTE
    else -> PaneBackTarget.NONE
}

/**
 * Whether the time range panel is on screen. The panel is drawn only over the grid or the map
 * (not over a selection's bar or a pane that has taken the grid's place) and only on a
 * destination a range applies to; the flag that opens it ([open]) is left set by every other
 * route that hides it, so Back must key off this, not the flag, or it is swallowed by a panel
 * nobody can see.
 */
internal fun timeEditorShown(
    open: Boolean,
    selectionActive: Boolean,
    paneTakenOver: Boolean,
    rangeApplies: Boolean,
): Boolean = open && !selectionActive && !paneTakenOver && rangeApplies
