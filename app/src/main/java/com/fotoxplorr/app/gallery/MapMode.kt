package com.fotoxplorr.app.gallery

/**
 * The map as a MODE of the browser, and how the rail and the bottom-left disc reach it.
 *
 * The map is not a destination (the enum is pinned at nine by the goldens and the settings
 * picker), not a route (it is not below a destination) and not a zoom rung (a pinch never
 * lands on it). It is one flag next to `destination` and `route` that swaps the grid for the same
 * photos on a map, so going back to the grid keeps the album, the search, the time range and the
 * scroll position exactly as they were. These are the pure decisions around that flag, pulled out
 * so the two rails and the disc are held to one definition.
 */

/** The rail word that opens the map. Not a [HyleDestination] name, so it cannot be mistaken for one. */
internal const val MAP_RAIL_ID = "MAP"

/** What the rail calls it. */
internal const val MAP_RAIL_LABEL = "Map"

/**
 * The rail's words top to bottom as (id, label): the destinations in their own order with Map
 * added before Experimental (the destination still named PLACES, whose label is "Experimental" --
 * the hub of the immersive views).
 */
internal fun railWords(): List<Pair<String, String>> = buildList {
    HyleDestination.entries.forEach { destination ->
        if (destination == HyleDestination.PLACES) add(MAP_RAIL_ID to MAP_RAIL_LABEL)
        add(destination.name to destination.label)
    }
}

/**
 * What choosing a rail word leaves the browser in.
 *
 * @property mapOpen whether the map is showing afterwards.
 * @property destination the destination afterwards.
 * @property resetsRoute whether the drill-down route goes back to the destination's root. Choosing
 *   a destination does; choosing Map does not, because the map shows the album you were in.
 */
internal data class RailChoice(
    val mapOpen: Boolean,
    val destination: HyleDestination,
    val resetsRoute: Boolean,
)

/**
 * Choosing [id] while [current] is the destination. Map keeps the destination -- unless it is one
 * with no photos to put on a map (Experimental has its own panes, Protected must never feed a map), when
 * it falls back to Photos. Any real word leaves the map.
 */
internal fun railChoice(id: String, current: HyleDestination): RailChoice =
    if (id == MAP_RAIL_ID) {
        RailChoice(
            mapOpen = true,
            destination = if (mapToggleOffered(current)) current else HyleDestination.PHOTOS,
            resetsRoute = false,
        )
    } else {
        RailChoice(mapOpen = false, destination = HyleDestination.valueOf(id), resetsRoute = true)
    }

/** The rail's selected word: Map while the map is showing, otherwise the destination. */
internal fun railSelectedId(mapOpen: Boolean, destination: HyleDestination): String =
    if (mapOpen) MAP_RAIL_ID else destination.name

/**
 * Whether the bottom-left disc (and so the map) is offered on [destination]. Experimental and Protected
 * draw their own panes rather than a grid of photos, so there is nothing to toggle, and the
 * protected folders must not be handed to a map at all.
 */
internal fun mapToggleOffered(destination: HyleDestination): Boolean =
    destination != HyleDestination.PLACES && destination != HyleDestination.PROTECTED

/** Whether a time range does anything on [destination]; the same destinations as the disc. */
internal fun timeRangeApplies(destination: HyleDestination): Boolean = mapToggleOffered(destination)
