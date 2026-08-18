@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.organize.MediaCollection
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen

/**
 * Gutter between grid tiles. The mockups draw an essentially seamless mosaic -- a hairline
 * of black between tiles and nothing at the screen edges -- so this is 1dp rather than the
 * 2dp the grid used before, and no content padding is applied.
 */
private val GRID_GUTTER = 1.dp

/**
 * The pure-black canvas the mockups sit every grid on. Deliberately a literal black rather
 * than `colorScheme.background`: the mockups are black regardless of the theme's surface
 * tint, and OLED-black is the whole visual point of the design.
 */
val GRID_BACKGROUND: Color = Color.Black

@Composable
fun TimelineScreen(
    assets: List<MediaAsset>,
    grouping: TimelineGrouping,
    columns: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    /**
     * Whether the selection chrome is up. Defaults to the old rule -- something is picked -- so
     * the several grids that do not participate in selection keep working unchanged. The main
     * gallery passes the real flag, because selection can now be entered with nothing picked yet.
     */
    selectionActive: Boolean = selectedIds.isNotEmpty(),
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    /**
     * Whether to draw date-group headers. The mockups' main grid has none -- it is one
     * continuous mosaic -- so the primary destination passes `false` here. The Timeline
     * grouping preference still drives this everywhere it applies (see
     * the grouped variant this delegates to), so the feature is switched off in
     * this view rather than deleted.
     */
    showDateHeaders: Boolean = true,
    gridState: LazyGridState = rememberLazyGridState(),
    fitToTile: Boolean = true,
    loopAnimations: Boolean = false,
    longPressPreview: Boolean = true,
) {
    if (assets.isEmpty()) {
        GalleryMessage("No media matches the current filters")
        return
    }
    if (!showDateHeaders || grouping == TimelineGrouping.NONE) {
        MediaGridScreen(
            assets = assets,
            columns = columns,
            favoriteIds = favoriteIds,
            sensitiveIds = sensitiveIds,
            blurSensitive = blurSensitive,
            selectedIds = selectedIds,
            emptyMessage = "No media matches the current filters",
            selectionActive = selectionActive,
            onOpen = onOpen,
            onToggleSelection = onToggleSelection,
            gridState = gridState,
            fitToTile = fitToTile,
            loopAnimations = loopAnimations,
            longPressPreview = longPressPreview,
        )
        return
    }
    // Its own peek state: this is the date-grouped variant and does not delegate to
    // MediaGridScreen, so it cannot inherit that one's overlay.
    var peeked by remember { mutableStateOf<MediaAsset?>(null) }
    val onPeek: (MediaAsset) -> Unit = { peeked = it }
    val groups = timelineGroups(assets, grouping)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
        verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
    ) {
        groups.forEach { group ->
            item(
                key = "header:${group.key}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GRID_BACKGROUND)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        "${group.assets.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            items(group.assets, key = { it.id.value }) { asset ->
                MediaTile(
                    asset = asset,
                    favorite = asset.id in favoriteIds,
                    sensitive = asset.id in sensitiveIds,
                    blurSensitive = blurSensitive,
                    selected = asset.id in selectedIds,
                    selectionActive = selectionActive,
                    fitToTile = fitToTile,
                    loopAnimations = loopAnimations,
                    longPressPreview = longPressPreview,
                    onOpen = { onOpen(asset) },
                    onToggleSelection = { onToggleSelection(asset.id) },
                    onPeek = { onPeek(asset) },
                    onPeekEnd = { peeked = null },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }

    peeked?.let { asset -> MediaPeek(asset = asset, loopAnimations = loopAnimations) }
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
    /** See TimelineScreen's own parameter of this name. */
    selectionActive: Boolean = selectedIds.isNotEmpty(),
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    // Media-behaviour preferences. Defaulted so the several other call sites that render a grid
    // (albums, collections, search results) keep working without each having to thread them.
    fitToTile: Boolean = true,
    loopAnimations: Boolean = false,
    longPressPreview: Boolean = true,
) {
    if (assets.isEmpty()) {
        GalleryMessage(emptyMessage)
        return
    }
    // The peeked photo, if any. Hoisted here rather than held per tile so only one can ever be
    // open, and so the overlay draws above the whole grid instead of inside one cell's bounds.
    var peeked by remember { mutableStateOf<MediaAsset?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
            verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        ) {
            items(assets, key = { it.id.value }) { asset ->
                MediaTile(
                    asset = asset,
                    favorite = asset.id in favoriteIds,
                    sensitive = asset.id in sensitiveIds,
                    blurSensitive = blurSensitive,
                    selected = asset.id in selectedIds,
                    selectionActive = selectionActive,
                    fitToTile = fitToTile,
                    loopAnimations = loopAnimations,
                    longPressPreview = longPressPreview,
                    onOpen = { onOpen(asset) },
                    onToggleSelection = { onToggleSelection(asset.id) },
                    onPeek = { peeked = asset },
                    onPeekEnd = { peeked = null },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
        }

        peeked?.let { asset -> MediaPeek(asset = asset, loopAnimations = loopAnimations) }
    }
}

/**
 * The peek: the photo, large, for exactly as long as the finger is held on it.
 *
 * Held, not opened (owner, 2026-08-18: *"the quick preview must disappear when the long press is
 * released"*). It is a glance at a thumbnail too small to read, so the gesture that asks for it is
 * the gesture that holds it open, and letting go puts it back. That is why it carries no buttons:
 * a control you cannot reach without releasing -- which closes the thing the control is on -- is
 * not a control.
 *
 * Drawn in the composition rather than in a `Dialog`, which matters here. A dialog is a new
 * window, and raising one mid-gesture cancels the pointer stream on the window underneath -- the
 * hold would end the instant the preview appeared, so the preview would flash and vanish.
 *
 * Long press used to be how a selection started. That entry point moves to the gallery's own
 * actions room ("Select photos"), and the `longPressPreview` preference still restores
 * long-press-to-select exactly as it was for anyone who prefers it.
 */
@Composable
private fun MediaPeek(asset: MediaAsset, loopAnimations: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            // Swallows pointer events so nothing behind the peek can be scrolled or tapped by
            // the same finger that is holding it open.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentScale = ContentScale.Fit,
            animate = loopAnimations,
        )
        Text(
            asset.displayName,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Release to go back",
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun MediaTile(
    asset: MediaAsset,
    favorite: Boolean,
    sensitive: Boolean,
    blurSensitive: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    fitToTile: Boolean,
    loopAnimations: Boolean,
    longPressPreview: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onPeek: () -> Unit,
    onPeekEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            // Black rather than surfaceVariant: an unloaded tile should read as a gap in the
            // mosaic, not as a light grey block punched through it.
            .background(GRID_BACKGROUND)
            // Hand-rolled rather than combinedClickable, because the peek needs the one thing
            // that API does not report: the RELEASE. onLongClick tells you the hold began and
            // nothing tells you it ended, so a peek built on it can only be a modal that outlives
            // the gesture -- which is exactly what this replaces.
            //
            // detectTapGestures gives all three: onPress suspends until the finger lifts or the
            // gesture is cancelled, onLongPress fires at the timeout, onTap only when it did not.
            .pointerInput(selected, selectionActive, longPressPreview) {
                detectTapGestures(
                    onPress = {
                        // Returns on release AND on cancellation -- so a peek cannot be stranded
                        // on screen by the grid scrolling out from under the finger.
                        tryAwaitRelease()
                        onPeekEnd()
                    },
                    // Long press peeks -- EXCEPT while a selection is already running, where
                    // extending that selection is obviously what the gesture means, and except
                    // when the user has turned peeking off, which restores the old behaviour.
                    onLongPress = {
                        if (selectionActive || !longPressPreview) onToggleSelection() else onPeek()
                    },
                    onTap = { if (selectionActive) onToggleSelection() else onOpen() },
                )
            },
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier
                .fillMaxSize()
                .then(if (sensitive && blurSensitive) Modifier.blur(22.dp) else Modifier),
            // Crop fills the square and trims the edges off; Fit letterboxes so the whole frame
            // and its true proportions survive. The tile itself stays square either way -- the
            // grid's column geometry, and the scrubber index mapping that rides on it, both
            // assume a uniform cell.
            contentScale = if (fitToTile) ContentScale.Crop else ContentScale.Fit,
            animate = loopAnimations,
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
            if (locked) {
                // A locked folder shows no photos at all, so there is nothing to stack -- and a
                // fanned stack of blank rectangles would imply content it is deliberately hiding.
                AlbumCard(
                    name = album.name,
                    count = album.count,
                    cover = null,
                    locked = true,
                    collection = false,
                    onClick = { onOpenAlbum(album) },
                )
            } else {
                AlbumStack(
                    covers = album.covers,
                    label = album.name,
                    count = album.count,
                    stackKey = album.key,
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenAlbum(album) },
                        onLongClick = { onOpenAlbum(album) },
                    ),
                )
            }
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
    // Drawn on [GRID_BACKGROUND], which is a literal black regardless of theme, so the text
    // colour has to be explicit too -- inheriting onSurface would render this dark-on-black
    // and effectively invisible whenever the app is in light theme.
    Box(
        Modifier.fillMaxSize().background(GRID_BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "Video"
    val totalSeconds = durationMillis / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
