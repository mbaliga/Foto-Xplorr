package com.fotoxplorr.app.spatial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/**
 * The offline flavour's plate backdrop: nothing.
 *
 * The plate keeps its local graticule look, because a street map under the pin means map tiles
 * from a server and this build performs no network I/O at all -- the same reason this flavour's
 * map pane is the tile-free stamp map. Same fully-qualified signature as `src/connect`'s
 * implementation; the flavour picks which one compiles in, and `PlacePlate` (main) calls it
 * without knowing which it got.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PlateMapBackdrop(
    assetId: MediaId,
    latitude: Double,
    longitude: Double,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    // Deliberately empty: see the KDoc.
}

/**
 * The offline flavour's full-screen map for one photo's place: the tile-free stamp map, with
 * every located photo a filled colour dot and this one its white-bordered tile. See [MapFocus].
 */
@Composable
fun ExpandedPhotoMap(
    focus: MapFocus,
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onOpenAsset: (MediaAsset) -> Unit,
    onClose: () -> Unit,
) {
    StampMapScreen(
        assets = assets,
        geoState = geoState,
        onIndexLocations = {},
        onOpenAsset = { asset, _ -> onOpenAsset(asset) },
        onClose = onClose,
        focus = focus,
    )
}
