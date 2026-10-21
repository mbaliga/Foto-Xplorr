package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.adaptive.GalleryShortcut

/**
 * What tapping (or landing a scrub on) the rail's ALREADY-selected word does.
 *
 * The rail only calls `onSelect` for a word that is not the selected one, and while a pane
 * (Settings, a destination screen) is open the selected word is still the destination underneath
 * it -- so the word you came from is, to the rail, "already selected", and tapping it is a
 * reselect. If a reselect only closed the room, that word could never take you back, and the pane
 * would be sticky until Back. So a reselect leaves any pane too.
 *
 * Pure so the two rails (the pull-out room and the pinned panel) are held to one decision.
 *
 * @property leavePane hand the pane back: `settingsOpen = false` and `legacyScreen = null`.
 * @property closeRoom swing the pull-out room closed. Never set for a pinned rail: no room is
 *   involved there, so there is nothing to close.
 */
internal data class RailReselectEffect(val leavePane: Boolean, val closeRoom: Boolean)

/**
 * @param inRoom whether the rail is the pull-out room's (a reselect there is also how the user
 *   says "this one, close the menu") rather than the pinned panel's.
 */
internal fun railReselectEffect(inRoom: Boolean, paneTakenOver: Boolean): RailReselectEffect =
    RailReselectEffect(leavePane = paneTakenOver, closeRoom = inRoom)

/**
 * Whether a keystroke may act on the grid. While a pane has taken over the place the photos are,
 * or a room is open over them, the grid is still composed underneath but out of reach, so Ctrl+A,
 * Delete, Enter, arrows and `/` would select, trash, open and search photos nobody can see or
 * touch. Only Escape keeps its meaning, because it is how the pane or the room is left.
 *
 * @param roomOpen a room is open or mid-drag (the shell is not at home).
 */
internal fun shortcutActsOverPane(
    shortcut: GalleryShortcut,
    paneTakenOver: Boolean,
    roomOpen: Boolean = false,
): Boolean = !(paneTakenOver || roomOpen) || shortcut == GalleryShortcut.CloseOrClear

/**
 * Whether Escape hands its work to the Back order ([paneBackTarget]) instead of undoing the grid's
 * own state. A room or a pane is something Back leaves first, so the hardware key must too:
 * otherwise Escape would clear a selection or a search while the room sat open over it.
 */
internal fun escapeFollowsBackOrder(paneTakenOver: Boolean, roomOpen: Boolean): Boolean =
    paneTakenOver || roomOpen

/**
 * Whether the notification shade is drawn over what is showing, and so whether its height is
 * reserved at the top for the shade's own pull. It is drawn on the two panes (Settings, a
 * destination screen) and on the root grid, and nowhere else: not inside an album, collection or
 * tag, not on the map, not on the timeline's headered grid, not on the zoom ladder's sparse
 * rungs. Reserving its height elsewhere would hand the top 56dp-and-more of a screen with no
 * shade to a dead zone where the top room cannot be pulled down.
 *
 * Mirrors the branches of the browsing `when` in `GalleryScreen`; a screen that gains a shade must
 * be added here too.
 *
 * @param mapOpen the map is showing in place of the grid. [zoomIsGrid] and [timelineHeadersOn] are
 *   the ladder's and the old switcher's, and go when the continuous grid replaces them; they
 *   default to "a plain grid" so a caller that has dropped them already compiles.
 */
internal fun shadeIsDrawn(
    paneTakenOver: Boolean,
    mapOpen: Boolean,
    routeIsRoot: Boolean,
    zoomIsGrid: Boolean = true,
    timelineHeadersOn: Boolean = false,
): Boolean = paneTakenOver || (!mapOpen && zoomIsGrid && !timelineHeadersOn && routeIsRoot)
