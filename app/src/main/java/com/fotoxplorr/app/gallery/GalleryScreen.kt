package com.fotoxplorr.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryUiState(
    val assets: List<MediaAsset>,
    val favoriteIds: Set<MediaId>,
    val permissionGranted: Boolean,
    val scanState: ScanState,
    val preferences: GalleryPreferencesState,
)

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSetSort: (GallerySort) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    when {
        !state.permissionGranted -> PermissionScreen(onRequestPermission)
        state.assets.isEmpty() && state.scanState is ScanState.Scanning -> LoadingScreen(state.scanState)
        state.assets.isEmpty() && state.scanState is ScanState.Error -> ErrorScreen(state.scanState.message, onRefresh)
        state.assets.isEmpty() && state.scanState is ScanState.Complete -> EmptyScreen(onRefresh)
        else -> GalleryBrowser(state, onRefresh, onSetSort, onSetGridColumns, onOpenAsset)
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) {
    CenteredColumn {
        Text("Foto Xplorr needs access to your photos to build a local gallery.")
        Button(onClick = onRequestPermission) { Text("Choose photos") }
    }
}

@Composable
private fun LoadingScreen(state: ScanState.Scanning) {
    CenteredColumn {
        CircularProgressIndicator()
        val progress = if (state.discovered > 0) "${state.scanned} / ${state.discovered}" else "Scanning"
        Text(progress)
    }
}

@Composable
private fun ErrorScreen(message: String, onRefresh: () -> Unit) {
    CenteredColumn {
        Text(message)
        Button(onClick = onRefresh) { Text("Retry") }
    }
}

@Composable
private fun EmptyScreen(onRefresh: () -> Unit) {
    CenteredColumn {
        Text("No photos found")
        Button(onClick = onRefresh) { Text("Scan again") }
    }
}

@Composable
private fun GalleryBrowser(
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onSetSort: (GallerySort) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    var section by remember { mutableStateOf(GallerySection.PHOTOS) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val visible = visibleAssets(
        assets = state.assets,
        favoriteIds = state.favoriteIds,
        section = section,
        selectedAlbum = selectedAlbum,
        query = query,
        sort = state.preferences.sort,
    )
    val albums = buildAlbumSummaries(state.assets, query)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(selectedAlbum ?: "Foto Xplorr", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        selectedAlbum != null -> "${visible.size} photos"
                        section == GallerySection.ALBUMS -> "${albums.size} albums"
                        section == GallerySection.FAVORITES -> "${visible.size} favourites"
                        else -> "${visible.size} photos"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (selectedAlbum != null) "Back" else "Refresh",
                modifier = Modifier.clickable {
                    if (selectedAlbum != null) selectedAlbum = null else onRefresh()
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            singleLine = true,
            label = { Text("Search photos and albums") },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GallerySection.entries.forEach { candidate ->
                FilterChip(
                    selected = section == candidate,
                    onClick = {
                        section = candidate
                        selectedAlbum = null
                    },
                    label = { Text(candidate.label()) },
                )
            }
        }

        if (section != GallerySection.ALBUMS || selectedAlbum != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GallerySort.entries.forEach { sort ->
                    FilterChip(
                        selected = state.preferences.sort == sort,
                        onClick = { onSetSort(sort) },
                        label = { Text(sort.label()) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Grid ${state.preferences.gridColumns}")
                Button(
                    enabled = state.preferences.gridColumns > MIN_GRID_COLUMNS,
                    onClick = { onSetGridColumns(state.preferences.gridColumns - 1) },
                ) { Text("Larger") }
                Button(
                    enabled = state.preferences.gridColumns < MAX_GRID_COLUMNS,
                    onClick = { onSetGridColumns(state.preferences.gridColumns + 1) },
                ) { Text("Smaller") }
            }
        }

        when {
            section == GallerySection.ALBUMS && selectedAlbum == null -> AlbumGrid(albums) {
                selectedAlbum = it.name
            }
            visible.isEmpty() -> CenteredColumn {
                Text(
                    when {
                        query.isNotBlank() -> "No matches"
                        section == GallerySection.FAVORITES -> "No favourites yet"
                        else -> "No photos in this album"
                    },
                )
            }
            else -> AssetGrid(
                assets = visible,
                columns = state.preferences.gridColumns,
                onOpenAsset = { asset -> onOpenAsset(asset, visible) },
            )
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<AlbumSummary>, onOpenAlbum: (AlbumSummary) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.name }) { album ->
            Column(
                modifier = Modifier
                    .clickable { onOpenAlbum(album) }
                    .padding(6.dp),
            ) {
                Thumbnail(album.cover) { onOpenAlbum(album) }
                Text(album.name, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                Text("${album.count} photos", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AssetGrid(
    assets: List<MediaAsset>,
    columns: Int,
    onOpenAsset: (MediaAsset) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(assets, key = { it.id.value }) { asset ->
            Thumbnail(asset) { onOpenAsset(asset) }
        }
    }
}

@Composable
private fun Thumbnail(asset: MediaAsset, onClick: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(initialValue = null, asset.contentUri, asset.id) {
        value = loadThumbnail(resolver, asset)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(asset.displayName.take(1).uppercase())
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = asset.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun GallerySection.label(): String = when (this) {
    GallerySection.PHOTOS -> "Photos"
    GallerySection.FAVORITES -> "Favourites"
    GallerySection.ALBUMS -> "Albums"
}

private fun GallerySort.label(): String = when (this) {
    GallerySort.NEWEST -> "Newest"
    GallerySort.OLDEST -> "Oldest"
    GallerySort.NAME -> "Name"
    GallerySort.SIZE -> "Size"
}

private suspend fun loadThumbnail(
    resolver: ContentResolver,
    asset: MediaAsset,
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(asset.contentUri, Size(360, 360), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                asset.id.value,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            ) ?: resolver.openInputStream(asset.contentUri)?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}
