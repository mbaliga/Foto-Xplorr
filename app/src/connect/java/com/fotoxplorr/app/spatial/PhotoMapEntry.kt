package com.fotoxplorr.app.spatial

import androidx.compose.runtime.Composable
import com.fotoxplorr.app.media.MediaAsset

/**
 * The connect flavor's photo map: the real MapLibre screen. Same fully-qualified
 * signature as `src/offline`'s implementation — the flavor picks which one compiles in,
 * which is the whole seam. `PlacesScreen` (main) calls this and knows nothing about maps
 * or flavors.
 */
@Composable
fun PhotoMapExperience(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    RichPhotoMapScreen(
        assets = assets,
        geoState = geoState,
        onIndexLocations = onIndexLocations,
        onOpenAsset = onOpenAsset,
        onClose = onClose,
    )
}
