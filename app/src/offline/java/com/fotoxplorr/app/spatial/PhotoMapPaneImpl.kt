package com.fotoxplorr.app.spatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.media.MediaAsset

/**
 * The offline flavour's map pane: the stamp map, embedded. Tile-free -- a photo's place is only a
 * latitude and longitude, so pinning a stamp at it is arithmetic and this build needs nothing from
 * the outside. Same fully-qualified signature as `src/connect`'s implementation; the flavour picks
 * which one compiles in, and [PhotoMapPane] (main) calls it without knowing which it got.
 *
 * [settled] is unused here: there is nothing to defer, since nothing is fetched.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PhotoMapPaneImpl(
    assets: List<MediaAsset>,
    frameAssets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    StampMapScreen(
        assets = assets,
        geoState = geoState,
        onIndexLocations = onIndexLocations,
        onOpenAsset = onOpenAsset,
        onClose = {},
        modifier = modifier,
        embedded = true,
        frameAssets = frameAssets,
    )
}
