package com.fotoxplorr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryScreen
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.media.AndroidMediaStoreScanner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaIndexer
import com.fotoxplorr.app.media.ScanEvent
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.viewer.ViewerScreen
import kotlinx.coroutines.flow.collect

class FotoXplorrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FotoXplorrApp()
                }
            }
        }
    }
}

@Composable
private fun FotoXplorrActivity.FotoXplorrApp() {
    val repository = remember { SqliteMediaRepository(applicationContext) }
    val favoriteStore = remember { FavoriteStore(applicationContext) }
    val galleryPreferences = remember { GalleryPreferences(applicationContext) }
    val indexer = remember {
        MediaIndexer(
            scanner = AndroidMediaStoreScanner(contentResolver),
            repository = repository,
        )
    }

    val assets by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by favoriteStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var scanGeneration by remember { mutableStateOf(0) }
    var viewerAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var selectedAssetId by remember { mutableStateOf<MediaId?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result.values.any { it } || hasMediaPermission()
        if (permissionGranted) scanGeneration += 1
    }

    LaunchedEffect(permissionGranted, scanGeneration) {
        if (!permissionGranted) return@LaunchedEffect

        indexer.refresh().collect { event ->
            scanState = when (event) {
                is ScanEvent.Started -> ScanState.Scanning(scanned = 0, discovered = 0)
                is ScanEvent.Progress -> ScanState.Scanning(event.scanned, event.discovered)
                is ScanEvent.AssetFound -> scanState
                is ScanEvent.Completed -> ScanState.Complete(event.total)
                is ScanEvent.Failed -> ScanState.Error(event.error.message ?: "Unable to scan media")
            }
        }
    }

    val selectedIndex = selectedAssetId?.let { id ->
        viewerAssets.indexOfFirst { it.id == id }
    } ?: -1
    val activeAsset = viewerAssets.getOrNull(selectedIndex)

    if (activeAsset != null) {
        BackHandler { selectedAssetId = null }
        ViewerScreen(
            asset = activeAsset,
            position = selectedIndex + 1,
            total = viewerAssets.size,
            isFavorite = activeAsset.id in favoriteIds,
            hasPrevious = selectedIndex > 0,
            hasNext = selectedIndex < viewerAssets.lastIndex,
            onToggleFavorite = { favoriteStore.toggle(activeAsset.id) },
            onPrevious = {
                viewerAssets.getOrNull(selectedIndex - 1)?.let { selectedAssetId = it.id }
            },
            onNext = {
                viewerAssets.getOrNull(selectedIndex + 1)?.let { selectedAssetId = it.id }
            },
            onClose = { selectedAssetId = null },
        )
    } else {
        selectedAssetId = null
        GalleryScreen(
            state = GalleryUiState(
                assets = assets,
                favoriteIds = favoriteIds,
                permissionGranted = permissionGranted,
                scanState = scanState,
                preferences = preferences,
            ),
            onRequestPermission = {
                permissionLauncher.launch(requiredMediaPermissions())
            },
            onRefresh = { scanGeneration += 1 },
            onSetSort = galleryPreferences::setSort,
            onSetGridColumns = galleryPreferences::setGridColumns,
            onOpenAsset = { asset, visible ->
                viewerAssets = visible
                selectedAssetId = asset.id
            },
        )
    }
}

private fun FotoXplorrActivity.hasMediaPermission(): Boolean =
    requiredMediaPermissions().any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun requiredMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
    )

    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Int, val discovered: Int) : ScanState
    data class Complete(val total: Int) : ScanState
    data class Error(val message: String) : ScanState
}
