package com.fotoxplorr.app.picker

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.LibraryRuntime
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.MediaGridScreen
import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId

/**
 * P0-20 (stretch): lets another app pick photos/videos from this library via
 * `ACTION_GET_CONTENT` or the older `ACTION_PICK`, mirroring the shape "Open with" (P0-18)
 * already established for an external caller — a focused activity of its own, read-only, never
 * touching this app's own catalogue state (favourites, tags, trash). Unlike
 * [com.fotoxplorr.app.viewer.ExternalViewerActivity] this never redirects into
 * [com.fotoxplorr.app.FotoXplorrActivity]: a caller asking this app to pick something wants a
 * result back, not this app's own UI taking over.
 *
 * The grid only ever shows [browsableAssets] (P0-01) — nothing locked, archived or sensitive is
 * ever offered to another app, regardless of this app's own "hide sensitive" preference, which is
 * about what a person sees in their own gallery, not what this app is willing to hand to a caller
 * that never agreed to that preference at all.
 */
class PhotoPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val allowMultiple = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) ?: false
        val requestedMimeType = intent?.type

        setContent {
            val galleryPreferences = remember { GalleryPreferences(applicationContext) }
            val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()
            FotoXplorrTheme(preferences) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhotoPickerScreen(
                        allowMultiple = allowMultiple,
                        requestedMimeType = requestedMimeType,
                        gridColumns = preferences.gridColumns,
                        onResult = ::finishWithSelection,
                        onCancel = ::finishCancelled,
                    )
                }
            }
        }
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * `data` is always set, for a caller that only ever reads that field the way a single-item
     * picker traditionally returns its result; `clipData` is ALSO set once there is more than one
     * uri, for a caller that reads it instead — both are populated rather than picking one, since
     * this bounded implementation cannot know in advance which convention the caller expects.
     */
    private fun finishWithSelection(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finishCancelled()
            return
        }
        val resultIntent = Intent().apply {
            data = uris.first()
            if (uris.size > 1) {
                clipData = ClipData.newUri(contentResolver, "Foto Xplorr selection", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

@Composable
private fun PhotoPickerScreen(
    allowMultiple: Boolean,
    requestedMimeType: String?,
    gridColumns: Int,
    onResult: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val runtime = remember { LibraryRuntime.get(context) }
    val libraryStore = remember { LibraryStore.get(context) }
    val sensitiveStore = remember { SensitiveStore(context.applicationContext) }
    val privateFolderStore = remember { PrivateFolderStore(context.applicationContext) }

    var permissionGranted by remember { mutableStateOf(hasPickerMediaPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = hasPickerMediaPermission(context)
        if (permissionGranted) runtime.requestScan(false)
    }
    // Fires once per composition of this screen (a fresh picker launch), not on every
    // recomposition -- Unit as the key, matching FotoXplorrActivity's own one-shot request shape.
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(pickerMediaPermissions())
    }
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        runtime.ensureChangeObserverRegistered()
        runtime.ensureInitialScan()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PickerBody(
            allowMultiple = allowMultiple,
            requestedMimeType = requestedMimeType,
            gridColumns = gridColumns,
            permissionGranted = permissionGranted,
            runtime = runtime,
            libraryStore = libraryStore,
            sensitiveStore = sensitiveStore,
            privateFolderStore = privateFolderStore,
            onResult = onResult,
            onCancel = onCancel,
            onGrantPermission = { permissionLauncher.launch(pickerMediaPermissions()) },
        )
    }
}

@Composable
private fun PickerBody(
    allowMultiple: Boolean,
    requestedMimeType: String?,
    gridColumns: Int,
    permissionGranted: Boolean,
    runtime: LibraryRuntime,
    libraryStore: LibraryStore,
    sensitiveStore: SensitiveStore,
    privateFolderStore: PrivateFolderStore,
    onResult: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    if (!permissionGranted) {
        PickerTopBar(allowMultiple = false, selectedCount = 0, onCancel = onCancel, onConfirm = null)
        PickerMessage(
            title = "Choose the photos and videos to allow",
            message = "Foto Xplorr needs access to your media library before you can pick from it.",
            actionLabel = "Grant access",
            onAction = onGrantPermission,
        )
        return
    }

    val assets by runtime.repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val libraryState by libraryStore.observe().collectAsStateWithLifecycle()
    val sensitiveIds by sensitiveStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedFolders by privateFolderStore.observeLockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val unlockedFolders by privateFolderStore.observeUnlockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())

    val pickerAssets = remember(assets, libraryState.archivedIds, sensitiveIds, lockedFolders, unlockedFolders, requestedMimeType) {
        browsableAssets(
            assets = assets,
            archivedIds = libraryState.archivedIds,
            sensitiveIds = sensitiveIds,
            lockedFolders = lockedFolders,
            unlockedFolders = unlockedFolders,
            hideSensitive = true,
        ).filter { matchesRequestedMimeType(it.mimeType, requestedMimeType) }
    }

    var selectedIds by remember { mutableStateOf(emptySet<MediaId>()) }

    fun pick(asset: MediaAsset) {
        if (!allowMultiple) {
            onResult(listOf(asset.contentUri))
        } else {
            selectedIds = if (asset.id in selectedIds) selectedIds - asset.id else selectedIds + asset.id
        }
    }

    PickerTopBar(
        allowMultiple = allowMultiple,
        selectedCount = selectedIds.size,
        onCancel = onCancel,
        onConfirm = {
            onResult(pickerAssets.filter { it.id in selectedIds }.map { it.contentUri })
        },
    )

    val scanState by runtime.scanState.collectAsStateWithLifecycle()
    if (assets.isEmpty() && scanState is ScanState.Scanning) {
        PickerMessage(title = "Loading your library", message = "Scanning local photos and videos…", progress = true)
        return
    }
    if (pickerAssets.isEmpty()) {
        PickerMessage(title = "Nothing to pick", message = "No photos or videos are available to choose from.")
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MediaGridScreen(
            assets = pickerAssets,
            columns = gridColumns,
            favoriteIds = emptySet(),
            sensitiveIds = emptySet(),
            blurSensitive = false,
            selectedIds = selectedIds,
            emptyMessage = "No photos or videos are available to choose from.",
            selectionActive = true,
            onOpen = ::pick,
            onToggleSelection = { id -> pickerAssets.firstOrNull { it.id == id }?.let(::pick) },
        )
    }
}

/** Mirrors [com.fotoxplorr.app.gallery.GalleryScreen]'s own `BrowserHeader` shape (a plain Row,
 *  not a `CenterAlignedTopAppBar`) rather than importing it, since that composable is `private`
 *  to its own file and this bar's content — a cancel action, a count, and a multi-select confirm
 *  — is different enough from the browser's own title-plus-menu header to not be worth exposing
 *  it just for this. */
@Composable
private fun PickerTopBar(
    allowMultiple: Boolean,
    selectedCount: Int,
    onCancel: () -> Unit,
    onConfirm: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
        }
        Text(
            text = if (allowMultiple && selectedCount > 0) "$selectedCount selected" else "Select a photo or video",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (allowMultiple && onConfirm != null) {
            TextButton(onClick = onConfirm, enabled = selectedCount > 0) {
                Text(if (selectedCount > 0) "Add ($selectedCount)" else "Add")
            }
        }
    }
}

