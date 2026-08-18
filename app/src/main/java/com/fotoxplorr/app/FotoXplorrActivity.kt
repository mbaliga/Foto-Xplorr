package com.fotoxplorr.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.editor.EditorScreen
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.share.SharePreparer
import com.fotoxplorr.app.share.ShareOptionsSheet
import com.fotoxplorr.app.share.ShareOptions
import com.fotoxplorr.app.share.ShareFrame
import com.fotoxplorr.app.fileops.MediaFileOperations
import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GalleryScreen
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.gallery.rememberGeoRepository
import com.fotoxplorr.app.media.AndroidMediaStoreScanner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaIndexer
import com.fotoxplorr.app.media.MediaStoreChangeObserver
import com.fotoxplorr.app.media.PrefsScanWatermark
import com.fotoxplorr.app.media.ScanEvent
import com.fotoxplorr.app.media.ScanPlan
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.recognition.RecognitionIndexer
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ViewerScreen
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.crashrecovery.CrashRecoveryStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FotoXplorrActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, per dev.aarso:crash-recovery's documented contract: if a crash was
        // captured last run, show the recovery screen instead of this activity's real
        // content and finish this instance.
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Foto Xplorr", style = CRASH_STYLE)) return
        enableEdgeToEdge()
        setContent {
            val galleryPreferences = remember { GalleryPreferences(applicationContext) }
            val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()
            FotoXplorrTheme(preferences) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FotoXplorrApp(galleryPreferences, preferences)
                }
            }
        }
    }
}

/**
 * Foto Xplorr's accent, handed to the recovery screen.
 *
 * The screen ships its own neutral paper/ink surface and only ever takes an accent from its
 * host, deliberately: a crash surface that arrives in a stranger's colours is one more thing
 * that does not look like the app the user was just in. These are the theme's VIOLET pair —
 * the app's default — as plain @ColorInt values, because crash-recovery holds no dependency on
 * Compose or on any design system and must be handed platform colours.
 */
private val CRASH_STYLE = CrashRecoveryStyle.accent(
    light = 0xFF6E49B8.toInt(),
    onLight = 0xFFFFFFFF.toInt(),
    dark = 0xFFCBB4FF.toInt(),
    onDark = 0xFF221636.toInt(),
)

/**
 * One screenshot emits several MediaStore notifications (insert, thumbnail, metadata).
 * Long enough to collapse that burst into a single delta pass, short enough that a new
 * photo still appears while the user is looking at the grid.
 */
private const val MEDIA_CHANGE_DEBOUNCE_MS = 800L

private enum class PendingMediaOperation { TRASH, RESTORE, DELETE }
private enum class PendingTreeOperation { COPY, MOVE }

