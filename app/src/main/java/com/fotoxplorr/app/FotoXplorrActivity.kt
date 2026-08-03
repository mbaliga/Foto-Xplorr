package com.fotoxplorr.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryScreen
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.media.AndroidMediaStoreScanner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaIndexer
import com.fotoxplorr.app.media.MediaStoreChangeObserver
import com.fotoxplorr.app.media.ScanEvent
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.viewer.ViewerScreen
import kotlinx.coroutines.flow.collect

class FotoXplorrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { FotoXplorrApp() }
            }
        }
    }
}

private enum class PendingMediaOperation { TRASH, RESTORE, DELETE }

@Composable
private fun FotoXplorrActivity.FotoXplorrApp() {
    val repository = remember { SqliteMediaRepository(applicationContext) }
    val favoriteStore = remember { FavoriteStore(applicationContext) }
    val sensitiveStore = remember { SensitiveStore(applicationContext) }
    val privateFolderStore = remember { PrivateFolderStore(applicationContext) }
    val galleryPreferences = remember { GalleryPreferences(applicationContext) }
    val changeObserver = remember { MediaStoreChangeObserver(contentResolver) }
    val indexer = remember { MediaIndexer(AndroidMediaStoreScanner(contentResolver), repository) }

    val assets by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by favoriteStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val sensitiveIds by sensitiveStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedFolders by privateFolderStore.observeLockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val unlockedFolders by privateFolderStore.observeUnlockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var scanGeneration by remember { mutableStateOf(0) }
    var viewerAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var selectedAssetId by remember { mutableStateOf<MediaId?>(null) }
    var pendingOperation by remember { mutableStateOf<PendingMediaOperation?>(null) }
    var pendingOperationIds by remember { mutableStateOf<Set<MediaId>>(emptySet()) }

    val mediaOperationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val affectedIds = pendingOperationIds
        pendingOperation = null
        pendingOperationIds = emptySet()
        if (result.resultCode == Activity.RESULT_OK && affectedIds.isNotEmpty()) {
            viewerAssets = viewerAssets.filterNot { it.id in affectedIds }
            if (selectedAssetId in affectedIds) selectedAssetId = null
            scanGeneration += 1
        }
    }

    fun requestMediaOperation(items: List<MediaAsset>, operation: PendingMediaOperation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || items.isEmpty()) return
        pendingOperation = operation
        pendingOperationIds = items.mapTo(linkedSetOf()) { it.id }
        val uris = items.map { it.contentUri }
        val request = when (operation) {
            PendingMediaOperation.TRASH -> MediaStore.createTrashRequest(contentResolver, uris, true)
            PendingMediaOperation.RESTORE -> MediaStore.createTrashRequest(contentResolver, uris, false)
            PendingMediaOperation.DELETE -> MediaStore.createDeleteRequest(contentResolver, uris)
        }
        mediaOperationLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        privateFolderStore.lockAll()
        selectedAssetId = null
        viewerAssets = emptyList()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result.values.any { it } || hasMediaPermission()
        if (permissionGranted) scanGeneration += 1
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) changeObserver.changes().collect { scanGeneration += 1 }
    }

    LaunchedEffect(permissionGranted, scanGeneration) {
        if (!permissionGranted) return@LaunchedEffect
        indexer.refresh().collect { event ->
            scanState = when (event) {
                is ScanEvent.Started -> ScanState.Scanning(0, 0)
                is ScanEvent.Progress -> ScanState.Scanning(event.scanned, event.discovered)
                is ScanEvent.AssetFound -> scanState
                is ScanEvent.Completed -> ScanState.Complete(event.total)
                is ScanEvent.Failed -> ScanState.Error(event.error.message ?: "Unable to scan media")
            }
        }
    }

    val selectedIndex = selectedAssetId?.let { id -> viewerAssets.indexOfFirst { it.id == id } } ?: -1
    val activeAsset = viewerAssets.getOrNull(selectedIndex)

    LaunchedEffect(selectedAssetId, activeAsset) {
        if (selectedAssetId != null && activeAsset == null) {
            selectedAssetId = null
            viewerAssets = emptyList()
        }
    }

    if (activeAsset != null) {
        BackHandler { selectedAssetId = null; viewerAssets = emptyList() }
        ViewerScreen(
            asset = activeAsset,
            position = selectedIndex + 1,
            total = viewerAssets.size,
            isFavorite = activeAsset.id in favoriteIds,
            isSensitive = activeAsset.id in sensitiveIds,
            hasPrevious = selectedIndex > 0,
            hasNext = selectedIndex < viewerAssets.lastIndex,
            canMoveToTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            onToggleFavorite = { favoriteStore.toggle(activeAsset.id) },
            onToggleSensitive = { sensitiveStore.toggle(activeAsset.id) },
            onMoveToTrash = { requestMediaOperation(listOf(activeAsset), PendingMediaOperation.TRASH) },
            onPrevious = { viewerAssets.getOrNull(selectedIndex - 1)?.let { selectedAssetId = it.id } },
            onNext = { viewerAssets.getOrNull(selectedIndex + 1)?.let { selectedAssetId = it.id } },
            onClose = { selectedAssetId = null; viewerAssets = emptyList() },
        )
    } else {
        GalleryScreen(
            state = GalleryUiState(
                assets = assets,
                favoriteIds = favoriteIds,
                sensitiveIds = sensitiveIds,
                lockedFolders = lockedFolders,
                unlockedFolders = unlockedFolders,
                permissionGranted = permissionGranted,
                scanState = scanState,
                preferences = preferences,
            ),
            onRequestPermission = { permissionLauncher.launch(requiredMediaPermissions()) },
            onRefresh = { scanGeneration += 1 },
            onSetSort = galleryPreferences::setSort,
            onSetGridColumns = galleryPreferences::setGridColumns,
            onSetBlurSensitive = galleryPreferences::setBlurSensitive,
            onProtectFolder = privateFolderStore::protect,
            onUnlockFolder = privateFolderStore::unlock,
            onLockFolder = privateFolderStore::lock,
            onRemoveFolderProtection = privateFolderStore::removeProtection,
            onSetFavorite = favoriteStore::setFavorite,
            onSetSensitive = sensitiveStore::setSensitive,
            onMoveToTrash = { requestMediaOperation(it, PendingMediaOperation.TRASH) },
            onRestore = { requestMediaOperation(it, PendingMediaOperation.RESTORE) },
            onDeletePermanently = { requestMediaOperation(it, PendingMediaOperation.DELETE) },
            onOpenAsset = { asset, visible -> viewerAssets = visible; selectedAssetId = asset.id },
        )
    }
}

private fun FotoXplorrActivity.hasMediaPermission(): Boolean =
    requiredMediaPermissions().any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

private fun requiredMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Int, val discovered: Int) : ScanState
    data class Complete(val total: Int) : ScanState
    data class Error(val message: String) : ScanState
}
