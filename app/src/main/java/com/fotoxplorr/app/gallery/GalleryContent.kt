@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.organize.MediaCollection
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen

@Composable
fun TimelineScreen(
    assets: List<MediaAsset>,
    grouping: TimelineGrouping,
    columns: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
) {
    if (assets.isEmpty()) {
        GalleryMessage("No media matches the current filters")
        return
    }
    val groups = timelineGroups(assets, grouping)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group ->
            item(
                key = "header:${group.key}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                    Text("${group.assets.size}", style = MaterialTheme.typography.labelMedium)
                }
            }
            items(group.assets, key = { it.id.value }) { asset ->
                MediaTile(
                    asset = asset,
                    favorite = asset.id in favoriteIds,
                    sensitive = asset.id in sensitiveIds,
                    blurSensitive = blurSensitive,
                    selected = asset.id in selectedIds,
                    onOpen = { onOpen(asset) },
                    onToggleSelection = { onToggleSelection(asset.id) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
fun MediaGridScreen(
    assets: List<MediaAsset>,
    columns: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    emptyMessage: String,
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
) {
    if (assets.isEmpty()) {
        GalleryMessage(emptyMessage)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(assets, key = { it.id.value }) { asset ->
            MediaTile(
                asset = asset,
                favorite = asset.id in favoriteIds,
                sensitive = asset.id in sensitiveIds,
                blurSensitive = blurSensitive,
                selected = asset.id in selectedIds,
                onOpen = { onOpen(asset) },
                onToggleSelection = { onToggleSelection(asset.id) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun MediaTile(
    asset: MediaAsset,
    favorite: Boolean,
    sensitive: Boolean,
    blurSensitive: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { if (selected) onToggleSelection() else onOpen() },
                onLongClick = onToggleSelection,
            ),
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier
                .fillMaxSize()
                .then(if (sensitive && blurSensitive) Modifier.blur(22.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
        if (sensitive && blurSensitive) {
            Text(
                "Sensitive",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (asset.isVideo) {
            Text(
                formatDuration(asset.durationMillis),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (favorite) {
            Text(
                "★",
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)),
            )
            Text(
                "✓",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
fun AlbumsScreen(
    assets: List<MediaAsset>,
    collections: List<MediaCollection>,
    archivedIds: Set<MediaId>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    showVideos: Boolean,
    query: String,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onOpenCollection: (MediaCollection) -> Unit,
) {
    val available = assets.filter { asset ->
        !asset.isTrashed && asset.id !in archivedIds && (showVideos || !asset.isVideo)
    }
    val albums = buildAlbumSummaries(available, query)
    val normalized = query.trim().lowercase()
    val matchingCollections = collections.filter {
        normalized.isEmpty() || it.name.lowercase().contains(normalized)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (matchingCollections.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("Collections", "Virtual albums that never move your files")
            }
            items(matchingCollections, key = { "collection:${it.id}" }) { collection ->
                val cover = collection.mediaIds.firstNotNullOfOrNull { id -> assets.firstOrNull { it.id == id } }
                AlbumCard(
                    name = collection.name,
                    count = collection.mediaIds.size,
                    cover = cover,
                    locked = false,
                    collection = true,
                    onClick = { onOpenCollection(collection) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeading("Device folders", "Media remains in its original folder")
        }
        items(albums, key = { "album:${it.key}" }) { album ->
            val locked = album.key in lockedFolders && album.key !in unlockedFolders
            AlbumCard(
                name = album.name,
                count = album.count,
                cover = if (locked) null else album.cover,
                locked = locked,
                collection = false,
                onClick = { onOpenAlbum(album) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun AlbumCard(
    name: String,
    count: Int,
    cover: MediaAsset?,
    locked: Boolean,
    collection: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.16f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cover != null -> MediaImage(
                    asset = cover,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                locked -> Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(36.dp))
                collection -> Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(36.dp))
                else -> Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                if (locked) "Private folder" else "$count items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DiscoverScreen(
    summaries: List<SmartAlbumSummary>,
    onOpen: (SmartAlbumSummary) -> Unit,
) {
    val spatial = LocalSpatialExperience.current
    var showingPlaces by remember { mutableStateOf(false) }

    if (showingPlaces && spatial != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = { showingPlaces = false }) { Text("Back to Discover") }
            PlacesScreen(
                assets = spatial.assets,
                geoState = spatial.geoState,
                onIndexLocations = spatial.onIndexLocations,
                onOpenAsset = spatial.onOpenAsset,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spatial != null) {
            item(key = "places") {
                Card(
                    modifier = Modifier.combinedClickable(
                        onClick = { showingPlaces = true },
                        onLongClick = { showingPlaces = true },
                    ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(40.dp))
                        if (spatial.geoState.locatedCount > 0) {
                            Text(
                                spatial.geoState.locatedCount.toString(),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Places", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Offline map, compass and elevation from embedded metadata",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        items(summaries, key = { it.album.name }) { summary ->
            Card(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpen(summary) },
                    onLongClick = { onOpen(summary) },
                ),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.35f)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    summary.cover?.let {
                        MediaImage(it, Modifier.fillMaxSize(), ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                    } ?: Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text(
                        summary.count.toString(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(summary.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        summary.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
fun LibraryScreen(
    library: LibraryState,
    privateAlbumCount: Int,
    trashCount: Int,
    onOpenCollection: (MediaCollection) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPrivateFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportMetadata: () -> Unit,
    onImportMetadata: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Organise", "Local metadata; original media is unchanged") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LibraryActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Archive",
                    subtitle = "${library.archivedIds.size} items",
                    icon = Icons.Outlined.Archive,
                    onClick = onOpenArchive,
                )
                LibraryActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Trash",
                    subtitle = "$trashCount items",
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onOpenTrash,
                )
            }
        }
        item {
            LibraryActionCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Private folders",
                subtitle = "$privateAlbumCount protected · in-app access control",
                icon = Icons.Outlined.Lock,
                onClick = onOpenPrivateFolders,
            )
        }

        item { SectionHeading("Collections", "${library.collections.size} virtual albums") }
        if (library.collections.isEmpty()) {
            item { Text("Use the + button to create a collection.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(library.collections, key = { it.id }) { collection ->
                Card(onClick = { onOpenCollection(collection) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Collections, null)
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, style = MaterialTheme.typography.titleSmall)
                            Text("${collection.mediaIds.size} items", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item { SectionHeading("Tags", "Searchable labels stored only in Foto Xplorr") }
        item {
            if (library.allTags.isEmpty()) {
                Text("Select media and choose Add tag to create your first tag.")
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(library.allTags, key = { it }) { tag ->
                        AssistChip(onClick = { onOpenTag(tag) }, label = { Text("#$tag") })
                    }
                }
            }
        }

        item { SectionHeading("Metadata backup", "Collections, tags and archive state") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onExportMetadata) {
                    Icon(Icons.Outlined.UploadFile, null)
                    Text(" Export")
                }
                TextButton(onClick = onImportMetadata) {
                    Icon(Icons.Outlined.Collections, null)
                    Text(" Import")
                }
            }
        }
        item {
            LibraryActionCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Settings",
                subtitle = "Appearance, timeline, privacy and slideshow",
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun LibraryActionCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GalleryMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.padding(32.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "Video"
    val totalSeconds = durationMillis / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
