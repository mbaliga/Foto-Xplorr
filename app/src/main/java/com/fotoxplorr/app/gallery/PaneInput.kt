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
 * the grid is still composed underneath it but hidden, so Ctrl+A, Delete, Enter, arrows and `/`
 * would select, trash, open and search photos nobody can see. Only Escape keeps its meaning,
 * because it is how the pane is left.
 */
internal fun shortcutActsOverPane(shortcut: GalleryShortcut, paneTakenOver: Boolean): Boolean =
    !paneTakenOver || shortcut == GalleryShortcut.CloseOrClear
