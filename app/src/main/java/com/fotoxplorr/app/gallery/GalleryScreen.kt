@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
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
    onSetFavorite: (Set<MediaId>, Boolean) -> Unit,
    onSetSensitive: (Set<MediaId>, Boolean) -> Unit,
    onMoveToTrash: (List<MediaAsset>) -> Unit,
    onRestore: (List<MediaAsset>) -> Unit,
    onDeletePermanently: (List<MediaAsset>) -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    when {
        !state.permissionGranted -> CenteredColumn {
            Text("Foto Xplorr needs access to your photos to build a local gallery.")
            Button(onClick = onRequestPermission) { Text("Choose photos") }
        }
        state.assets.isEmpty() && state.scanState is ScanState.Scanning -> CenteredColumn {
            CircularProgressIndicator()
            Text("Scanning")
        }
        state.assets.isEmpty() && state.scanState is ScanState.Error -> CenteredColumn {
            Text(state.scanState.message)
            Button(onClick = onRefresh) { Text("Retry") }
        }
        state.assets.isEmpty() -> CenteredColumn {
            Text("No photos found")
            Button(onClick = onRefresh) { Text("Scan again") }
        }
        else -> GalleryBrowser(
            state, onRefresh, onSetSort, onSetGridColumns, onSetBlurSensitive,
            onProtectFolder, onUnlockFolder, onLockFolder, onRemoveFolderProtection,
            onSetFavorite, onSetSensitive, onMoveToTrash, onRestore,
            onDeletePermanently, onOpenAsset,
        )
    }
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
    onSetFavorite: (Set<MediaId>, Boolean) -> Unit,
    onSetSensitive: (Set<MediaId>, Boolean) -> Unit,
    onMoveToTrash: (List<MediaAsset>) -> Unit,
    onRestore: (List<MediaAsset>) -> Unit,
    onDeletePermanently: (List<MediaAsset>) -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
) {
    var section by remember { mutableStateOf(GallerySection.PHOTOS) }
    var selectedAlbumKey by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(GallerySelection()) }
    var passwordAction by remember { mutableStateOf<PasswordAction?>(null) }
    var passwordFolderKey by remember { mutableStateOf<String?>(null) }
    var passwordFolderName by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val visible = visibleAssets(
        assets = state.assets,
        favoriteIds = state.favoriteIds,
        section = section,
        selectedAlbum = selectedAlbumKey,
        query = query,
        sort = state.preferences.sort,
        lockedFolders = state.lockedFolders,
        unlockedFolders = state.unlockedFolders,
    )
    val visibleIds = visible.mapTo(linkedSetOf()) { it.id }
    val selectedAssets = visible.filter { it.id in selection.selectedIds }
    val albums = buildAlbumSummaries(state.assets, query)
    val activeAlbum = albums.firstOrNull { it.key == selectedAlbumKey }
    val protected = selectedAlbumKey != null && selectedAlbumKey in state.lockedFolders
    val unlocked = selectedAlbumKey != null && selectedAlbumKey in state.unlockedFolders
    val inTrash = section == GallerySection.TRASH

    LaunchedEffect(visibleIds) { selection = selection.retainAvailable(visibleIds) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            SelectionBar(
                count = selection.count,
                allVisibleSelected = selection.count == visible.size,
                inTrash = inTrash,
                canUseSystemTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                favoriteAction = bulkMarkAction(selection.selectedIds, state.favoriteIds),
                sensitiveAction = bulkMarkAction(selection.selectedIds, state.sensitiveIds),
                onClose = { selection = selection.clear() },
                onSelectAll = { selection = selection.selectAll(visibleIds) },
                onFavorite = {
                    onSetFavorite(selection.selectedIds, bulkMarkAction(selection.selectedIds, state.favoriteIds) == BulkMarkAction.MARK)
                    selection = selection.clear()
                },
                onSensitive = {
                    onSetSensitive(selection.selectedIds, bulkMarkAction(selection.selectedIds, state.sensitiveIds) == BulkMarkAction.MARK)
                    selection = selection.clear()
                },
                onTrash = { onMoveToTrash(selectedAssets); selection = selection.clear() },
                onRestore = { onRestore(selectedAssets); selection = selection.clear() },
                onDelete = { onDeletePermanently(selectedAssets); selection = selection.clear() },
            )
        } else {
            Header(
                title = activeAlbum?.name ?: if (inTrash) "Recycle Bin" else "Foto Xplorr",
                subtitle = when {
                    protected && !unlocked -> "Private folder"
                    activeAlbum != null -> "${visible.size} photos"
                    inTrash -> "${visible.size} items · deletion is manual only"
                    section == GallerySection.ALBUMS -> "${albums.size} albums"
                    section == GallerySection.FAVORITES -> "${visible.size} favourites"
                    else -> "${visible.size} photos"
                },
                action = if (activeAlbum != null) "Back" else "Refresh",
                onAction = { if (activeAlbum != null) selectedAlbumKey = null else onRefresh() },
            )
        }

        if (!selection.isActive && activeAlbum != null) {
            FolderPrivacyActions(
                protected = protected,
                unlocked = unlocked,
                onProtect = {
                    passwordFolderKey = activeAlbum.key
                    passwordFolderName = activeAlbum.name
                    passwordAction = PasswordAction.PROTECT
                },
                onLock = { onLockFolder(activeAlbum.key); selectedAlbumKey = null },
                onRemove = {
                    passwordFolderKey = activeAlbum.key
                    passwordFolderName = activeAlbum.name
                    passwordAction = PasswordAction.REMOVE
                },
            )
        }

        if (!selection.isActive) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                singleLine = true,
                label = { Text(if (inTrash) "Search recycle bin" else "Search photos and albums") },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GallerySection.entries.forEach { candidate ->
                    FilterChip(
                        selected = section == candidate,
                        onClick = {
                            section = candidate
                            selectedAlbumKey = null
                            selection = selection.clear()
                        },
                        label = { Text(candidate.label()) },
                    )
                }
            }
            if (section != GallerySection.ALBUMS || activeAlbum != null) {
                GalleryControls(state.preferences, onSetSort, onSetGridColumns, onSetBlurSensitive)
            }
        }

        when {
            protected && !unlocked -> CenteredColumn {
                Text("This folder is private")
                Button(onClick = {
                    passwordFolderKey = activeAlbum?.key
                    passwordFolderName = activeAlbum?.name
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
                    if (album.key in state.lockedFolders && album.key !in state.unlockedFolders) {
                        passwordFolderKey = album.key
                        passwordFolderName = album.name
                        passwordAction = PasswordAction.UNLOCK
                    } else selectedAlbumKey = album.key
                },
            )
            visible.isEmpty() -> CenteredColumn {
                Text(
                    when {
                        query.isNotBlank() -> "No matches"
                        inTrash -> "Recycle Bin is empty"
                        else -> "No photos here"
                    },
                )
            }
            else -> AssetGrid(
                assets = visible,
                columns = state.preferences.gridColumns,
                selectedIds = selection.selectedIds,
                sensitiveIds = state.sensitiveIds,
                blurSensitive = state.preferences.blurSensitive,
                onClick = { asset ->
                    if (selection.isActive) selection = selection.toggle(asset.id)
                    else onOpenAsset(asset, visible)
                },
                onLongClick = { asset -> selection = selection.toggle(asset.id) },
            )
        }
    }

    val action = passwordAction
    val folderKey = passwordFolderKey
    val folderName = passwordFolderName
    if (action != null && folderKey != null && folderName != null) {
        PasswordDialog(
            title = when (action) {
                PasswordAction.PROTECT -> "Protect $folderName"
                PasswordAction.UNLOCK -> "Unlock $folderName"
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
                passwordFolderKey = null
                passwordFolderName = null
                passwordError = null
            },
            onConfirm = { password ->
                val success = when (action) {
                    PasswordAction.PROTECT -> onProtectFolder(folderKey, password).isSuccess
                    PasswordAction.UNLOCK -> onUnlockFolder(folderKey, password)
                    PasswordAction.REMOVE -> onRemoveFolderProtection(folderKey, password)
                }
                if (success) {
                    if (action == PasswordAction.UNLOCK) selectedAlbumKey = folderKey
                    passwordAction = null
                    passwordFolderKey = null
                    passwordFolderName = null
                    passwordError = null
                } else {
                    passwordError = if (action == PasswordAction.PROTECT) "Use at least 6 characters" else "Incorrect password"
                }
            },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allVisibleSelected: Boolean,
    inTrash: Boolean,
    canUseSystemTrash: Boolean,
    favoriteAction: BulkMarkAction,
    sensitiveAction: BulkMarkAction,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onSensitive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium)
            Text("Cancel", modifier = Modifier.combinedClickable(onClick = onClose, onLongClick = onClose))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!allVisibleSelected) Button(onClick = onSelectAll) { Text("Select all") }
            if (inTrash) {
                Button(enabled = canUseSystemTrash, onClick = onRestore) { Text("Restore") }
                Button(enabled = canUseSystemTrash, onClick = onDelete) { Text("Delete permanently") }
            } else {
                Button(onClick = onFavorite) { Text(if (favoriteAction == BulkMarkAction.MARK) "Favourite" else "Unfavourite") }
                Button(onClick = onSensitive) { Text(if (sensitiveAction == BulkMarkAction.MARK) "Sensitive" else "Not sensitive") }
                Button(enabled = canUseSystemTrash, onClick = onTrash) { Text("Trash") }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column { Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Text(action, modifier = Modifier.combinedClickable(onClick = onAction, onLongClick = onAction), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FolderPrivacyActions(protected: Boolean, unlocked: Boolean, onProtect: () -> Unit, onLock: () -> Unit, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !protected -> Button(onClick = onProtect) { Text("Make private") }
            unlocked -> { Button(onClick = onLock) { Text("Lock now") }; Button(onClick = onRemove) { Text("Remove lock") } }
        }
    }
}

@Composable
private fun GalleryControls(preferences: GalleryPreferencesState, onSetSort: (GallerySort) -> Unit, onSetGridColumns: (Int) -> Unit, onSetBlurSensitive: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GallerySort.entries.forEach { sort -> FilterChip(selected = preferences.sort == sort, onClick = { onSetSort(sort) }, label = { Text(sort.label()) }) }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Blur sensitive")
        Switch(checked = preferences.blurSensitive, onCheckedChange = onSetBlurSensitive)
        Button(enabled = preferences.gridColumns > MIN_GRID_COLUMNS, onClick = { onSetGridColumns(preferences.gridColumns - 1) }) { Text("Larger") }
        Button(enabled = preferences.gridColumns < MAX_GRID_COLUMNS, onClick = { onSetGridColumns(preferences.gridColumns + 1) }) { Text("Smaller") }
    }
}

