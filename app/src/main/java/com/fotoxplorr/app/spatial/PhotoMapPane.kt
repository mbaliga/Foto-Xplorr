package com.fotoxplorr.app.spatial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.media.MediaAsset

/**
 * The map as a pane of the browser: the photos the grid is showing, on a map, in the place the
 * grid was.
 *
 * Embedded, not a screen of its own. It has no close button and no header, and it registers no
 * Back handling -- leaving the map is the browser's decision (the bottom-left disc, the rail, or
 * Back through `paneBackTarget`), and a handler of its own here would outrank that order. It is
 * handed the photos the browser already filtered, so the destination, the route, the search, the
 * time range and the privacy filter all apply without this knowing any of them.
 *
 * The one thing it does for itself is read locations. The Places hub starts the index when a map
 * experience opens with nothing indexed, and this is the other door onto the same maps, so it
 * does the same.
 *
 * Which map it is, is the flavour's: `src/offline` draws the local stamp map and `src/connect`
 * the street map ([PhotoMapPaneImpl], same signature in both).
 *
 * @param assets what the map shows (already filtered by the browser).
 * @param frameAssets what the map is FRAMED to, so that narrowing [assets] (a time range) makes
 *   stamps appear and disappear without the whole field jumping. Already privacy-filtered.
 * @param settled whether the browser is at rest with every room closed. The street map starts
 *   fetching tiles the moment it exists, and the rail commits a word while a finger is still on
 *   the wheel, so a map that is merely passed over must not be created: see the connect
 *   implementation.
 */
@Composable
fun PhotoMapPane(
    assets: List<MediaAsset>,
    frameAssets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Once per time the map is opened, not whenever the index goes quiet with nothing scanned:
    // a library whose locations cannot be read at all (every one redacted) would otherwise start a
    // new pass the moment each one finished. The empty map's own button asks again by hand.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(geoState.scannedCount, geoState.isIndexing) {
        if (!asked && geoState.scannedCount == 0 && !geoState.isIndexing) {
            asked = true
            onIndexLocations()
        }
    }
    PhotoMapPaneImpl(
        assets = assets,
        frameAssets = frameAssets,
        geoState = geoState,
        onIndexLocations = onIndexLocations,
        onOpenAsset = onOpenAsset,
        settled = settled,
        modifier = modifier,
    )
}
