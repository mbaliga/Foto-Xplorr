package com.fotoxplorr.app.spatial

import androidx.compose.runtime.staticCompositionLocalOf
import com.fotoxplorr.app.media.MediaAsset

data class SpatialExperience(
    /** What Places, the map, the compass and the 3D scenes actually draw -- always
     *  browsable-filtered (`browsableAssets`). Never fed to an indexer: see [indexInput]. */
    val assets: List<MediaAsset>,
    /** Every asset an indexer reachable from this experience (geo, similarity) should cover,
     *  trashed included -- Phase 1 owner decision 1 (24 Sep 2026): indexing scope and display
     *  scope are deliberately different lists, so a locked/archived/hidden/trashed photo's data
     *  still gets indexed even though [assets] never shows it here. */
    val indexInput: List<MediaAsset>,
    val geoState: GeoIndexState,
    val onIndexLocations: () -> Unit,
    val onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
)

val LocalSpatialExperience = staticCompositionLocalOf<SpatialExperience?> { null }