@Composable
private fun PasswordDialog(title: String, confirmLabel: String, error: String?, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { val chars = password.toCharArray(); password = ""; onConfirm(chars) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlbumGrid(albums: List<AlbumSummary>, lockedFolders: Set<String>, unlockedFolders: Set<String>, sensitiveIds: Set<MediaId>, blurSensitive: Boolean, onOpenAlbum: (AlbumSummary) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), modifier = Modifier.fillMaxSize()) {
        items(albums, key = { it.key }) { album ->
            val locked = album.key in lockedFolders && album.key !in unlockedFolders
            Column(modifier = Modifier.combinedClickable(onClick = { onOpenAlbum(album) }, onLongClick = { onOpenAlbum(album) }).padding(6.dp)) {
                if (locked) Box(modifier = Modifier.aspectRatio(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Private") }
                else Thumbnail(album.cover, blurSensitive && album.cover.id in sensitiveIds, false, { onOpenAlbum(album) }, { onOpenAlbum(album) })
                Text(album.name, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
                Text(if (locked) "Locked" else "${album.count} photos", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AssetGrid(assets: List<MediaAsset>, columns: Int, selectedIds: Set<MediaId>, sensitiveIds: Set<MediaId>, blurSensitive: Boolean, onClick: (MediaAsset) -> Unit, onLongClick: (MediaAsset) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize()) {
        items(assets, key = { it.id.value }) { asset ->
            Thumbnail(asset, blurSensitive && asset.id in sensitiveIds, asset.id in selectedIds, { onClick(asset) }, { onLongClick(asset) })
        }
    }
}

@Composable
private fun Thumbnail(asset: MediaAsset, blur: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<Bitmap?>(initialValue = null, asset.contentUri, asset.id) { value = loadThumbnail(resolver, asset) }
    Box(modifier = Modifier.aspectRatio(1f).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = onClick, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(asset.displayName.take(1).uppercase())
        else Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = asset.displayName, modifier = Modifier.fillMaxSize().then(if (blur) Modifier.blur(24.dp) else Modifier), contentScale = ContentScale.Crop)
        if (blur) Text("Sensitive", modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(6.dp))
        if (selected) Text("✓", modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimary)
    }
}

private fun GallerySection.label() = when (this) {
    GallerySection.PHOTOS -> "Photos"
    GallerySection.FAVORITES -> "Favourites"
    GallerySection.ALBUMS -> "Albums"
    GallerySection.TRASH -> "Trash"
}

private fun GallerySort.label() = when (this) {
    GallerySort.NEWEST -> "Newest"
    GallerySort.OLDEST -> "Oldest"
    GallerySort.NAME -> "Name"
    GallerySort.SIZE -> "Size"
}

private suspend fun loadThumbnail(resolver: ContentResolver, asset: MediaAsset): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.loadThumbnail(asset.contentUri, Size(360, 360), null)
        else MediaStore.Images.Thumbnails.getThumbnail(resolver, asset.id.value, MediaStore.Images.Thumbnails.MINI_KIND, null)
            ?: resolver.openInputStream(asset.contentUri)?.use(BitmapFactory::decodeStream)
    }.getOrNull()
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) { content() }
}