@Composable
private fun FotoXplorrActivity.FotoXplorrApp(
    galleryPreferences: GalleryPreferences,
    preferences: GalleryPreferencesState,
) {
    val repository = remember { SqliteMediaRepository(applicationContext) }
    val favoriteStore = remember { FavoriteStore(applicationContext) }
    val sensitiveStore = remember { SensitiveStore(applicationContext) }
    val privateFolderStore = remember { PrivateFolderStore(applicationContext) }
    val libraryStore = remember { LibraryStore(applicationContext) }
    val fileOperations = remember { MediaFileOperations(applicationContext) }
    val sharePreparer = remember { SharePreparer(applicationContext) }
    val changeObserver = remember { MediaStoreChangeObserver(contentResolver) }
    // On-device recognition backing the Pets / People / Identity destinations. Bundled ML
    // Kit models only -- nothing here can reach the network, so personal photos never leave
    // the device (the BYOK remote-AI path stays separate and strictly opt-in).
    val recognitionStore = remember { RecognitionStore(applicationContext) }
    val recognitionIndexer = remember { RecognitionIndexer(applicationContext, recognitionStore) }
    val indexer = remember {
        MediaIndexer(
            scanner = AndroidMediaStoreScanner(contentResolver),
            repository = repository,
            watermark = PrefsScanWatermark(applicationContext),
        )
    }
    val scope = rememberCoroutineScope()
    // One geo index for the whole app: the gallery reads it for the map and compass, the viewer
    // writes hand-placed locations into it. Two instances over the same file would each hold
    // their own StateFlow and a pin dropped in the viewer would not reach the map.
    val geoRepository = rememberGeoRepository()
    val geoState by geoRepository.observe().collectAsStateWithLifecycle()

    val assets by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by favoriteStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val sensitiveIds by sensitiveStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedFolders by privateFolderStore.observeLockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val unlockedFolders by privateFolderStore.observeUnlockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val library by libraryStore.observe().collectAsStateWithLifecycle()
    val recognition by recognitionStore.observe().collectAsStateWithLifecycle()
    val recognitionProgress by recognitionStore.observeProgress().collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }

    // Rescans are REQUESTS on a conflated channel, not a LaunchedEffect key.
    //
    // This used to be `LaunchedEffect(permissionGranted, scanGeneration)` with the observer
    // bumping scanGeneration. Every MediaStore change therefore re-keyed the effect, which
    // CANCELLED the running scan and started a fresh full one — so taking a screenshot sent
    // "Indexing 3456 of 21526" back to 0, and under any churn the scan could never finish.
    // A channel decouples "something changed" from "a scan is running": requests that arrive
    // mid-scan are collapsed into one follow-up pass instead of killing the current one.
    val scanRequests = remember { Channel<Boolean>(Channel.CONFLATED) }

    var viewerAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var selectedAssetId by remember { mutableStateOf<MediaId?>(null) }
    var slideshowActive by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf<PendingMediaOperation?>(null) }
    var pendingOperationIds by remember { mutableStateOf<Set<MediaId>>(emptySet()) }
    var pendingTreeOperation by remember { mutableStateOf<PendingTreeOperation?>(null) }
    var pendingTreeItems by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var pendingRenameAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var pendingRenameName by remember { mutableStateOf<String?>(null) }
    var userMessage by remember { mutableStateOf<String?>(null) }
    var editingAsset by remember { mutableStateOf<MediaAsset?>(null) }
    // Non-null while the advanced share sheet is up; holds what is being shared.
    var pendingShare by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var recognitionGeneration by remember { mutableStateOf(0) }

    val selectedIndex = selectedAssetId?.let { id -> viewerAssets.indexOfFirst { it.id == id } } ?: -1
    val activeAsset = viewerAssets.getOrNull(selectedIndex)

    DisposableEffect(unlockedFolders.isNotEmpty()) {
        if (unlockedFolders.isNotEmpty()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val mediaOperationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val completedOperation = pendingOperation
        val affectedIds = pendingOperationIds
        pendingOperation = null
        pendingOperationIds = emptySet()

        if (result.resultCode == Activity.RESULT_OK && affectedIds.isNotEmpty()) {
            viewerAssets = viewerAssets.filterNot { it.id in affectedIds }
            if (selectedAssetId?.let(affectedIds::contains) == true) selectedAssetId = null
            slideshowActive = false

            if (completedOperation == PendingMediaOperation.DELETE) {
                favoriteStore.setFavorite(affectedIds, false)
                sensitiveStore.setSensitive(affectedIds, false)
                libraryStore.removeMissingMedia(assets.mapTo(linkedSetOf()) { it.id } - affectedIds)
            }

            userMessage = when (completedOperation) {
                PendingMediaOperation.TRASH -> "Moved to Android's system trash."
                PendingMediaOperation.RESTORE -> "Restored from trash."
                PendingMediaOperation.DELETE -> "Permanently deleted."
                null -> null
            }
            scanRequests.trySend(false)
        } else if (affectedIds.isNotEmpty()) {
            userMessage = "Android cancelled the media operation."
        }
    }

    fun requestMediaOperation(items: List<MediaAsset>, operation: PendingMediaOperation) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            userMessage = "System trash operations require Android 11 or newer. Foto Xplorr will not delete media directly."
            return
        }
        runCatching {
            pendingOperation = operation
            pendingOperationIds = items.mapTo(linkedSetOf()) { it.id }
            val uris = items.map { it.contentUri }
            val request = when (operation) {
                PendingMediaOperation.TRASH -> MediaStore.createTrashRequest(contentResolver, uris, true)
                PendingMediaOperation.RESTORE -> MediaStore.createTrashRequest(contentResolver, uris, false)
                PendingMediaOperation.DELETE -> MediaStore.createDeleteRequest(contentResolver, uris)
            }
            mediaOperationLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { error ->
            pendingOperation = null
            pendingOperationIds = emptySet()
            userMessage = error.message ?: "Unable to open Android's media confirmation."
        }
    }

    fun performPendingRename() {
        val asset = pendingRenameAsset ?: return
        val name = pendingRenameName ?: return
        scope.launch {
            val outcome = fileOperations.rename(asset, name)
            pendingRenameAsset = null
            pendingRenameName = null
            userMessage = outcome.fold(
                onSuccess = {
                    scanRequests.trySend(false)
                    "Renamed to $it."
                },
                onFailure = { it.message ?: "Android did not allow this file to be renamed." },
            )
        }
    }

    val renamePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            performPendingRename()
        } else {
            pendingRenameAsset = null
            pendingRenameName = null
            userMessage = "Android cancelled the rename request."
        }
    }

    fun requestRename(asset: MediaAsset, requestedName: String) {
        pendingRenameAsset = asset
        pendingRenameName = requestedName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))
            }.onSuccess { request ->
                renamePermissionLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build(),
                )
            }.onFailure { error ->
                pendingRenameAsset = null
                pendingRenameName = null
                userMessage = error.message ?: "Could not request rename permission."
            }
        } else {
            scope.launch {
                val outcome = fileOperations.rename(asset, requestedName)
                val error = outcome.exceptionOrNull()
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    renamePermissionLauncher.launch(
                        IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                    )
                } else {
                    pendingRenameAsset = null
                    pendingRenameName = null
                    userMessage = outcome.fold(
                        onSuccess = {
                            scanRequests.trySend(false)
                            "Renamed to $it."
                        },
                        onFailure = { it.message ?: "Android did not allow this file to be renamed." },
                    )
                }
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        val operation = pendingTreeOperation
        val items = pendingTreeItems
        pendingTreeOperation = null
        pendingTreeItems = emptyList()
        if (treeUri == null || operation == null || items.isEmpty()) return@rememberLauncherForActivityResult

        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        scope.launch {
            userMessage = "Copying ${items.size} item${if (items.size == 1) "" else "s"}…"
            val outcome = fileOperations.copyToTree(treeUri, items)
            outcome.fold(
                onSuccess = {
                    if (operation == PendingTreeOperation.MOVE) {
                        userMessage = "Copy complete. Confirm moving the originals to Android's trash."
                        requestMediaOperation(items, PendingMediaOperation.TRASH)
                    } else {
                        userMessage = "Copied ${items.size} item${if (items.size == 1) "" else "s"}."
                    }
                },
                onFailure = { error ->
                    userMessage = error.message ?: "The copy could not be completed. Originals were not changed."
                },
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result.values.any { it } || hasMediaPermission()
        if (permissionGranted) scanRequests.trySend(false)
    }

    val exportMetadataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                val root = JSONObject().apply {
                    put("schema", 1)
                    put("exportedAtMillis", System.currentTimeMillis())
                    put("library", libraryStore.exportJson())
                    put("favoriteIds", JSONArray(favoriteIds.map { it.value }))
                    put("sensitiveIds", JSONArray(sensitiveIds.map { it.value }))
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                        writer.write(root.toString(2))
                    } ?: error("Unable to open backup destination")
                }
            }
            userMessage = outcome.fold(
                onSuccess = { "Metadata backup exported." },
                onFailure = { it.message ?: "Could not export metadata." },
            )
        }
    }

    val importMetadataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Unable to open backup")
                }
                val root = JSONObject(json)
                val libraryRoot = root.optJSONObject("library") ?: root
                libraryStore.importJson(libraryRoot).getOrThrow()
                val importedFavorites = root.optJSONArray("favoriteIds").toMediaIds()
                val importedSensitive = root.optJSONArray("sensitiveIds").toMediaIds()
                favoriteStore.setFavorite(favoriteIds, false)
                favoriteStore.setFavorite(importedFavorites, true)
                sensitiveStore.setSensitive(sensitiveIds, false)
                sensitiveStore.setSensitive(importedSensitive, true)
            }
            userMessage = outcome.fold(
                onSuccess = { "Metadata backup imported." },
                onFailure = { it.message ?: "Could not import metadata." },
            )
        }
    }

    fun shareUris(uris: List<Uri>, mimeType: String, title: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }.apply {
            clipData = uris.toClipData(contentResolver, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, title)) }
            .onFailure { userMessage = "No compatible sharing app was found." }
    }

    /**
     * Prepare and hand off to the system share sheet.
     *
     * EVERY share goes through SharePreparer now, not just the one behind an opt-in menu item.
     * Metadata stripping is the default (owner, 2026-08-15), so the ordinary path is the private
     * one and the advanced sheet is where somebody deliberately chooses otherwise.
     */
    fun shareWith(items: List<MediaAsset>, options: ShareOptions) {
        if (items.isEmpty()) return
        scope.launch {
            userMessage = "Preparing ${if (items.size == 1) "your photo" else "your photos"}…"
            sharePreparer.prepare(items, options).fold(
                onSuccess = { uris ->
                    userMessage = null
                    shareUris(
                        uris = uris,
                        // A stamp frame is a PNG (it has real transparency at the perforations),
                        // so a blanket image/jpeg would misdescribe it to the receiving app.
                        mimeType = if (options.requiresRender) "image/*" else commonShareType(items),
                        title = "Share ${items.size} item${if (items.size == 1) "" else "s"}",
                    )
                },
                onFailure = { error ->
                    userMessage = error.message ?: "Could not prepare the photos to share."
                },
            )
        }
    }

    /** The plain Share action: uses the saved defaults, no sheet, one tap. */
    fun share(items: List<MediaAsset>) {
        if (items.isEmpty()) return
        shareWith(items, preferences.toShareOptions())
    }

    /** The advanced trigger: opens the options sheet above the system share sheet. */
    fun shareAdvanced(items: List<MediaAsset>) {
        if (items.isEmpty()) return
        pendingShare = items
    }

    fun openExternally(asset: MediaAsset, action: String) {
        val intent = Intent(action).apply {
            setDataAndType(asset.contentUri, asset.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(
                Intent.createChooser(
                    intent,
                    if (action == Intent.ACTION_EDIT) "Edit with" else "Open with",
                ),
            )
        }.onFailure { userMessage = "No compatible app was found." }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val privateViewerOpen = activeAsset?.let { folderIdentity(it).key.value in lockedFolders } == true
        privateFolderStore.lockAll()
        slideshowActive = false
        if (privateViewerOpen) {
            selectedAssetId = null
            viewerAssets = emptyList()
        }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        // One screenshot emits several MediaStore notifications (insert, thumbnail,
        // metadata). Debouncing collapses that burst into a single delta pass.
        changeObserver.changes()
            .debounce(MEDIA_CHANGE_DEBOUNCE_MS)
            .collect { scanRequests.trySend(false) }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        scanRequests.trySend(true) // first pass after a grant is a full one
        for (userRequested in scanRequests) {
            indexer.refresh(userRequested = userRequested).collect { event ->
                scanState = when (event) {
                    is ScanEvent.Started -> scanState.takeIf { it is ScanState.Scanning }
                        ?: ScanState.Scanning(0, 0)
                    is ScanEvent.Progress -> ScanState.Scanning(event.scanned, event.discovered)
                    is ScanEvent.AssetFound -> scanState
                    is ScanEvent.Completed -> ScanState.Complete(
                        total = event.total,
                        incremental = event.plan is ScanPlan.Delta,
                    )
                    is ScanEvent.Failed ->
                        ScanState.Error(event.error.message ?: "Unable to scan media")
                }
            }
        }
    }

    LaunchedEffect(Unit) { recognitionStore.reload() }

    // Guarded on a generation counter rather than the asset list itself, so a recomposition
    // never restarts a pass that already finished. RecognitionIndexer is itself idempotent:
    // it only visits assets whose stored result is missing or stale.
    LaunchedEffect(recognitionGeneration) {
        if (recognitionGeneration > 0 && assets.isNotEmpty()) {
            recognitionIndexer.index(assets)
        }
    }

    LaunchedEffect(selectedAssetId, activeAsset) {
        if (selectedAssetId != null && activeAsset == null) {
            selectedAssetId = null
            viewerAssets = emptyList()
            slideshowActive = false
        }
    }

    userMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { userMessage = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { userMessage = null }) { Text("OK") } },
        )
    }

    pendingShare?.let { items ->
        ShareOptionsSheet(
            // The first selected photo stands in for the batch: the preview is about the FRAME,
            // and the frame is identical across every photo in one share.
            sample = items.firstOrNull { !it.isVideo },
            initial = preferences.toShareOptions(),
            onDismiss = { pendingShare = null },
            onShare = { chosen ->
                pendingShare = null
                // Remember the choices, so a habit does not have to be re-picked every time.
                galleryPreferences.setShareFrame(chosen.frame.name)
                galleryPreferences.setShareStripMetadata(chosen.stripMetadata)
                galleryPreferences.setShareWatermark(chosen.watermark)
                chosen.seal?.let(galleryPreferences::setShareSeal)
                shareWith(items, chosen)
            },
        )
    }

    val editing = editingAsset
    if (editing != null) {
        BackHandler { editingAsset = null }
        EditorScreen(
            asset = editing,
            onClose = { editingAsset = null },
            onSaved = { message ->
                editingAsset = null
                userMessage = message
                // The copy is a new file, so the library has to learn about it.
                scanRequests.trySend(false)
            },
        )
    } else if (activeAsset != null) {
        BackHandler {
            selectedAssetId = null
            viewerAssets = emptyList()
            slideshowActive = false
        }
        ViewerScreen(
            asset = activeAsset,
            position = selectedIndex + 1,
            total = viewerAssets.size,
            isFavorite = activeAsset.id in favoriteIds,
            isSensitive = activeAsset.id in sensitiveIds,
            hasPrevious = selectedIndex > 0,
            hasNext = selectedIndex < viewerAssets.lastIndex,
            canMoveToTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            slideshowActive = slideshowActive,
            slideshowIntervalSeconds = preferences.slideshowIntervalSeconds,
            onToggleSlideshow = { slideshowActive = !slideshowActive },
            onToggleFavorite = { favoriteStore.toggle(activeAsset.id) },
            onToggleSensitive = { sensitiveStore.toggle(activeAsset.id) },
            onShare = { share(listOf(activeAsset)) },
            onEdit = { editingAsset = activeAsset },
            onOpenWith = { openExternally(activeAsset, Intent.ACTION_VIEW) },
            onMoveToTrash = { requestMediaOperation(listOf(activeAsset), PendingMediaOperation.TRASH) },
            onPrevious = {
                viewerAssets.getOrNull(selectedIndex - 1)?.let { selectedAssetId = it.id }
            },
            onNext = {
                val nextIndex = when {
                    // Shuffle only applies to a running slideshow: a manual swipe means "the
                    // next photo", and answering it with a random one would be a bug, not a
                    // setting.
                    slideshowActive && preferences.slideshowShuffle && viewerAssets.size > 1 ->
                        randomOtherIndex(viewerAssets.size, selectedIndex)
                    selectedIndex < viewerAssets.lastIndex -> selectedIndex + 1
                    slideshowActive && viewerAssets.size > 1 -> 0
                    else -> -1
                }
                viewerAssets.getOrNull(nextIndex)?.let { selectedAssetId = it.id }
            },
            onClose = {
                selectedAssetId = null
                viewerAssets = emptyList()
                slideshowActive = false
            },
            // Hand-placed location for a photo whose file carries no GPS tag. Written to Foto
            // Xplorr's own index, never into the user's file -- see setManualLocation.
            manualLatitude = geoState.metadataById[activeAsset.id]?.latitude,
            manualLongitude = geoState.metadataById[activeAsset.id]?.longitude,
            onSetLocation = { latitude, longitude ->
                scope.launch { geoRepository.setManualLocation(activeAsset.id, latitude, longitude) }
            },
            onClearLocation = {
                scope.launch { geoRepository.clearManualLocation(activeAsset.id) }
            },
            // The viewer's own settings room edits these, so it needs the value and the setter.
            blurSensitive = preferences.blurSensitive,
            keepScreenOn = preferences.keepScreenOn,
            showFilmstrip = preferences.showFilmstrip,
            slideshowShuffle = preferences.slideshowShuffle,
            loopAnimations = preferences.loopAnimations,
            autoplayVideos = preferences.autoplayVideos,
            onSetSlideshowInterval = galleryPreferences::setSlideshowInterval,
            onSetBlurSensitive = galleryPreferences::setBlurSensitive,
            onSetShowFilmstrip = galleryPreferences::setShowFilmstrip,
            onSetKeepScreenOn = galleryPreferences::setKeepScreenOn,
            onSetSlideshowShuffle = galleryPreferences::setSlideshowShuffle,
            onSetLoopAnimations = galleryPreferences::setLoopAnimations,
            onSetAutoplayVideos = galleryPreferences::setAutoplayVideos,
            relatedAssets = viewerAssets,
            onSelectAsset = { picked ->
                if (viewerAssets.any { it.id == picked.id }) {
                    selectedAssetId = picked.id
                    slideshowActive = false
                }
            },
        )
    } else {
        GalleryScreen(
            geoRepository = geoRepository,
            state = GalleryUiState(
                assets = assets,
                favoriteIds = favoriteIds,
                sensitiveIds = sensitiveIds,
                lockedFolders = lockedFolders,
                unlockedFolders = unlockedFolders,
                library = library,
                permissionGranted = permissionGranted,
                scanState = scanState,
                preferences = preferences,
                recognition = recognition,
                recognitionProgress = recognitionProgress,
            ),
            actions = GalleryActions(
                onRequestPermission = { permissionLauncher.launch(requiredMediaPermissions()) },
                onRefresh = { scanRequests.trySend(true) },
                onSetSort = galleryPreferences::setSort,
                onSetGridColumns = galleryPreferences::setGridColumns,
                onSetBlurSensitive = galleryPreferences::setBlurSensitive,
                onSetHideSensitive = galleryPreferences::setHideSensitive,
                onSetShowVideos = galleryPreferences::setShowVideos,
                onSetTimelineGrouping = galleryPreferences::setTimelineGrouping,
                onSetThemeMode = galleryPreferences::setThemeMode,
                onSetAccentPalette = galleryPreferences::setAccentPalette,
                onSetSlideshowInterval = galleryPreferences::setSlideshowInterval,
                onSetDefaultDestination = galleryPreferences::setDefaultDestination,
                onSetKeepScreenOn = galleryPreferences::setKeepScreenOn,
                onSetSlideshowShuffle = galleryPreferences::setSlideshowShuffle,
                onSetAutoplayVideos = galleryPreferences::setAutoplayVideos,
                onSetFitToTile = galleryPreferences::setFitToTile,
                onSetLoopAnimations = galleryPreferences::setLoopAnimations,
                onSetLongPressPreview = galleryPreferences::setLongPressPreview,
                onIndexRecognition = { recognitionGeneration += 1 },
                onProtectFolder = privateFolderStore::protect,
                onUnlockFolder = privateFolderStore::unlock,
                onLockFolder = privateFolderStore::lock,
                onRemoveFolderProtection = privateFolderStore::removeProtection,
                onSetFavorite = favoriteStore::setFavorite,
                onSetSensitive = sensitiveStore::setSensitive,
                onSetArchived = libraryStore::setArchived,
                onShare = ::share,
                onShareClean = ::shareAdvanced,
                onCopyToFolder = { items ->
                    pendingTreeOperation = PendingTreeOperation.COPY
                    pendingTreeItems = items
                    treeLauncher.launch(null)
                },
                onMoveToFolder = { items ->
                    pendingTreeOperation = PendingTreeOperation.MOVE
                    pendingTreeItems = items
                    treeLauncher.launch(null)
                },
                onRenameAsset = ::requestRename,
                onMoveToTrash = { requestMediaOperation(it, PendingMediaOperation.TRASH) },
                onRestore = { requestMediaOperation(it, PendingMediaOperation.RESTORE) },
                onDeletePermanently = { requestMediaOperation(it, PendingMediaOperation.DELETE) },
                onCreateCollection = { libraryStore.createCollection(it)?.id },
                onRenameCollection = { id, name -> libraryStore.renameCollection(id, name) },
                onDeleteCollection = libraryStore::deleteCollection,
                onAddToCollection = libraryStore::addToCollection,
                onRemoveFromCollection = libraryStore::removeFromCollection,
                onAddTag = libraryStore::addTag,
                onRemoveTag = libraryStore::removeTag,
                onExportMetadata = { exportMetadataLauncher.launch("foto-xplorr-metadata.json") },
                onImportMetadata = { importMetadataLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                onOpenAsset = { asset, visible ->
                    viewerAssets = visible
                    selectedAssetId = asset.id
                    slideshowActive = false
                },
                onStartSlideshow = { visible ->
                    if (visible.isNotEmpty()) {
                        viewerAssets = visible
                        selectedAssetId = visible.first().id
                        slideshowActive = true
                    }
                },
            ),
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
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun commonShareType(items: List<MediaAsset>): String = when {
    items.all { it.mimeType.startsWith("image/") } -> "image/*"
    items.all { it.mimeType.startsWith("video/") } -> "video/*"
    else -> "*/*"
}

private fun List<Uri>.toClipData(
    resolver: android.content.ContentResolver,
    label: String,
): ClipData = ClipData.newUri(resolver, label, first()).also { clip ->
    drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
}

private fun JSONArray?.toMediaIds(): Set<MediaId> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            val value = optLong(index, -1L)
            if (value >= 0L) add(MediaId(value))
        }
    }
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Int, val discovered: Int) : ScanState
    /** @param incremental true when this was a delta pass (a few changed items), not a full library scan. */
    data class Complete(val total: Int, val incremental: Boolean = false) : ScanState
    data class Error(val message: String) : ScanState
}

