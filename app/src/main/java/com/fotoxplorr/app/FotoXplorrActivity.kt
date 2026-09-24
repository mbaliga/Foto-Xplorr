package com.fotoxplorr.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.audio.AudioAsset
import com.fotoxplorr.app.audio.AudioConversionWriter
import com.fotoxplorr.app.audio.AudioPlayerScreen
import com.fotoxplorr.app.editor.EditedCopyWriter
import com.fotoxplorr.app.editor.EditorScreen
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.share.PreparedItem
import com.fotoxplorr.app.share.SharePreparer
import com.fotoxplorr.app.share.ZipExporter
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
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.curate.AutoCurationPass
import com.fotoxplorr.app.metadata.GpsCoordinate
import com.fotoxplorr.app.metadata.MetadataEdit
import com.fotoxplorr.app.metadata.MetadataWriter
import com.fotoxplorr.app.metadata.isMetadataWritable
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.video.VideoConversionWriter
import com.fotoxplorr.app.videoeditor.VideoEditorScreen
import com.fotoxplorr.app.recognition.RecognitionIndexer
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ExternalViewerActivity
import com.fotoxplorr.app.viewer.ViewerScreen
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.crashrecovery.CrashRecoveryStyle
import kotlinx.coroutines.Dispatchers
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

    companion object {
        /**
         * A `MediaId.value` to open straight into the viewer for, with every normal catalogue
         * action available (P0-18). Set by `viewer.ExternalViewerActivity` when an incoming
         * `ACTION_VIEW` URI turns out to already be a catalogued photo -- redirecting here rather
         * than reimplementing favourite/sensitive/trash/tags/caption/location a second time is
         * what "open that asset with all its normal actions instead" (the brief's own words)
         * actually means: the real actions, not a lookalike set of them.
         */
        const val EXTRA_OPEN_MEDIA_ID = "com.fotoxplorr.app.EXTRA_OPEN_MEDIA_ID"
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

// MEDIA_CHANGE_DEBOUNCE_MS moved to LibraryRuntime.kt (P0-09) along with the change-observer
// subscription it debounces.

/**
 * Saves a [MediaId] across process death as the plain [Long] it wraps (P0-09 item 6) --
 * `rememberSaveable`'s default Saveable-types support has no idea what to do with an inline value
 * class. [NO_MEDIA_ID] stands in for null: every real [MediaId] comes from MediaStore's own `_ID`
 * column, which is never negative.
 */
private val MediaIdSaver = Saver<MediaId?, Long>(
    save = { it?.value ?: NO_MEDIA_ID },
    restore = { if (it == NO_MEDIA_ID) null else MediaId(it) },
)

private const val NO_MEDIA_ID = -1L

private enum class PendingMediaOperation { TRASH, RESTORE, DELETE }
private enum class PendingTreeOperation { COPY, MOVE }

@Composable
private fun FotoXplorrActivity.FotoXplorrApp(
    galleryPreferences: GalleryPreferences,
    preferences: GalleryPreferencesState,
) {
    // P0-09: the one process-wide scanning engine. Was rebuilt from scratch, and re-triggered a
    // full scan, on every single Activity recreation -- see LibraryRuntime's own KDoc.
    val runtime = remember { LibraryRuntime.get(applicationContext) }
    val repository = runtime.repository
    val audioRepository = runtime.audioRepository
    val favoriteStore = remember { FavoriteStore(applicationContext) }
    val sensitiveStore = remember { SensitiveStore(applicationContext) }
    val privateFolderStore = remember { PrivateFolderStore(applicationContext) }
    val libraryStore = remember { LibraryStore.get(applicationContext) }
    val fileOperations = remember { MediaFileOperations(applicationContext) }
    val metadataWriter = remember { MetadataWriter(applicationContext) }
    val editedCopyWriter = remember { EditedCopyWriter(applicationContext) }
    val videoConversionWriter = remember { VideoConversionWriter(applicationContext) }
    val sharePreparer = remember { SharePreparer(applicationContext) }
    val zipExporter = remember { ZipExporter(applicationContext) }
    // On-device recognition backing the Pets / People / Identity destinations. Bundled ML
    // Kit models only -- nothing here can reach the network, so personal photos never leave
    // the device (the BYOK remote-AI path stays separate and strictly opt-in).
    val recognitionStore = remember { RecognitionStore(applicationContext) }
    val recognitionIndexer = remember { RecognitionIndexer(applicationContext, recognitionStore) }
    val audioConversionWriter = remember { AudioConversionWriter(applicationContext) }
    val scope = rememberCoroutineScope()
    // One geo index for the whole app: the gallery reads it for the map and compass, the viewer
    // writes hand-placed locations into it. Two instances over the same file would each hold
    // their own StateFlow and a pin dropped in the viewer would not reach the map.
    val geoRepository = rememberGeoRepository()
    val geoState by geoRepository.observe().collectAsStateWithLifecycle()

    val assets by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val audioAssets by audioRepository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteIds by favoriteStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val sensitiveIds by sensitiveStore.observe().collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedFolders by privateFolderStore.observeLockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val unlockedFolders by privateFolderStore.observeUnlockedFolders().collectAsStateWithLifecycle(initialValue = emptySet())
    val library by libraryStore.observe().collectAsStateWithLifecycle()
    val recognition by recognitionStore.observe().collectAsStateWithLifecycle()
    val recognitionProgress by recognitionStore.observeProgress().collectAsStateWithLifecycle()
    // P0-14: which ids actually animate, kept fresh by LibraryRuntime itself after every scan --
    // this Activity only ever reads it, the same read-only-view shape scanState already has.
    val animatedIds by runtime.animationIndex.observeAnimatedIds().collectAsStateWithLifecycle(initialValue = emptySet())

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var partialMediaAccess by remember { mutableStateOf(hasPartialMediaAccess(applicationContext)) }
    // Independent of permissionGranted above and requested separately, only once the user opens
    // the Audio library (P0-10) -- see hasAudioPermission's own KDoc for why.
    var audioPermissionGranted by remember { mutableStateOf(hasAudioPermission(applicationContext)) }
    // P0-09: the scan itself, and the request channel driving it, now live entirely in
    // LibraryRuntime -- this is a read-only view onto its process-wide state, so recomposing (or
    // recreating) this Activity never resets progress or re-triggers a full scan.
    val scanState by runtime.scanState.collectAsStateWithLifecycle(initialValue = ScanState.Idle)

    // P0-09 item 6: process death survives via just the ids, rebuilt into real MediaAssets from
    // the repository (once it has loaded) rather than saving MediaAsset itself, which isn't
    // Parcelable/Bundle-saveable and would need to be if it were. setViewerAssets is the one
    // write path so no call site needs to know the list is really stored as a LongArray.
    var viewerAssetIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    val viewerAssetsById = assets.associateBy { asset -> asset.id.value }
    val viewerAssets: List<MediaAsset> = viewerAssetIds.map { id -> viewerAssetsById[id] }.filterNotNull()
    fun setViewerAssets(list: List<MediaAsset>) {
        viewerAssetIds = list.map { it.id.value }.toLongArray()
    }
    var selectedAssetId by rememberSaveable(stateSaver = MediaIdSaver) { mutableStateOf<MediaId?>(null) }

    // P0-18: ExternalViewerActivity redirects here, rather than reimplementing the catalogue
    // actions itself, when an incoming "Open with" URI turns out to already be a photo this app
    // has scanned. `assets` is empty on the very first composition and fills in asynchronously
    // (P0-09), so this waits for a real match rather than firing once and giving up; the
    // `rememberSaveable` guard is what stops a LATER background rescan (which also changes
    // `assets`) from reopening the viewer out from under someone who has since closed it.
    var consumedOpenMediaIdExtra by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(assets) {
        if (consumedOpenMediaIdExtra) return@LaunchedEffect
        val requestedId = intent.getLongExtra(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, NO_MEDIA_ID)
        if (requestedId == NO_MEDIA_ID) {
            consumedOpenMediaIdExtra = true
            return@LaunchedEffect
        }
        val match = assets.firstOrNull { it.id.value == requestedId } ?: return@LaunchedEffect
        setViewerAssets(listOf(match))
        selectedAssetId = match.id
        consumedOpenMediaIdExtra = true
    }

    var audioQueue by remember { mutableStateOf<List<AudioAsset>>(emptyList()) }
    var selectedAudioAssetId by remember { mutableStateOf<MediaId?>(null) }
    var convertingAudioId by remember { mutableStateOf<MediaId?>(null) }
    var slideshowActive by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf<PendingMediaOperation?>(null) }
    var pendingOperationIds by remember { mutableStateOf<Set<MediaId>>(emptySet()) }
    var pendingTreeOperation by remember { mutableStateOf<PendingTreeOperation?>(null) }
    var pendingTreeItems by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var pendingRenameAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var pendingRenameName by remember { mutableStateOf<String?>(null) }
    var pendingMetadataAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var pendingMetadataEdit by remember { mutableStateOf<MetadataEdit?>(null) }
    // P0-08 item 6: true from the moment requestMetadataWrite actually starts a write (or its
    // consent round trip) until it lands, so a second edit arriving in that window (the detail
    // room commits creator and copyright as two independent field commits, not one) is queued and
    // merged rather than clobbering pendingMetadataEdit outright -- seedable to be non-null and
    // still have the *other* field's edit silently discarded, exactly the loss the brief names.
    var metadataWriteInFlight by remember { mutableStateOf(false) }
    var queuedMetadataAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var queuedMetadataEdit by remember { mutableStateOf<MetadataEdit?>(null) }
    var pendingOverwriteAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var pendingOverwriteBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Which video (if any) is mid-conversion, so the actions room can disable a second tap and
    // show "Converting..." rather than starting the same video through the pipeline twice.
    var convertingVideoId by remember { mutableStateOf<MediaId?>(null) }
    // Bumped on every metadata write that actually lands, so ViewerScreen's own EXIF/XMP cache
    // (which only reloads when the asset itself changes) knows to re-read the file it just wrote
    // into. See that parameter's own doc for why nothing else already covers this.
    var metadataRevision by remember { mutableStateOf(0) }
    var userMessage by remember { mutableStateOf<String?>(null) }
    // P0-09 item 6: only the id survives process death; the asset itself is re-resolved from the
    // repository below, same shape as viewerAssets/activeAsset just above.
    var editingAssetId by rememberSaveable(stateSaver = MediaIdSaver) { mutableStateOf<MediaId?>(null) }
    val editingAsset = editingAssetId?.let { id -> assets.firstOrNull { it.id == id } }
    // Text handed over from the viewer's "Search inside this photo" card, on its way to the
    // grid's search field. Lives here rather than in either screen because the two are siblings
    // under this composable, not nested -- the viewer has no way to reach into the browser's own
    // state, so the instruction has to pass through their nearest common ancestor. Cleared by
    // GalleryActions.onPendingSearchConsumed as soon as the browser has acted on it; see
    // GalleryUiState.pendingSearch for why it is a one-shot instruction and not the field itself.
    var pendingSearch by remember { mutableStateOf<String?>(null) }
    // Non-null while the advanced share sheet is up; holds what is being shared.
    var pendingShare by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var recognitionGeneration by remember { mutableStateOf(0) }

    val selectedIndex = selectedAssetId?.let { id -> viewerAssets.indexOfFirst { it.id == id } } ?: -1
    val activeAsset = viewerAssets.getOrNull(selectedIndex)
    val activeAudioAsset = selectedAudioAssetId?.let { id -> audioQueue.firstOrNull { it.id == id } }

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
            setViewerAssets(viewerAssets.filterNot { it.id in affectedIds })
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
            runtime.requestScan(false)
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
                    runtime.requestScan(false)
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
                            runtime.requestScan(false)
                            "Renamed to $it."
                        },
                        onFailure = { it.message ?: "Android did not allow this file to be renamed." },
                    )
                }
            }
        }
    }

    // Declared with `lateinit`-style forward reference via a plain var so requestMetadataWrite and
    // finishMetadataWrite can call each other (a queued edit's follow-up write is just another
    // requestMetadataWrite call) without a circular local-function definition order problem.
    lateinit var requestMetadataWrite: (MediaAsset, MetadataEdit) -> Unit

    fun finishMetadataWrite() {
        pendingMetadataAsset = null
        pendingMetadataEdit = null
        metadataWriteInFlight = false
        val nextAsset = queuedMetadataAsset
        val nextEdit = queuedMetadataEdit
        queuedMetadataAsset = null
        queuedMetadataEdit = null
        // Fires the queued edit as a brand new write, exactly as if the caller had called
        // requestMetadataWrite itself just now -- it goes through the same isEmpty check and the
        // same three-tier consent dance, since a write finishing is not consent granted in advance
        // for a DIFFERENT write.
        if (nextAsset != null && nextEdit != null) requestMetadataWrite(nextAsset, nextEdit)
    }

    fun performPendingMetadataWrite() {
        val asset = pendingMetadataAsset ?: return
        val edit = pendingMetadataEdit ?: return
        scope.launch {
            val outcome = metadataWriter.write(asset, edit)
            // Both a scan request (the write changed the file's own size/modified-date, which
            // InformationBlock shows) and the revision bump (so ViewerScreen's own EXIF/XMP cache
            // for THIS still-open photo re-reads immediately, rather than waiting on that scan).
            outcome.onSuccess { metadataRevision++; runtime.requestScan(false) }
                .onFailure { userMessage = it.message ?: "Android did not allow this file's metadata to be updated." }
            finishMetadataWrite()
        }
    }

    // Mirrors renamePermissionLauncher/requestRename immediately above, field for field: the same
    // three-tier consent dance (direct write pre-Q, RecoverableSecurityException on Q, an upfront
    // MediaStore.createWriteRequest on R+) applies to any write into a file this app did not
    // itself create, and a caption/rating/keyword edit is exactly that kind of write.
    val metadataPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            performPendingMetadataWrite()
        } else {
            userMessage = "Android cancelled the metadata update."
            finishMetadataWrite()
        }
    }

    requestMetadataWrite = requestMetadataWrite@{ asset, edit ->
        if (edit.isEmpty) return@requestMetadataWrite
        if (metadataWriteInFlight) {
            // P0-08 item 6: a write for some asset is already running (or waiting on consent) --
            // never start a second one concurrently, which is how the old code lost an edit
            // (PhotoDetailRoom.kt commits creator and copyright as two independent field commits,
            // each calling this). Queue it, merged with anything already queued for the SAME
            // asset (MetadataEdit.merge: later non-null fields win, keywords union); a queued edit
            // for a DIFFERENT asset simply replaces the queue slot, since only one write is ever
            // in flight and this app has no multi-asset batch metadata editor today.
            queuedMetadataEdit = if (queuedMetadataAsset == asset) queuedMetadataEdit?.merge(edit) ?: edit else edit
            queuedMetadataAsset = asset
            return@requestMetadataWrite
        }
        metadataWriteInFlight = true
        // P0-08 item 1: checked BEFORE anything else -- including before pendingMetadataAsset/Edit
        // are even set -- so a HEIC/RAW edit never opens the system consent screen only to fail
        // once it's granted. The whole dispatch below is inside this one coroutine (rather than
        // just the check) so the R+ branch's own createWriteRequest call happens after the check
        // resolves, not racing it.
        scope.launch {
            if (!isMetadataWritable(contentResolver, asset)) {
                userMessage = "Foto Xplorr can't write metadata into ${asset.mimeType} files yet"
                finishMetadataWrite()
                return@launch
            }
            pendingMetadataAsset = asset
            pendingMetadataEdit = edit
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))
                }.onSuccess { request ->
                    metadataPermissionLauncher.launch(
                        IntentSenderRequest.Builder(request.intentSender).build(),
                    )
                }.onFailure { error ->
                    userMessage = error.message ?: "Could not request metadata write permission."
                    finishMetadataWrite()
                }
                return@launch
            }
            run {
                val outcome = metadataWriter.write(asset, edit)
                val error = outcome.exceptionOrNull()
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    metadataPermissionLauncher.launch(
                        IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                    )
                } else {
                    outcome.onSuccess { metadataRevision++; runtime.requestScan(false) }
                        .onFailure { userMessage = it.message ?: "Android did not allow this file's metadata to be updated." }
                    finishMetadataWrite()
                }
            }
        }
    }

    fun finishOverwrite(outcome: Result<Unit>) {
        pendingOverwriteAsset = null
        pendingOverwriteBitmap = null
        userMessage = outcome.fold(
            onSuccess = { "Replaced the original." },
            onFailure = { it.message ?: "Could not replace the original photo." },
        )
        // The bytes at this Uri changed size and content; the library's cached size/date-modified
        // (and any thumbnail) are stale until MediaStore is asked to look again.
        runtime.requestScan(false)
    }

    // Mirrors metadataPermissionLauncher/requestMetadataWrite immediately above, field for field:
    // the same three-tier consent dance applies here too -- EditedCopyWriter.overwrite touches a
    // file this app did not create, exactly like a metadata write does.
    val overwritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val asset = pendingOverwriteAsset
        val bitmap = pendingOverwriteBitmap
        if (result.resultCode == Activity.RESULT_OK && asset != null && bitmap != null) {
            scope.launch { finishOverwrite(editedCopyWriter.overwrite(asset, bitmap)) }
        } else {
            pendingOverwriteAsset = null
            pendingOverwriteBitmap = null
            userMessage = "Android cancelled replacing the original — nothing was saved."
        }
    }

    // EditorScreen already rendered the edit at full size and decided OVERWRITE is even possible
    // for this asset's format (see EditorScreen's own canOverwriteInPlace) before calling this --
    // this function's only job is the consent dance and the actual write, the same division of
    // labour requestMetadataWrite already has with MetadataWriter.
    fun requestOverwrite(asset: MediaAsset, bitmap: Bitmap) {
        pendingOverwriteAsset = asset
        pendingOverwriteBitmap = bitmap
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))
            }.onSuccess { request ->
                overwritePermissionLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build(),
                )
            }.onFailure { error ->
                pendingOverwriteAsset = null
                pendingOverwriteBitmap = null
                userMessage = error.message ?: "Could not request permission to replace the original."
            }
        } else {
            scope.launch {
                val outcome = editedCopyWriter.overwrite(asset, bitmap)
                val error = outcome.exceptionOrNull()
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    overwritePermissionLauncher.launch(
                        IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                    )
                } else {
                    finishOverwrite(outcome)
                }
            }
        }
    }

    // No permission dance needed here, unlike rename/metadata/overwrite above: a conversion
    // always inserts a brand-new MediaStore row this app itself owns (see
    // VideoConversionWriter's own doc for why it never touches the source video), and creating a
    // new row this app owns needs no per-file consent the way writing into someone else's already
    // needs.
    fun requestVideoConversion(asset: MediaAsset) {
        if (convertingVideoId != null) return
        convertingVideoId = asset.id
        scope.launch {
            val outcome = videoConversionWriter.convertToH264Mp4(asset)
            convertingVideoId = null
            outcome.onSuccess { runtime.requestScan(false) }
            userMessage = outcome.fold(
                onSuccess = { "Converted to MP4." },
                onFailure = { it.message ?: "Could not convert this video." },
            )
        }
    }

    // AudioConversionWriter's own doc explains why this too needs no permission dance: like a
    // video conversion, it inserts a brand-new MediaStore row this app owns rather than touching
    // the source file.
    fun requestAudioConversion(asset: AudioAsset) {
        if (convertingAudioId != null) return
        convertingAudioId = asset.id
        scope.launch {
            val outcome = audioConversionWriter.convertToAac(asset)
            convertingAudioId = null
            outcome.onSuccess { runtime.requestAudioScan(false) }
            userMessage = outcome.fold(
                onSuccess = { "Converted to AAC (M4A)." },
                onFailure = { it.message ?: "Could not convert this audio file." },
            )
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
    ) {
        // ACCESS_MEDIA_LOCATION can be granted independently; it must never make the
        // gallery believe that image/video access itself was granted.
        permissionGranted = hasMediaPermission()
        partialMediaAccess = hasPartialMediaAccess(applicationContext)
        if (permissionGranted) runtime.requestScan(false)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        audioPermissionGranted = hasAudioPermission(applicationContext)
        if (audioPermissionGranted) runtime.ensureInitialAudioScan()
    }

    // Permission scope can change in system Settings while the process is backgrounded.
    // Re-read it on return so the Settings affordance and scan state never describe a
    // stale grant.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val hadPermission = permissionGranted
        permissionGranted = hasMediaPermission()
        partialMediaAccess = hasPartialMediaAccess(applicationContext)
        if (!hadPermission && permissionGranted) runtime.requestScan(false)

        val hadAudioPermission = audioPermissionGranted
        audioPermissionGranted = hasAudioPermission(applicationContext)
        if (!hadAudioPermission && audioPermissionGranted) runtime.ensureInitialAudioScan()
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
     *
     * P0-04: [SharePreparer.prepare] reports a [PreparedItem] per item rather than failing the
     * whole share the first time one photo can't be safely prepared -- everything that succeeded
     * still goes out, and [userMessage] names what did not, rather than the sharer silently
     * getting nothing with no explanation.
     */
    fun shareWith(items: List<MediaAsset>, options: ShareOptions) {
        if (items.isEmpty()) return
        scope.launch {
            userMessage = "Preparing ${if (items.size == 1) "your photo" else "your photos"}…"
            sharePreparer.prepare(items, options, animatedIds = animatedIds).fold(
                onSuccess = { prepared ->
                    val ready = prepared.filterIsInstance<PreparedItem.Ready>()
                    val failed = prepared.filterIsInstance<PreparedItem.Failed>()
                    if (ready.isEmpty()) {
                        userMessage = if (failed.size == 1) {
                            failed.first().reason
                        } else {
                            "None of the ${failed.size} photos could be prepared to share."
                        }
                        return@fold
                    }
                    userMessage = unpreparedShareItemsMessage(failed)
                    shareUris(
                        uris = ready.map { it.uri },
                        // Derived from what was actually produced, not the original assets or a
                        // render flag: a stamp frame comes out as PNG, an unsupported source may
                        // have been re-encoded to JPEG, and a raw copy keeps its own MIME type.
                        mimeType = commonShareType(ready.map { it.mimeType }),
                        title = "Share ${ready.size} item${if (ready.size == 1) "" else "s"}",
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
        val chooser = Intent.createChooser(
            intent,
            if (action == Intent.ACTION_EDIT) "Edit with" else "Open with",
        ).apply {
            // P0-18: never offer this app back to itself here. Without this, ACTION_VIEW's
            // chooser would list ExternalViewerActivity, which -- for a catalogued asset -- just
            // redirects straight back to this exact activity/photo, a round trip through a
            // picker that answers nothing: the person is already looking at this photo, in this
            // app, right now.
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@FotoXplorrApp, ExternalViewerActivity::class.java)),
            )
        }
        runCatching { startActivity(chooser) }.onFailure { userMessage = "No compatible app was found." }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val privateViewerOpen = activeAsset?.let { folderIdentity(it).key.value in lockedFolders } == true
        privateFolderStore.lockAll()
        slideshowActive = false
        if (privateViewerOpen) {
            selectedAssetId = null
            setViewerAssets(emptyList())
        }
    }

    // P0-09: scanning itself, its request channels, the change-observer subscription and the
    // scan-state reducer all now live in LibraryRuntime, a process-wide singleton -- this effect
    // only has to ask it to make sure the once-per-process work has actually started. Re-running
    // on every Activity recreation is fine (and, since permissionGranted itself starts fresh
    // each time, unavoidable): ensureInitialScan/ensureChangeObserverRegistered are each no-ops
    // after their first real call.
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        runtime.ensureChangeObserverRegistered()
        runtime.ensureInitialScan()
    }

    // Kept separate from the effect above (P0-10): audio permission is granted independently, and
    // often much later -- the Audio library is unreachable until permissionGranted is already
    // true (see GalleryScreen's own top-level `when`), so this fires on its own key rather than
    // waiting for another change to permissionGranted that may never come again.
    LaunchedEffect(audioPermissionGranted) {
        if (audioPermissionGranted) runtime.ensureInitialAudioScan()
    }

    LaunchedEffect(Unit) { recognitionStore.reload() }

    // Guarded on a generation counter rather than the asset list itself, so a recomposition
    // never restarts a pass that already finished. RecognitionIndexer is itself idempotent:
    // it only visits assets whose stored result is missing or stale.
    LaunchedEffect(recognitionGeneration) {
        if (recognitionGeneration > 0 && assets.isNotEmpty()) {
            recognitionIndexer.index(assets)
            // Curate immediately after, on the results that pass just wrote, rather than leaving
            // it for the next background wake -- someone who taps "index my library" and then
            // opens a photo expects to see what it found, not to see it tomorrow morning. The
            // pass is idempotent, so the background run doing this again costs nothing.
            AutoCurationPass(libraryStore).run(assets, recognitionStore.observe().value, lockedFolders)
        }
    }

    LaunchedEffect(selectedAssetId, activeAsset) {
        if (selectedAssetId != null && activeAsset == null) {
            selectedAssetId = null
            setViewerAssets(emptyList())
            slideshowActive = false
        }
    }

    LaunchedEffect(selectedAudioAssetId, activeAudioAsset) {
        if (selectedAudioAssetId != null && activeAudioAsset == null) {
            selectedAudioAssetId = null
            audioQueue = emptyList()
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
    if (editing != null && editing.isVideo) {
        // A separate screen, not a branch inside EditorScreen: trim/speed share almost nothing
        // with EditRecipe's crop/colour/rotate model (see VideoEditRecipe's own doc), and forcing
        // both asset types through one composable would mean every future photo-only or
        // video-only tool growing an "if (asset.isVideo)" branch somewhere inside it.
        BackHandler { editingAssetId = null }
        VideoEditorScreen(
            asset = editing,
            onClose = { editingAssetId = null },
            onSaved = { message ->
                editingAssetId = null
                userMessage = message
                runtime.requestScan(false)
            },
        )
    } else if (editing != null) {
        BackHandler { editingAssetId = null }
        EditorScreen(
            asset = editing,
            saveMode = preferences.editorSaveMode,
            onSetSaveMode = galleryPreferences::setEditorSaveMode,
            onClose = { editingAssetId = null },
            onSaved = { message ->
                editingAssetId = null
                userMessage = message
                // The copy is a new file, so the library has to learn about it.
                runtime.requestScan(false)
            },
            onOverwrite = ::requestOverwrite,
        )
    } else if (activeAsset != null) {
        BackHandler {
            selectedAssetId = null
            setViewerAssets(emptyList())
            slideshowActive = false
        }
        ViewerScreen(
            asset = activeAsset,
            position = selectedIndex + 1,
            total = viewerAssets.size,
            isFavorite = activeAsset.id in favoriteIds,
            isSensitive = activeAsset.id in sensitiveIds,
            // Text the offline pass already read out of this photo. Looked up per asset rather
            // than passed wholesale: the index holds every photo's text, and the viewer needs
            // exactly one photo's worth.
            liveTextBlocks = recognition.textByMedia[activeAsset.id].orEmpty(),
            // What this photo carries in the library, as opposed to what its file says. Read
            // from the same LibraryState the grid already renders, so a tag added in the grid's
            // selection menu is on the photo the moment the viewer opens over it.
            tags = library.tagsFor(activeAsset.id),
            autoTags = library.autoTagsFor(activeAsset.id),
            onRemoveTag = { tag -> libraryStore.removeTag(setOf(activeAsset.id), tag) },
            caption = library.captionFor(activeAsset.id),
            captionIsMachineWritten = library.isMachineCaption(activeAsset.id),
            onSetCaption = { text -> libraryStore.setCaption(activeAsset.id, text) },
            // MetadataWriter is built on ExifInterface, which this app relies on only for still
            // images (see readImageExifDetails's own early return for asset.isVideo) -- so these
            // stay null for a video rather than offering fields a tap on would just fail against.
            // Video's own metadata story is Phase 3/4, not this one.
            onSetRating = if (activeAsset.isVideo) null else { rating: Int ->
                requestMetadataWrite(activeAsset, MetadataEdit(rating = rating))
            },
            onSetCreator = if (activeAsset.isVideo) null else { creator: String ->
                requestMetadataWrite(activeAsset, MetadataEdit(creator = creator))
            },
            onSetCopyright = if (activeAsset.isVideo) null else { copyright: String ->
                requestMetadataWrite(activeAsset, MetadataEdit(copyright = copyright))
            },
            onEmbedKeywords = if (activeAsset.isVideo) null else {
                {
                    val tags = library.tagsFor(activeAsset.id)
                    if (tags.isNotEmpty()) {
                        requestMetadataWrite(activeAsset, MetadataEdit(keywordsToAdd = tags.toList()))
                    }
                }
            },
            metadataRevision = metadataRevision,
            // The Search pill in the details room. Closing the viewer is done HERE rather than
            // inside ViewerScreen (see that parameter's own doc): the results land in the grid,
            // and leaving the photo open on top of them would put the answer behind the question.
            onSearchLibrary = { text ->
                pendingSearch = text
                selectedAssetId = null
                setViewerAssets(emptyList())
                slideshowActive = false
            },
            hasPrevious = selectedIndex > 0,
            hasNext = selectedIndex < viewerAssets.lastIndex,
            canMoveToTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            slideshowActive = slideshowActive,
            slideshowIntervalSeconds = preferences.slideshowIntervalSeconds,
            onToggleSlideshow = { slideshowActive = !slideshowActive },
            onToggleFavorite = { favoriteStore.toggle(activeAsset.id) },
            onToggleSensitive = { sensitiveStore.toggle(activeAsset.id) },
            onShare = { share(listOf(activeAsset)) },
            onEdit = { editingAssetId = activeAsset?.id },
            onOpenWith = { openExternally(activeAsset, Intent.ACTION_VIEW) },
            onMoveToTrash = { requestMediaOperation(listOf(activeAsset), PendingMediaOperation.TRASH) },
            isConvertingToMp4 = convertingVideoId == activeAsset.id,
            onConvertToMp4 = if (activeAsset.isVideo) {
                { requestVideoConversion(activeAsset) }
            } else {
                null
            },
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
                setViewerAssets(emptyList())
                slideshowActive = false
            },
            // Hand-placed location for a photo whose file carries no GPS tag. Written to Foto
            // Xplorr's own index, never into the user's file -- see setManualLocation.
            manualLatitude = geoState.metadataById[activeAsset.id]?.latitude,
            manualLongitude = geoState.metadataById[activeAsset.id]?.longitude,
            onSetLocation = { latitude, longitude ->
                scope.launch { geoRepository.setManualLocation(activeAsset.id, latitude, longitude) }
                // PlaceBlock only ever offers this picker when the file itself carries no GPS
                // tag (see its own KDoc), so writing this coordinate into the file's EXIF here
                // can only ever be FILLING IN an absent location, never overwriting a real one.
                // That is what turns a hand-placed pin from an app-only fact into one that
                // travels with the file when it is copied, shared, or opened elsewhere. Skipped
                // for a video for the same reason as the fields just above: MetadataWriter is an
                // ExifInterface-on-still-images story, not (yet) a video one.
                if (!activeAsset.isVideo) {
                    requestMetadataWrite(activeAsset, MetadataEdit(setLocation = GpsCoordinate(latitude, longitude)))
                }
            },
            onClearLocation = {
                scope.launch { geoRepository.clearManualLocation(activeAsset.id) }
                if (!activeAsset.isVideo) {
                    requestMetadataWrite(activeAsset, MetadataEdit(clearLocation = true))
                }
            },
            // The viewer's own settings room edits these, so it needs the value and the setter.
            blurSensitive = preferences.blurSensitive,
            keepScreenOn = preferences.keepScreenOn,
            showFilmstrip = preferences.showFilmstrip,
            slideshowShuffle = preferences.slideshowShuffle,
            loopAnimations = preferences.loopAnimations,
            animated = activeAsset.id in animatedIds,
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
    } else if (activeAudioAsset != null) {
        BackHandler {
            selectedAudioAssetId = null
            audioQueue = emptyList()
        }
        AudioPlayerScreen(
            asset = activeAudioAsset,
            queue = audioQueue,
            onClose = {
                selectedAudioAssetId = null
                audioQueue = emptyList()
            },
            onSelect = { picked -> selectedAudioAssetId = picked.id },
            isConverting = convertingAudioId == activeAudioAsset.id,
            onConvertToAac = { requestAudioConversion(activeAudioAsset) },
        )
    } else {
        GalleryScreen(
            geoRepository = geoRepository,
            audioAssets = audioAssets,
            onPlayAudio = { asset, queue ->
                audioQueue = queue
                selectedAudioAssetId = asset.id
            },
            audioPermissionGranted = audioPermissionGranted,
            onRequestAudioPermission = { audioPermissionLauncher.launch(requiredAudioPermissions()) },
            state = GalleryUiState(
                assets = assets,
                favoriteIds = favoriteIds,
                sensitiveIds = sensitiveIds,
                lockedFolders = lockedFolders,
                unlockedFolders = unlockedFolders,
                library = library,
                permissionGranted = permissionGranted,
                partialMediaAccess = partialMediaAccess,
                scanState = scanState,
                preferences = preferences,
                recognition = recognition,
                recognitionProgress = recognitionProgress,
                animatedIds = animatedIds,
                pendingSearch = pendingSearch,
            ),
            actions = GalleryActions(
                onRequestPermission = { permissionLauncher.launch(requiredMediaPermissions()) },
                onRefresh = { runtime.requestScan(true) },
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
                onExportZip = { items ->
                scope.launch {
                    val result = zipExporter.export(items, preferences.toShareOptions().stripMetadata)
                    result.fold(
                        onSuccess = { export ->
                            userMessage = unpreparedShareItemsMessage(export.failed)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, export.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, null))
                        },
                        onFailure = { userMessage = it.message ?: "Could not build the archive" },
                    )
                }
            },
            onExportMetadata = { exportMetadataLauncher.launch("foto-xplorr-metadata.json") },
                onImportMetadata = { importMetadataLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                onOpenAsset = { asset, visible ->
                    setViewerAssets(visible)
                    selectedAssetId = asset.id
                    slideshowActive = false
                },
                onStartSlideshow = { visible ->
                    if (visible.isNotEmpty()) {
                        setViewerAssets(visible)
                        selectedAssetId = visible.first().id
                        slideshowActive = true
                    }
                },
                onPendingSearchConsumed = { pendingSearch = null },
                onRejectArchiveSuggestions = libraryStore::rejectArchiveSuggestions,
                onManageSelectedMedia = { permissionLauncher.launch(requiredMediaPermissions()) },
            ),
        )
    }
}

private fun FotoXplorrActivity.hasMediaPermission(): Boolean =
    mediaReadPermissions().any { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

// hasPartialMediaAccess moved to LibraryRuntime.kt (P0-09): it is no longer only ever called from
// this Activity, since LibraryRuntime's own scan loop needs it too.

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        mediaReadPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION
    } else {
        mediaReadPermissions()
    }

private fun mediaReadPermissions(): Array<String> = when {
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

/** [requiredMediaPermissions]'s own shape, for the Audio library's separate grant (P0-10) --
 *  see [hasAudioPermission]'s KDoc for why this is never folded into [mediaReadPermissions]. */
private fun requiredAudioPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

internal fun commonShareType(mimeTypes: List<String>): String = when {
    mimeTypes.all { it.startsWith("image/") } -> "image/*"
    mimeTypes.all { it.startsWith("video/") } -> "video/*"
    else -> "*/*"
}

/** @return null when nothing failed, else a message naming the first failed item and, when there
 * is more than one, how many others -- for [FotoXplorrActivity.FotoXplorrApp.shareWith] and, since
 * P0-06, the zip export path (`onExportZip`), which reports its own skipped items the same way. */
internal fun unpreparedShareItemsMessage(failed: List<PreparedItem.Failed>): String? {
    if (failed.isEmpty()) return null
    val first = failed.first().asset.displayName
    return if (failed.size == 1) {
        "\"$first\" couldn't be prepared without its location and wasn't shared."
    } else {
        "\"$first\" and ${failed.size - 1} more couldn't be prepared without their location and weren't shared."
    }
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

// ScanState moved to LibraryRuntime.kt (P0-09): it is no longer only ever produced from this
// file's own scan loop, since LibraryRuntime's scan loop is what produces it now.

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
