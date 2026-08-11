@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ai.AiSettingsScreen
import com.fotoxplorr.app.ai.SimilarityExplorerScreen
import com.fotoxplorr.app.experience.GalleryPreviewScreen
import com.fotoxplorr.app.experience.PhotoWallScreen
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage

private enum class ExploreExperience(
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    SIMILARITY(
        "Organise by similarity",
        "Local image embeddings arranged into visual neighbourhoods",
        Icons.Outlined.Psychology,
    ),
    PHOTO_WALL(
        "3D photo wall",
        "GPU-rendered mixed-size corridor with touch and gyro navigation",
        Icons.Outlined.AutoAwesomeMosaic,
    ),
    GALLERY_PREVIEW(
        "Gallery preview",
        "Focused central preview with adjacent images in depth",
        Icons.Outlined.PermMedia,
    ),
    PHOTO_MAP(
        "Rich photo map",
        "Clustered geotagged media, pitch, hillshade and 3D buildings",
        Icons.Outlined.Map,
    ),
    SPATIAL_COMPASS(
        "Spatial compass",
        "Stand at your current location and turn toward photos around you",
        Icons.Outlined.ViewInAr,
    ),
    AI_SETTINGS(
        "AI and provider keys",
        "Local model management and encrypted optional provider credentials",
        Icons.Outlined.Key,
    ),
}

@Composable
fun PlacesScreen(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    var experience by remember { mutableStateOf<ExploreExperience?>(null) }

    LaunchedEffect(experience, geoState.scannedCount, geoState.isIndexing) {
        if (
            experience in setOf(ExploreExperience.PHOTO_MAP, ExploreExperience.SPATIAL_COMPASS) &&
            geoState.scannedCount == 0 &&
            !geoState.isIndexing
        ) {
            onIndexLocations()
        }
    }

    when (experience) {
        ExploreExperience.SIMILARITY -> SimilarityExplorerScreen(
            assets = assets,
            onOpenAsset = onOpenAsset,
            onClose = { experience = null },
        )
        ExploreExperience.PHOTO_WALL -> PhotoWallScreen(
            assets = assets,
            onOpenAsset = onOpenAsset,
            onClose = { experience = null },
        )
        ExploreExperience.GALLERY_PREVIEW -> GalleryPreviewScreen(
            assets = assets,
            onOpenAsset = onOpenAsset,
            onClose = { experience = null },
        )
        // Flavor seam: connect renders the MapLibre map, offline an honest absence.
        ExploreExperience.PHOTO_MAP -> PhotoMapExperience(
            assets = assets,
            geoState = geoState,
            onIndexLocations = onIndexLocations,
            onOpenAsset = onOpenAsset,
            onClose = { experience = null },
        )
        ExploreExperience.SPATIAL_COMPASS -> SpatialPhotoSceneScreen(
            assets = assets,
            geoState = geoState,
            onIndexLocations = onIndexLocations,
            onOpenAsset = onOpenAsset,
            onClose = { experience = null },
        )
        ExploreExperience.AI_SETTINGS -> AiSettingsScreen(onClose = { experience = null })
        null -> ExploreHub(
            assets = assets,
            locatedCount = geoState.locatedCount,
            locationIndexing = geoState.isIndexing,
            onOpen = { experience = it },
        )
    }
}

@Composable
private fun ExploreHub(
    assets: List<MediaAsset>,
    locatedCount: Int,
    locationIndexing: Boolean,
    onOpen: (ExploreExperience) -> Unit,
) {
    val imageCover = assets.firstOrNull { !it.isVideo && !it.isTrashed }
    val alternateCover = assets.drop(8).firstOrNull { !it.isTrashed } ?: imageCover

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                Text("Immersive and AI", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Six optional ways to explore the same local library. The ordinary gallery continues to work with AI, network and location disabled.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(164.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ExploreExperience.entries, key = { it.name }) { feature ->
                val cover = when (feature) {
                    ExploreExperience.PHOTO_WALL,
                    ExploreExperience.GALLERY_PREVIEW,
                    ExploreExperience.SIMILARITY,
                    -> if (feature == ExploreExperience.GALLERY_PREVIEW) alternateCover else imageCover
                    else -> null
                }
                ExperienceCard(
                    feature = feature,
                    cover = cover,
                    badge = when (feature) {
                        ExploreExperience.PHOTO_MAP,
                        ExploreExperience.SPATIAL_COMPASS,
                        -> if (locationIndexing) "Indexing…" else "$locatedCount located"
                        ExploreExperience.PHOTO_WALL -> "OpenGL ES"
                        ExploreExperience.SIMILARITY -> "On-device"
                        ExploreExperience.GALLERY_PREVIEW -> "Touch + D-pad"
                        ExploreExperience.AI_SETTINGS -> "Keystore"
                    },
                    onClick = { onOpen(feature) },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy boundaries", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Similarity indexing is local. Map tiles are requested only while the map is open. Current location is requested only inside Spatial compass. Remote AI stays disabled until a user adds and enables a provider key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(72.dp))
                }
            }
        }
    }
}

@Composable
private fun ExperienceCard(
    feature: ExploreExperience,
    cover: MediaAsset?,
    badge: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                MediaImage(cover, Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            }
            Icon(
                feature.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = if (cover == null) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
            )
            Text(
                badge,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.small)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(feature.title, style = MaterialTheme.typography.titleSmall)
            Text(
                feature.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