/**
 * A random index other than [current], for a shuffled slideshow.
 *
 * Drawing from the other [size] - 1 positions and stepping over [current] rather than retrying a
 * uniform draw: a retry loop is unbounded in the worst case, and at size 2 it would spin on the
 * one index it must not pick roughly half the time.
 */
internal fun randomOtherIndex(size: Int, current: Int): Int {
    if (size <= 1) return 0
    val drawn = kotlin.random.Random.nextInt(size - 1)
    return if (drawn >= current) drawn + 1 else drawn
}

/**
 * The saved share defaults, as the value the share pipeline actually consumes.
 *
 * Kept as an extension rather than a field on the preferences data class so that
 * `GalleryPreferencesState` stays a plain record of what is stored, and the mapping from stored
 * strings to the share package's own types lives next to the code that needs it.
 *
 * An unrecognised stored frame name falls back to NONE rather than throwing: the value comes from
 * SharedPreferences, which can outlive a rename of the enum, and a crash on start because someone
 * once picked a frame that no longer exists would be an absurd way to lose a library.
 */
private fun GalleryPreferencesState.toShareOptions(): ShareOptions = ShareOptions(
    frame = ShareFrame.entries.firstOrNull { it.name == shareFrame } ?: ShareFrame.NONE,
    stripMetadata = shareStripMetadata,
    watermark = shareWatermark,
    seal = shareSeal.takeIf { it.isNotBlank() },
)
