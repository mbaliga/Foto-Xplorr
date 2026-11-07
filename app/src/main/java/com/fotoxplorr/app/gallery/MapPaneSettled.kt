package com.fotoxplorr.app.gallery

/**
 * Whether the map pane may be created: the browser is at rest with every room closed AND the rail
 * has stopped turning.
 *
 * The pull-out room says it is open through [atHome]. A pinned rail (a wide window) is a sibling of
 * the grid, so no room opens: it commits the hovered word, Map included, after a short rest while
 * the finger is still on the wheel, and only its own motion ([railTurning]) can say that a scrub
 * merely rested there. The Connect build's street map starts its network fetch when it exists.
 */
internal fun mapPaneSettled(atHome: Boolean, railTurning: Boolean): Boolean = atHome && !railTurning
