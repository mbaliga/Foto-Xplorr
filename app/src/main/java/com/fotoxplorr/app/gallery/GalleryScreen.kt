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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryUiState(
    val assets: List<MediaAsset>,
    val permissionGranted: Boolean,
    val scanState: ScanState,
)

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAsset: (MediaAsset) -> Unit,
) {
    when {
        !state.permissionGranted -> PermissionScreen(onRequestPermission)
        state.assets.isEmpty() && state.scanState is ScanState.Scanning -> LoadingScreen(state.scanState)
        state.assets.isEmpty() && state.scanState is ScanState.Error -> ErrorScreen(state.scanState.message, onRefresh)
        state.assets.isEmpty() && state.scanState is ScanState.Complete -> EmptyScreen(onRefresh)
        else -> GalleryGrid(state, onRefresh, onOpenAsset)
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
private fun GalleryGrid(
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onOpenAsset: (MediaAsset) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Foto Xplorr", style = MaterialTheme.typography.titleLarge)
                Text("${state.assets.size} photos", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Refresh",
                modifier = Modifier.clickable(onClick = onRefresh),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = state.assets,
                key = { it.id.value },
            ) { asset ->
                Thumbnail(
                    asset = asset,
                    onClick = { onOpenAsset(asset) },
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(
    asset: MediaAsset,
    onClick: () -> Unit,
) {
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