@Composable
private fun PickerMessage(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    progress: Boolean = false,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (progress) CircularProgressIndicator()
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * A small, deliberate duplicate of [com.fotoxplorr.app.mediaReadPermissions] (that one is
 * `private` to `FotoXplorrActivity.kt`): this activity only ever needs the read grant, never
 * `ACCESS_MEDIA_LOCATION` (`requiredMediaPermissions`'s own addition), since a picker returns
 * URIs, not location metadata.
 */
private fun pickerMediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasPickerMediaPermission(context: Context): Boolean =
    pickerMediaPermissions().any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * The pure part of P0-20's mime scoping: whether [assetMimeType] satisfies [requested], the
 * caller's own `Intent.type` -- an image or video type-level wildcard, a bare wildcard for both,
 * blank/null for an `ACTION_PICK` caller that only set a data URI, or occasionally one exact type
 * such as `"image/gif"`. No `Uri`/`Context` dependency, so it needs no Robolectric.
 */
internal fun matchesRequestedMimeType(assetMimeType: String, requested: String?): Boolean {
    if (requested.isNullOrBlank() || requested == "*/*") return true
    if (requested.endsWith("/*")) {
        val requestedType = requested.substringBefore('/')
        return assetMimeType.substringBefore('/').equals(requestedType, ignoreCase = true)
    }
    return assetMimeType.equals(requested, ignoreCase = true)
}
