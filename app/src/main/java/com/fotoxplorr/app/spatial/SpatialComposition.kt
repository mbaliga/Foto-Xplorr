package com.fotoxplorr.app.spatial

import androidx.compose.runtime.staticCompositionLocalOf
import com.fotoxplorr.app.media.MediaAsset

data class SpatialExperience(
    val assets: List<MediaAsset>,
    val geoState: GeoIndexState,
    val onIndexLocations: () -> Unit,
    val onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
)

val LocalSpatialExperience = staticCompositionLocalOf<SpatialExperience?> { null }
