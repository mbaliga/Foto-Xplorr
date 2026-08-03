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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryUiState(
    val assets: List<MediaAsset>,
    val favoriteIds: Set<MediaId>,
    val sensitiveIds: Set<MediaId>,
    val lockedFolders: Set<String>,
    val unlockedFolders: Set<String>,
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
    onSetBlurSensitive: (Boolean) -> Unit,
    onProtectFolder: (String, CharArray) -> Result<Unit>,
    onUnlockFolder: (String, CharArray) -> Boolean,
    onLockFolder: (String) -> Unit,
    onRemoveFolderProtection: (String, CharArray) -> Boolean,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    when {
        !state.permissionGranted -> PermissionScreen(onRequestPermission)
        state.assets.isEmpty() && state.scanState is ScanState.Scanning ->
            LoadingScreen(state.scanState)
        state.assets.isEmpty() && state.scanState is ScanState.Error ->
            ErrorScreen(state.scanState.message, onRefresh)
        state.assets.isEmpty() && state.scanState is ScanState.Complete ->
            EmptyScreen(onRefresh)
        else -> GalleryBrowser(
            state = state,
            onRefresh = onRefresh,
            onSetSort = onSetSort,
            onSetGridColumns = onSetGridColumns,
            onSetBlurSensitive = onSetBlurSensitive,
            onProtectFolder = onProtectFolder,
            onUnlockFolder = onUnlockFolder,
            onLockFolder = onLockFolder,
            onRemoveFolderProtection = onRemoveFolderProtection,
            onOpenAsset = onOpenAsset,
        )
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) = CenteredColumn {
    Text("Foto Xplorr needs access to your photos to build a local gallery.")
    Button(onClick = onRequestPermission) { Text("Choose photos") }
}

@Composable
private fun LoadingScreen(state: ScanState.Scanning) = CenteredColumn {
    CircularProgressIndicator()
    Text(if (state.discovered > 0) "${state.scanned} / ${state.discovered}" else "Scanning")
}

@Composable
private fun ErrorScreen(message: String, onRefresh: () -> Unit) = CenteredColumn {
    Text(message)
    Button(onClick = onRefresh) { Text("Retry") }
}

@Composable
private fun EmptyScreen(onRefresh: () -> Unit) = CenteredColumn {
    Text("No photos found")
    Button(onClick = onRefresh) { Text("Scan again") }
}

private enum class PasswordAction { PROTECT, UNLOCK, REMOVE }

@Composable
private fun GalleryBrowser(
    state: GalleryUiState,
    onRefresh: () -> Unit,
    onSetSort: (GallerySort) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
    onProtectFolder: (String, CharArray) -> Result<Unit>,
    onUnlockFolder: (String, CharArray) -> Boolean,
    onLockFolder: (String) -> Unit,
    onRemoveFolderProtection: (String, CharArray) -> Boolean,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    var section by remember { mutableStateOf(GallerySection.PHOTOS) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var passwordFolder by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val visible = visibleAssets(
        assets = state.assets,
        favoriteIds = state.favoriteIds,
        section = section,
        selectedAlbum = selectedAlbum,
        query = query,
        sort = state.preferences.sort,
        lockedFolders = state.lockedFolders,
        unlockedFolders = state.unlockedFolders,
    )
    val albums = buildAlbumSummaries(state.assets, query)
    val activeAlbum = selectedAlbum
    val activeAlbumProtected = activeAlbum != null && activeAlbum in state.lockedFolders
    val activeAlbumUnlocked = activeAlbum != null && activeAlbum in state.unlockedFolders

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            title = activeAlbum ?: "Foto Xplorr",
            subtitle = when {
                activeAlbumProtected && !activeAlbumUnlocked -> "Private folder"
                activeAlbum != null -> "${visible.size} photos"
                section == GallerySection.ALBUMS -> "${albums.size} albums"
                section == GallerySection.FAVORITES -> "${visible.size} favourites"
                else -> "${visible.size} photos"
            },
            action = if (activeAlbum != null) "Back" else "Refresh",
            onAction = {
                if (activeAlbum != null) selectedAlbum = null else onRefresh()
            },
        )

        if (activeAlbum != null) {
            FolderPrivacyActions(
                protected = activeAlbumProtected,
                unlocked = activeAlbumUnlocked,
                onProtect = {
                    passwordFolder = activeAlbum
                    passwordAction = PasswordAction.PROTECT
                },
                onLock = {
                    onLockFolder(activeAlbum)
                    selectedAlbum = null
                },
                onRemove = {
                    passwordFolder = activeAlbum
                    passwordAction = PasswordAction.REMOVE
                },
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

        if (section != GallerySection.ALBUMS || activeAlbum != null) {
            GalleryControls(
                preferences = state.preferences,
                onSetSort = onSetSort,
                onSetGridColumns = onSetGridColumns,
                onSetBlurSensitive = onSetBlurSensitive,
            )
        }

        when {
            activeAlbumProtected && !activeAlbumUnlocked -> CenteredColumn {
                Text("This folder is private")
                Button(onClick = {
                    passwordFolder = activeAlbum
                    passwordAction = PasswordAction.UNLOCK
                }) { Text("Unlock") }
            }

            section == GallerySection.ALBUMS && activeAlbum == null -> AlbumGrid(
                albums = albums,
                lockedFolders = state.lockedFolders,
                unlockedFolders = state.unlockedFolders,
                sensitiveIds = state.sensitiveIds,
                blurSensitive = state.preferences.blurSensitive,
                onOpenAlbum = { album ->
                    if (album.name in state.lockedFolders &&
                        album.name !in state.unlockedFolders
                    ) {
                        passwordFolder = album.name
                        passwordAction = PasswordAction.UNLOCK
                    } else {
                        selectedAlbum = album.name
                    }
                },
            )

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
                sensitiveIds = state.sensitiveIds,
                blurSensitive = state.preferences.blurSensitive,
                onOpenAsset = { asset -> onOpenAsset(asset, visible) },
            )
        }
    }

    val action = passwordAction
    val folder = passwordFolder
    if (action != null && folder != null) {
        PasswordDialog(
            title = when (action) {
                PasswordAction.PROTECT -> "Protect $folder"
                PasswordAction.UNLOCK -> "Unlock $folder"
                PasswordAction.REMOVE -> "Remove protection"
            },
            confirmLabel = when (action) {
                PasswordAction.PROTECT -> "Protect"
                PasswordAction.UNLOCK -> "Unlock"
                PasswordAction.REMOVE -> "Remove"
            },
            error = passwordError,
            onDismiss = {
                passwordAction = null
                passwordFolder = null
                passwordError = null
            },
            onConfirm = { password ->
                val success = when (action) {
                    PasswordAction.PROTECT -> onProtectFolder(folder, password).isSuccess
                    PasswordAction.UNLOCK -> onUnlockFolder(folder, password)
                    PasswordAction.REMOVE -> onRemoveFolderProtection(folder, password)
                }
                if (success) {
                    if (action == PasswordAction.UNLOCK) selectedAlbum = folder
                    passwordAction = null
                    passwordFolder = null
                    passwordError = null
                } else {
                    passwordError = if (action == PasswordAction.PROTECT) {
                        "Use at least 6 characters"
                    } else {
                        "Incorrect password"
                    }
                }
            },
        )
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = action,
            modifier = Modifier.clickable(onClick = onAction),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun FolderPrivacyActions(
    protected: Boolean,
    unlocked: Boolean,
    onProtect: () -> Unit,
    onLock: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            !protected -> Button(onClick = onProtect) { Text("Make private") }
            unlocked -> {
                Button(onClick = onLock) { Text("Lock now") }
                Button(onClick = onRemove) { Text("Remove lock") }
            }
        }
    }
}

@Composable
private fun GalleryControls(
    preferences: GalleryPreferencesState,
    onSetSort: (GallerySort) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GallerySort.entries.forEach { sort ->
            FilterChip(
                selected = preferences.sort == sort,
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
        Text("Blur sensitive")
        Switch(
            checked = preferences.blurSensitive,
            onCheckedChange = onSetBlurSensitive,
        )
        Text("Grid ${preferences.gridColumns}")
        Button(
            enabled = preferences.gridColumns > MIN_GRID_COLUMNS,
            onClick = { onSetGridColumns(preferences.gridColumns - 1) },
        ) { Text("Larger") }
        Button(
            enabled = preferences.gridColumns < MAX_GRID_COLUMNS,
            onClick = { onSetGridColumns(preferences.gridColumns + 1) },
        ) { Text("Smaller") }
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    confirmLabel: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    onConfirm(chars)
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AlbumGrid(
    albums: List<AlbumSummary>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    onOpenAlbum: (AlbumSummary) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.name }) { album ->
            val locked = album.name in lockedFolders && album.name !in unlockedFolders
            Column(
                modifier = Modifier
                    .clickable { onOpenAlbum(album) }
                    .padding(6.dp),
            ) {
                if (locked) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Private")
                    }
                } else {
                    Thumbnail(
                        asset = album.cover,
                        blur = blurSensitive && album.cover.id in sensitiveIds,
                    ) { onOpenAlbum(album) }
                }
                Text(
                    text = album.name,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 1,
                )
                Text(
                    text = if (locked) "Locked" else "${album.count} photos",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AssetGrid(
    assets: List<MediaAsset>,
    columns: Int,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    onOpenAsset: (MediaAsset) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(assets, key = { it.id.value }) { asset ->
            Thumbnail(
                asset = asset,
                blur = blurSensitive && asset.id in sensitiveIds,
            ) { onOpenAsset(asset) }
        }
    }
}

@Composable
private fun Thumbnail(
    asset: MediaAsset,
    blur: Boolean = false,
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
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blur) Modifier.blur(24.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
            if (blur) {
                Text(
                    text = "Sensitive",
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
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
