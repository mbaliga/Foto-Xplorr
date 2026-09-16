package com.fotoxplorr.app

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ClipData
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fotoxplorr.app.audio.AndroidAudioMediaStoreScanner
import com.fotoxplorr.app.audio.AudioAsset
import com.fotoxplorr.app.audio.AudioConversionWriter
import com.fotoxplorr.app.audio.AudioIndexer
import com.fotoxplorr.app.audio.AudioPlayerScreen
import com.fotoxplorr.app.audio.SqliteAudioRepository
import com.fotoxplorr.app.audio.PrefsAudioScanWatermark
import com.fotoxplorr.app.editor.EditedCopyWriter
import com.fotoxplorr.app.editor.EditorScreen
import com.fotoxplorr.app.favorites.FavoriteStore
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
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobForegroundService
import com.fotoxplorr.app.jobs.humanMessage
import com.fotoxplorr.app.media.AndroidMediaStoreScanner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaIndexer
import com.fotoxplorr.app.media.MediaStoreChangeObserver
import com.fotoxplorr.app.media.PrefsScanWatermark
import com.fotoxplorr.app.media.ScanEvent
import com.fotoxplorr.app.media.ScanPlan
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.curate.AutoCurationPass
import com.fotoxplorr.app.metadata.GpsCoordinate
import com.fotoxplorr.app.metadata.MetadataEdit
import com.fotoxplorr.app.metadata.MetadataWriter
import com.fotoxplorr.app.openwith.ExternalAssetLoader
import com.fotoxplorr.app.openwith.IncomingIntentRoute
import com.fotoxplorr.app.openwith.classifyIncomingIntent
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.shell.PendingMediaOperation
import com.fotoxplorr.app.shell.PendingTreeOperation
import com.fotoxplorr.app.video.VideoConversionWriter
import com.fotoxplorr.app.video.VideoExportOptions
import com.fotoxplorr.app.videoeditor.VideoEditorScreen
import com.fotoxplorr.app.recognition.RecognitionIndexer
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ViewerScreen
import dev.aarso.crashrecovery.CrashRecovery
import dev.aarso.crashrecovery.CrashRecoveryStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class FotoXplorrActivity : ComponentActivity() {
    // Bumped by onNewIntent so the composition below reprocesses `intent`: singleTop launch mode
    // (needed so a second "open with" while the app is already running does not start a second
    // Activity instance) means that second intent arrives here rather than through a fresh
    // onCreate, and nothing else would tell a running composition to look at it again.
    //
    // internal, not private: FotoXplorrApp (below, in this same file) reads it as a
    // LaunchedEffect key, and Kotlin's "private is file-visible" rule only extends to private
    // TOP-LEVEL declarations, not to private members of a class read from a same-file top-level
    // function.
    internal var incomingIntentGeneration by mutableIntStateOf(0)
        private set

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntentGeneration++
    }

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
    val viewModel: AppStateViewModel = viewModel()
    val externalAssetLoader = remember { ExternalAssetLoader(applicationContext) }
    val repository = remember { SqliteMediaRepository(applicationContext) }
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
    // Audio's own repository/indexer, parallel to the photo/video ones above rather than folded
    // into them -- see AudioAsset's own doc for why the two asset types stay apart everywhere.
    val audioRepository = remember { SqliteAudioRepository(applicationContext) }
    val audioIndexer = remember {
        AudioIndexer(
            scanner = AndroidAudioMediaStoreScanner(contentResolver),
            repository = audioRepository,
            watermark = PrefsAudioScanWatermark(applicationContext),
        )
    }
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

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var partialMediaAccess by remember { mutableStateOf(hasPartialMediaAccess()) }
    var audioPermissionGranted by remember { mutableStateOf(hasAudioPermission()) }
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
    // Audio's own conflated request channel -- kept separate from `scanRequests` above rather than
    // shared, since a Kotlin Channel hands each element to exactly one collector: two independent
    // `for` loops over the same channel would each only see some of the requests, not all of them.
    val audioScanRequests = remember { Channel<Boolean>(Channel.CONFLATED) }

    // Every one of these used to be `remember { mutableStateOf(...) }`, which a rotation (no
    // ViewModel, no `configChanges`) tore down along with the whole composable tree -- closing
    // the viewer/editor/audio player, forgetting the current selection, and cancelling whatever
    // `rememberCoroutineScope()` job happened to be mid-flight. Delegating to a property on
    // `viewModel` (`by viewModel::field`) keeps every downstream read/write in this function
    // exactly as it was; only the storage moved to survive the Activity recreation that rotation
    // (and, for the three ids `viewModel` mirrors into `SavedStateHandle`, process death) causes.
    var viewerAssets by viewModel::viewerAssets
    var selectedAssetId by viewModel::selectedAssetId
    var audioQueue by viewModel::audioQueue
    var selectedAudioAssetId by viewModel::selectedAudioAssetId
    var convertingAudioId by viewModel::convertingAudioId
    var slideshowActive by viewModel::slideshowActive
    var pendingOperation by viewModel::pendingOperation
    var pendingOperationIds by viewModel::pendingOperationIds
    var pendingTreeOperation by viewModel::pendingTreeOperation
    var pendingTreeItems by viewModel::pendingTreeItems
    var pendingRenameAsset by viewModel::pendingRenameAsset
    var pendingRenameName by viewModel::pendingRenameName
    var pendingMetadataAsset by viewModel::pendingMetadataAsset
    var pendingMetadataEdit by viewModel::pendingMetadataEdit
    var pendingOverwriteAsset by viewModel::pendingOverwriteAsset
    var pendingOverwriteBitmap by viewModel::pendingOverwriteBitmap
    // True once Android's own consent sheet for replacing the original has been declined, so the
    // editor (still open -- see `editingAsset` below) can offer "Save a copy instead" using the
    // very bitmap already rendered, instead of the edit simply being lost.
    var overwriteDeclined by viewModel::overwriteDeclined
    // Which video (if any) is mid-conversion, so the actions room can disable a second tap and
    // show "Converting..." rather than starting the same video through the pipeline twice.
    var convertingVideoId by viewModel::convertingVideoId
    // Bumped on every metadata write that actually lands, so ViewerScreen's own EXIF/XMP cache
    // (which only reloads when the asset itself changes) knows to re-read the file it just wrote
    // into. See that parameter's own doc for why nothing else already covers this.
    var metadataRevision by viewModel::metadataRevision
    // AlertDialog is now reserved for a failure that needs a decision -- see `transientNotice`
    // just below for the non-modal "it worked" case this used to also carry.
    var userMessage by viewModel::userMessage
    // A short-lived, non-modal notice for a result that does not need a decision (a rename that
    // landed, a copy that finished): shown briefly and dismissed on its own, or on a tap if
    // `onOpen` is set. Cleared by the LaunchedEffect below three seconds after it is set.
    var transientNotice by remember { mutableStateOf<TransientNotice?>(null) }
    LaunchedEffect(transientNotice) {
        if (transientNotice != null) {
            delay(TRANSIENT_NOTICE_MILLIS)
            transientNotice = null
        }
    }
    // Collects every job's outcome once, wherever it lands -- a rotation loses and regains this
    // LaunchedEffect, but jobRunner.outcomes is a buffered SharedFlow (see its own doc), so an
    // outcome delivered mid-rotation is not lost, only delayed until the new instance subscribes.
    LaunchedEffect(Unit) {
        viewModel.jobRunner.outcomes.collect { outcome ->
            outcome.result.fold(
                onSuccess = { value ->
                    // A blank message is the convention a job uses to say "I finished, but a
                    // later step in the same flow (the trash consent sheet, for a move) is the
                    // one that actually reports the outcome" -- see the tree-copy job below.
                    val message = (value as? String)?.takeIf { it.isNotBlank() } ?: "${outcome.title} finished."
                    if (value !is String || value.isNotBlank()) transientNotice = TransientNotice(message)
                },
                onFailure = { error ->
                    if (error !is kotlinx.coroutines.CancellationException) {
                        userMessage = humanMessage(error, "${outcome.title} could not finish.")
                    }
                },
            )
        }
    }
    val editingAsset = viewModel.editingAsset
    // Text handed over from the viewer's "Search inside this photo" card, on its way to the
    // grid's search field. Lives here rather than in either screen because the two are siblings
    // under this composable, not nested -- the viewer has no way to reach into the browser's own
    // state, so the instruction has to pass through their nearest common ancestor. Cleared by
    // GalleryActions.onPendingSearchConsumed as soon as the browser has acted on it; see
    // GalleryUiState.pendingSearch for why it is a one-shot instruction and not the field itself.
    var pendingSearch by viewModel::pendingSearch
    // Non-null while the advanced share sheet is up; holds what is being shared.
    var pendingShare by viewModel::pendingShare
    var recognitionGeneration by viewModel::recognitionGeneration
    // Non-null while the viewer's own rename dialog is up. Plain `remember`, not the ViewModel:
    // it is a short-lived confirmation, exactly the kind of thing a rotation losing is an
    // acceptable, ordinary trade -- unlike the navigation state above, whose whole point is not
    // being lost.
    var viewerRenameTarget by remember { mutableStateOf<MediaAsset?>(null) }

    // Starts (or stops) the notification that keeps a conversion/export/copy-move visible and
    // cancellable while the app may not be in the foreground -- see JobForegroundService's own
    // doc. Read as a State so this effect only re-runs on the true/false edge, not on every
    // progress tick the same list of jobs produces.
    val runningJobs by viewModel.jobRunner.jobs.collectAsStateWithLifecycle()
    val anyJobRunning = runningJobs.isNotEmpty()
    LaunchedEffect(anyJobRunning) {
        if (anyJobRunning) JobForegroundService.start(applicationContext) else JobForegroundService.stop(applicationContext)
    }

    val selectedIndex = selectedAssetId?.let { id -> viewerAssets.indexOfFirst { it.id == id } } ?: -1
    // Read by id off the LIVE repository, not the snapshot `viewerAssets` was built from: a
    // successful overwrite changes this same file's size and modified-date, and MediaImage's own
    // cache key (see that composable's own doc) already busts on exactly that change -- but
    // PhotoDetailRoom's Size/Modified fields would still show the stale numbers from whenever the
    // viewer was opened unless the asset object itself is refreshed too. Falls back to the
    // `viewerAssets` entry for an external asset (see MediaAsset.isExternal), which the
    // repository does not and should not know about.
    val activeAsset = viewerAssets.getOrNull(selectedIndex)?.let { fromList ->
        if (fromList.isExternal) fromList else assets.firstOrNull { it.id == fromList.id } ?: fromList
    }
    val activeAudioAsset = selectedAudioAssetId?.let { id -> audioQueue.firstOrNull { it.id == id } }

    // Open-with / share-to: classifies whatever this Activity was started or re-delivered with
    // (see onNewIntent's own doc on incomingIntentGeneration) and routes it straight to the
    // viewer, the editor, or the audio player. Keyed on the generation counter, not on `intent`
    // itself -- `Intent` is not stable equality for a Compose key across the SAME intent being
    // re-read after `setIntent`, and the counter is exactly "a new intent arrived" as a value.
    LaunchedEffect(incomingIntentGeneration) {
        processIncomingIntent(
            intent = intent,
            loader = externalAssetLoader,
            onViewMedia = { loaded ->
                viewerAssets = loaded
                selectedAssetId = loaded.first().id
                slideshowActive = false
            },
            onViewAudio = { audio ->
                audioQueue = listOf(audio)
                selectedAudioAssetId = audio.id
            },
            onEditImage = { asset -> viewModel.setEditingAsset(asset) },
        )
    }

    // Process death: `selectedAssetId`/`editingAssetId`/`selectedAudioAssetId` themselves survived
    // (SavedStateHandle), but `viewerAssets`/`editingAsset`/`audioQueue` did not -- a fresh
    // ViewModel instance starts those empty. Rebuilds each one, by id, the FIRST time the
    // relevant repository has something to look the id up in; a no-op on an ordinary rotation,
    // where `viewerAssets` etc. were never emptied in the first place.
    LaunchedEffect(assets) {
        if (assets.isEmpty()) return@LaunchedEffect
        viewModel.selectedAssetId?.let { id ->
            if (viewerAssets.isEmpty()) {
                assets.firstOrNull { it.id == id }?.let { restored -> viewerAssets = listOf(restored) }
            }
        }
        viewModel.editingAssetId?.let { id ->
            if (viewModel.editingAsset == null) {
                assets.firstOrNull { it.id == id }?.let { restored -> viewModel.setEditingAsset(restored) }
            }
        }
    }
    LaunchedEffect(audioAssets) {
        if (audioAssets.isEmpty()) return@LaunchedEffect
        viewModel.selectedAudioAssetId?.let { id ->
            if (audioQueue.isEmpty()) {
                audioAssets.firstOrNull { it.id == id }?.let { restored -> audioQueue = listOf(restored) }
            }
        }
    }

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

            when (completedOperation) {
                PendingMediaOperation.TRASH -> transientNotice = TransientNotice("Moved to Android's system trash.")
                PendingMediaOperation.RESTORE -> transientNotice = TransientNotice("Restored from trash.")
                PendingMediaOperation.DELETE -> transientNotice = TransientNotice("Permanently deleted.")
                null -> Unit
            }
            scanRequests.trySend(false)
        } else if (affectedIds.isNotEmpty()) {
            transientNotice = TransientNotice("Android cancelled the media operation.")
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
            userMessage = humanMessage(error, "Unable to open Android's media confirmation.")
        }
    }

    fun performPendingRename() {
        val asset = pendingRenameAsset ?: return
        val name = pendingRenameName ?: return
        scope.launch {
            val outcome = fileOperations.rename(asset, name)
            pendingRenameAsset = null
            pendingRenameName = null
            outcome.fold(
                onSuccess = {
                    scanRequests.trySend(false)
                    transientNotice = TransientNotice("Renamed to $it.")
                },
                onFailure = { error -> userMessage = humanMessage(error, "Android did not allow this file to be renamed.") },
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
            transientNotice = TransientNotice("Android cancelled the rename request.")
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
                userMessage = humanMessage(error, "Could not request rename permission.")
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
                    outcome.fold(
                        onSuccess = {
                            scanRequests.trySend(false)
                            transientNotice = TransientNotice("Renamed to $it.")
                        },
                        onFailure = { renameError ->
                            userMessage = humanMessage(renameError, "Android did not allow this file to be renamed.")
                        },
                    )
                }
            }
        }
    }

    fun performPendingMetadataWrite() {
        val asset = pendingMetadataAsset ?: return
        val edit = pendingMetadataEdit ?: return
        scope.launch {
            val outcome = metadataWriter.write(asset, edit)
            pendingMetadataAsset = null
            pendingMetadataEdit = null
            // Both a scan request (the write changed the file's own size/modified-date, which
            // InformationBlock shows) and the revision bump (so ViewerScreen's own EXIF/XMP cache
            // for THIS still-open photo re-reads immediately, rather than waiting on that scan).
            outcome.onSuccess { metadataRevision++; scanRequests.trySend(false) }
                .onFailure { error -> userMessage = humanMessage(error, "Android did not allow this file's metadata to be updated.") }
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
            pendingMetadataAsset = null
            pendingMetadataEdit = null
            transientNotice = TransientNotice("Android cancelled the metadata update.")
        }
    }

    fun requestMetadataWrite(asset: MediaAsset, edit: MetadataEdit) {
        if (edit.isEmpty) return
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
                pendingMetadataAsset = null
                pendingMetadataEdit = null
                userMessage = humanMessage(error, "Could not request metadata write permission.")
            }
        } else {
            scope.launch {
                val outcome = metadataWriter.write(asset, edit)
                val error = outcome.exceptionOrNull()
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    metadataPermissionLauncher.launch(
                        IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                    )
                } else {
                    pendingMetadataAsset = null
                    pendingMetadataEdit = null
                    outcome.onSuccess { metadataRevision++; scanRequests.trySend(false) }
                        .onFailure { error -> userMessage = humanMessage(error, "Android did not allow this file's metadata to be updated.") }
                }
            }
        }
    }

    /**
     * The result of asking Android to let this app replace [asset]'s own bytes.
     *
     * On success the editor closes (via `viewModel.setEditingAsset(null)`) and a transient notice
     * appears -- no more blocking "Replaced the original." dialog. On failure the editor is left
     * exactly as it was: still open, still holding [pendingOverwriteBitmap], with
     * [overwriteDeclined] set so the editor overlay below can offer "Save a copy instead" rather
     * than the edit being silently lost. This is the fix for `requestOverwrite` used to run
     * AFTER `EditorScreen` had already called `onClose()` on the OVERWRITE path -- see
     * `EditorScreen.performSave`'s own doc, which agent C is changing so that call no longer
     * happens; this function's job is unaffected by which of the two behaviours EditorScreen
     * currently has, because it never itself closes the editor except on a genuine success.
     */
    fun finishOverwrite(outcome: Result<Unit>) {
        outcome.fold(
            onSuccess = {
                pendingOverwriteAsset = null
                pendingOverwriteBitmap = null
                overwriteDeclined = false
                viewModel.setEditingAsset(null)
                transientNotice = TransientNotice("Replaced the original.")
                // The bytes at this Uri changed size and content; the library's cached
                // size/date-modified (and any thumbnail) are stale until MediaStore looks again.
                scanRequests.trySend(false)
            },
            onFailure = { overwriteDeclined = true },
        )
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
            // Declined, not merely cancelled -- see finishOverwrite's own doc for why this is
            // NOT treated the way a trash/rename cancellation is (nulling the pending state and
            // reporting failure): the edit itself must survive this.
            overwriteDeclined = true
        }
    }

    // EditorScreen already rendered the edit at full size and decided OVERWRITE is even possible
    // for this asset's format (see EditorScreen's own canOverwriteInPlace) before calling this --
    // this function's only job is the consent dance and the actual write, the same division of
    // labour requestMetadataWrite already has with MetadataWriter.
    fun requestOverwrite(asset: MediaAsset, bitmap: Bitmap) {
        pendingOverwriteAsset = asset
        pendingOverwriteBitmap = bitmap
        overwriteDeclined = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))
            }.onSuccess { request ->
                overwritePermissionLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build(),
                )
            }.onFailure { overwriteDeclined = true }
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

    /**
     * Opens the viewer on the asset the library indexes at [uri], once it has learned about it.
     *
     * This is the hook `EditorScreen`'s own `onSavedUri` should be wired to once agent C adds it
     * (see that screen's own KDoc on `onSaved`/`onOverwrite` for the shape) -- until then it is
     * called only from [saveDeclinedOverwriteAsCopy] below, which already has the new Uri in hand
     * because it calls [EditedCopyWriter.save] directly rather than through EditorScreen's own
     * COPY path.
     */
    fun openViewerForSavedUri(uri: Uri) {
        scope.launch {
            scanRequests.trySend(false)
            // The row EditedCopyWriter just inserted needs one delta scan to reach `assets`; a
            // short settle covers the gap between that scan landing and this composable's own
            // collection of it, without this function polling in a loop.
            delay(SAVED_COPY_SETTLE_MILLIS)
            assets.firstOrNull { it.contentUri == uri }?.let { opened ->
                viewerAssets = listOf(opened)
                selectedAssetId = opened.id
            }
        }
    }

    /**
     * "Save a copy instead", offered once [overwriteDeclined] is true: the same
     * [EditedCopyWriter.save] the editor's own COPY path would have called, run from here because
     * [pendingOverwriteBitmap] already holds the exact bitmap EditorScreen rendered and there is
     * no reason to ask it to render the edit a second time.
     */
    fun saveDeclinedOverwriteAsCopy() {
        val asset = pendingOverwriteAsset ?: return
        val bitmap = pendingOverwriteBitmap ?: return
        scope.launch {
            val outcome = editedCopyWriter.save(asset, bitmap)
            pendingOverwriteAsset = null
            pendingOverwriteBitmap = null
            overwriteDeclined = false
            outcome.fold(
                onSuccess = { uri ->
                    viewModel.setEditingAsset(null)
                    transientNotice = TransientNotice("Saved a copy.") { openViewerForSavedUri(uri) }
                    scanRequests.trySend(false)
                },
                onFailure = { error -> userMessage = humanMessage(error, "Could not save a copy of the edit.") },
            )
        }
    }

    // No permission dance needed here, unlike rename/metadata/overwrite above: a conversion
    // always inserts a brand-new MediaStore row this app itself owns (see
    // VideoConversionWriter's own doc for why it never touches the source video), and creating a
    // new row this app owns needs no per-file consent the way writing into someone else's already
    // needs. Routed through the job runner so a rotation cannot kill it, so it shows real
    // progress in the shade instead of a blocking dialog, and so a second tap is refused rather
    // than starting the same video through the pipeline twice.
    fun requestVideoConversion(asset: MediaAsset) {
        if (convertingVideoId != null) return
        convertingVideoId = asset.id
        viewModel.jobRunner.launch(
            title = "Converting ${asset.displayName}",
            kind = ActivityKind.EXPORTING,
        ) { onProgress ->
            videoConversionWriter.convertToH264Mp4(
                asset,
                VideoExportOptions(),
                onProgress = { fraction -> onProgress(fraction) },
            ).also { outcome ->
                convertingVideoId = null
                outcome.onSuccess { scanRequests.trySend(false) }
            }.map { "Converted to MP4 (H.264)." }
        }
    }

    // AudioConversionWriter's own doc explains why this too needs no permission dance: like a
    // video conversion, it inserts a brand-new MediaStore row this app owns rather than touching
    // the source file. Same job-runner treatment as requestVideoConversion, immediately above.
    fun requestAudioConversion(asset: AudioAsset) {
        if (convertingAudioId != null) return
        convertingAudioId = asset.id
        viewModel.jobRunner.launch(
            title = "Converting ${asset.displayName}",
            kind = ActivityKind.EXPORTING,
        ) { onProgress ->
            onProgress(null)
            audioConversionWriter.convertToAac(asset).also { outcome ->
                convertingAudioId = null
                outcome.onSuccess { audioScanRequests.trySend(false) }
            }.map { "Converted to M4A (AAC)." }
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

        val itemWord = "item${if (items.size == 1) "" else "s"}"
        viewModel.jobRunner.launch(
            title = "${if (operation == PendingTreeOperation.MOVE) "Moving" else "Copying"} ${items.size} $itemWord",
            kind = if (operation == PendingTreeOperation.MOVE) ActivityKind.MOVING else ActivityKind.COPYING,
        ) { onProgress ->
            fileOperations.copyToTree(treeUri, items) { completed, total ->
                onProgress(if (total > 0) completed.toFloat() / total else null)
            }.onSuccess {
                if (operation == PendingTreeOperation.MOVE) {
                    // One coherent flow instead of two stacked messages: the copy phase reports
                    // nothing of its own (see the blank-string convention below) and the very
                    // next thing on screen is Android's own trash consent sheet, whose own
                    // mediaOperationLauncher callback is what actually says whether the move
                    // finished -- "Moved to Android's system trash." or, honestly, that Android
                    // declined it. Two notices for one action was the "garbled" feedback this
                    // replaces.
                    requestMediaOperation(items, PendingMediaOperation.TRASH)
                }
            }.map {
                when (operation) {
                    PendingTreeOperation.MOVE -> ""
                    PendingTreeOperation.COPY -> "Copied ${items.size} $itemWord."
                }
            }
        }
    }

    // Whether the media permission has ever actually been asked for in this app install --
    // Android's own `shouldShowRequestPermissionRationale` returns false BOTH before the first
    // ask and after a permanent ("don't ask again") denial, so telling the two apart needs this
    // flag alongside it. `rememberSaveable` rather than the ViewModel: it needs to survive
    // process death (a cold relaunch after a permanent denial must still show "Open app
    // settings", not silently fall back to "Choose media" and a dialog Android will refuse to
    // show), which is exactly what SavedStateHandle-backed state is for, and a plain Boolean
    // needs no custom Saver.
    var mediaPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val mediaPermissionPermanentlyDenied = mediaPermissionRequested && !permissionGranted &&
        mediaReadPermissions().none { ActivityCompat.shouldShowRequestPermissionRationale(this@FotoXplorrApp, it) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        mediaPermissionRequested = true
        // ACCESS_MEDIA_LOCATION can be granted independently; it must never make the
        // gallery believe that image/video access itself was granted.
        permissionGranted = hasMediaPermission()
        partialMediaAccess = hasPartialMediaAccess()
        if (permissionGranted) scanRequests.trySend(false)
    }

    fun requestMediaPermissionOrOpenSettings() {
        if (mediaPermissionPermanentlyDenied) {
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    },
                )
            }
        } else {
            permissionLauncher.launch(requiredMediaPermissions())
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        audioPermissionGranted = hasAudioPermission()
    }

    // Permission scope can change in system Settings while the process is backgrounded.
    // Re-read it on return so the Settings affordance and scan state never describe a
    // stale grant.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val hadPermission = permissionGranted
        permissionGranted = hasMediaPermission()
        partialMediaAccess = hasPartialMediaAccess()
        audioPermissionGranted = hasAudioPermission()
        if (!hadPermission && permissionGranted) scanRequests.trySend(false)
    }

    val exportMetadataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.jobRunner.launch(title = "Backing up", kind = ActivityKind.BACKING_UP) { onProgress ->
            onProgress(null)
            runCatching {
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
                "Metadata backup exported."
            }
        }
    }

    val importMetadataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.jobRunner.launch(title = "Restoring backup", kind = ActivityKind.BACKING_UP) { onProgress ->
            onProgress(null)
            runCatching {
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
                "Metadata backup imported."
            }
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
     * Routed through the job runner rather than a blocking "Preparing your photo…" dialog: the
     * system share sheet appearing is the feedback that preparation finished, so a successful run
     * reports a blank message (see the outcomes collector's own convention for that) and only a
     * failure needs to say anything at all.
     */
    fun shareWith(items: List<MediaAsset>, options: ShareOptions) {
        if (items.isEmpty()) return
        viewModel.jobRunner.launch(
            title = "Preparing ${if (items.size == 1) "your photo" else "your photos"}",
            kind = ActivityKind.EXPORTING,
        ) { onProgress ->
            onProgress(null)
            sharePreparer.prepare(items, options).map { uris ->
                shareUris(
                    uris = uris,
                    // A stamp frame is a PNG (it has real transparency at the perforations),
                    // so a blanket image/jpeg would misdescribe it to the receiving app.
                    mimeType = if (options.requiresRender) "image/*" else commonShareType(items),
                    title = "Share ${items.size} item${if (items.size == 1) "" else "s"}",
                )
                ""
            }
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
        // Full only the FIRST time this process has ever scanned, tracked on the ViewModel (see
        // its own doc) rather than derived from `permissionGranted` alone: without a ViewModel,
        // rotation recreated this whole composable and its LaunchedEffect from nothing, so
        // `trySend(true)` ran again on every single rotation -- sending "Indexing 3,456 of
        // 21,526" back to zero because the phone turned sideways. A delta pass on every
        // recreation after the first is both correct (MediaIndexer's own delta plan already
        // covers whatever changed while this Activity instance was being torn down and rebuilt)
        // and cheap.
        scanRequests.trySend(!viewModel.initialScanSent)
        viewModel.initialScanSent = true
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
                        ScanState.Error(humanMessage(event.error, "Unable to scan media"))
                }
            }
        }
    }

    // Audio's own change-triggered rescan, mirroring the two LaunchedEffects just above field for
    // field. MediaStoreChangeObserver already watches the whole `Files` table (audio rows
    // included), so no second observer is needed -- just this pipeline's own debounced trigger
    // and its own consumer loop over `audioScanRequests`.
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        changeObserver.changes()
            .debounce(MEDIA_CHANGE_DEBOUNCE_MS)
            .collect { audioScanRequests.trySend(false) }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        // Mirrors the photo/video scan's own guard immediately above, field for field.
        audioScanRequests.trySend(!viewModel.initialAudioScanSent)
        viewModel.initialAudioScanSent = true
        for (userRequested in audioScanRequests) {
            audioIndexer.refresh(userRequested = userRequested).collect { }
        }
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
            viewerAssets = emptyList()
            slideshowActive = false
        }
    }

    LaunchedEffect(selectedAudioAssetId, activeAudioAsset) {
        if (selectedAudioAssetId != null && activeAudioAsset == null) {
            selectedAudioAssetId = null
            audioQueue = emptyList()
        }
    }

    // Reserved for a failure that needs a decision -- every plain "it worked" result now goes
    // through transientNotice, just below, instead of blocking a tap on OK.
    userMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { userMessage = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { userMessage = null }) { Text("OK") } },
        )
    }

    transientNotice?.let { notice ->
        TransientNoticeBar(
            notice = notice,
            onDismiss = { transientNotice = null },
        )
    }

    viewerRenameTarget?.let { asset ->
        com.fotoxplorr.app.gallery.TextEntryDialog(
            title = "Rename file",
            label = "File name",
            initialValue = asset.displayName,
            confirmLabel = "Rename",
            onDismiss = { viewerRenameTarget = null },
            onConfirm = {
                requestRename(asset, it)
                viewerRenameTarget = null
            },
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
        BackHandler { viewModel.setEditingAsset(null) }
        VideoEditorScreen(
            asset = editing,
            onClose = { viewModel.setEditingAsset(null) },
            onSaved = { message ->
                viewModel.setEditingAsset(null)
                transientNotice = TransientNotice(message)
                scanRequests.trySend(false)
            },
        )
    } else if (editing != null) {
        BackHandler { viewModel.setEditingAsset(null) }
        Box(Modifier.fillMaxSize()) {
            EditorScreen(
                asset = editing,
                saveMode = preferences.editorSaveMode,
                onSetSaveMode = galleryPreferences::setEditorSaveMode,
                onClose = { viewModel.setEditingAsset(null) },
                onSaved = { message ->
                    viewModel.setEditingAsset(null)
                    transientNotice = TransientNotice(message)
                    // The copy is a new file, so the library has to learn about it.
                    scanRequests.trySend(false)
                },
                onOverwrite = ::requestOverwrite,
                onSavedUri = ::openViewerForSavedUri,
            )
            // Android's own consent sheet said no to replacing the original -- the editor stays
            // open (this whole branch is still `editing != null`) rather than the edit being
            // lost, and this is the "Save a copy instead" the decline earns it. See
            // AppStateViewModel.overwriteDeclined's own doc.
            if (overwriteDeclined) {
                OverwriteDeclinedBanner(
                    onSaveCopy = ::saveDeclinedOverwriteAsCopy,
                    onDismiss = {
                        overwriteDeclined = false
                        pendingOverwriteAsset = null
                        pendingOverwriteBitmap = null
                    },
                )
            }
        }
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
                viewerAssets = emptyList()
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
            onShareClean = { shareAdvanced(listOf(activeAsset)) },
            onEdit = { viewModel.setEditingAsset(activeAsset) },
            onOpenWith = { openExternally(activeAsset, Intent.ACTION_VIEW) },
            onRename = if (activeAsset.isExternal) null else ({ viewerRenameTarget = activeAsset }),
            onMoveToTrash = { requestMediaOperation(listOf(activeAsset), PendingMediaOperation.TRASH) },
            // Favourite/sensitive/trash/collections are library concepts; an asset opened from
            // another app's share sheet has no library row for any of them to act on.
            isExternal = activeAsset.isExternal,
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
                viewerAssets = emptyList()
                slideshowActive = false
            },
            // Hand-placed location for a photo whose file carries no GPS tag. Written to Foto
            // Xplorr's own index ONLY -- never into the user's file as a side effect of moving a
            // pin (see LocationPicker's own doc for why that used to be a storm of Android
            // consent dialogs, one per drag frame). Writing it into the file is now the separate,
            // explicit onWriteLocationIntoFile action below, which asks Android for permission
            // exactly once, on a real tap.
            manualLatitude = geoState.metadataById[activeAsset.id]?.latitude,
            manualLongitude = geoState.metadataById[activeAsset.id]?.longitude,
            onSetLocation = { latitude, longitude ->
                scope.launch { geoRepository.setManualLocation(activeAsset.id, latitude, longitude) }
            },
            onClearLocation = {
                scope.launch { geoRepository.clearManualLocation(activeAsset.id) }
            },
            // PlaceBlock only ever offers this action when the file itself carries no GPS tag
            // (see its own KDoc), so writing this coordinate into the file's EXIF here can only
            // ever be FILLING IN an absent location, never overwriting a real one. Skipped for a
            // video for the same reason MetadataWriter is skipped elsewhere on this screen: it is
            // an ExifInterface-on-still-images story, not (yet) a video one -- so the action is
            // hidden entirely rather than offered and then failing.
            onWriteLocationIntoFile = if (activeAsset.isVideo) null else {
                {
                    val location = geoState.metadataById[activeAsset.id]
                    if (location != null) {
                        requestMetadataWrite(
                            activeAsset,
                            MetadataEdit(setLocation = GpsCoordinate(location.latitude, location.longitude)),
                        )
                    }
                }
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
            state = GalleryUiState(
                assets = assets,
                favoriteIds = favoriteIds,
                sensitiveIds = sensitiveIds,
                lockedFolders = lockedFolders,
                unlockedFolders = unlockedFolders,
                library = library,
                permissionGranted = permissionGranted,
                partialMediaAccess = partialMediaAccess,
                audioPermissionGranted = audioPermissionGranted,
                mediaPermissionPermanentlyDenied = mediaPermissionPermanentlyDenied,
                scanState = scanState,
                preferences = preferences,
                recognition = recognition,
                recognitionProgress = recognitionProgress,
                pendingSearch = pendingSearch,
            ),
            actions = GalleryActions(
                onRequestPermission = ::requestMediaPermissionOrOpenSettings,
                onRequestAudioPermission = {
                    if (requiredAudioPermissions().isNotEmpty()) audioPermissionLauncher.launch(requiredAudioPermissions())
                },
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
                onSetShowFilmstrip = galleryPreferences::setShowFilmstrip,
                onSetEditorSaveMode = galleryPreferences::setEditorSaveMode,
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
                    viewModel.jobRunner.launch(
                        title = "Building a zip of ${items.size} item${if (items.size == 1) "" else "s"}",
                        kind = ActivityKind.EXPORTING,
                    ) { onProgress ->
                        zipExporter.export(items) { done, total ->
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }.map { uri ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, null))
                            ""
                        }
                    }
                },
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

private fun FotoXplorrActivity.hasPartialMediaAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) != PackageManager.PERMISSION_GRANTED

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        mediaReadPermissions() + Manifest.permission.ACCESS_MEDIA_LOCATION
    } else {
        // WRITE_EXTERNAL_STORAGE folded in here on API 26-28, not requested separately right
        // before whichever write happens to be first: it is in the SAME Android permission group
        // as READ_EXTERNAL_STORAGE (storage), so the two already show as one system dialog, and
        // on these versions nearly every feature of this app (rename, metadata, overwrite, an
        // editor save, a conversion) needs it -- deferring it would only mean the very next thing
        // the person tries to do prompts them again for a permission that reads, to them, like
        // the one they already granted a moment ago.
        mediaReadPermissions() + requiredWriteStoragePermission()
    }

/**
 * Below API 33 audio is already covered by [mediaReadPermissions]'s own `READ_EXTERNAL_STORAGE`
 * fallback -- there is no separate audio grant on those platforms at all, only one storage-wide
 * one. True there so the Audio destination never blocks on a permission it does not need.
 */
private fun FotoXplorrActivity.hasAudioPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * READ_MEDIA_AUDIO plus POST_NOTIFICATIONS, requested together the first time the Audio
 * destination is opened rather than upfront with the photo/video grant -- see
 * [GalleryUiState.audioPermissionGranted]'s own doc. Both are API 33+ only; empty below that, so
 * a caller can request this unconditionally without its own version check.
 */
private fun requiredAudioPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

/**
 * `WRITE_EXTERNAL_STORAGE` on Android 8-9 (it is declared `maxSdkVersion="28"`, so Android itself
 * drops it from the running grant set above that) -- every MediaStore write on those two versions
 * needs it and nothing requested it, so it failed silently. Empty on API 29+, where scoped storage
 * replaced it with the per-file consent dance this app's writers already run.
 */
private fun requiredWriteStoragePermission(): Array<String> =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        emptyArray()
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

/**
 * Classifies [intent] and, for whatever it turns out to be, loads the external asset(s) it names
 * and hands them to the matching callback.
 *
 * Kept apart from [classifyIncomingIntent] deliberately: that function is pure and tested with no
 * Android runtime at all, and this is the Android-glue half -- turning its result into real
 * [MediaAsset]/[com.fotoxplorr.app.audio.AudioAsset] objects via [ExternalAssetLoader] -- that a
 * pure unit test has no way to exercise.
 */
private suspend fun FotoXplorrActivity.processIncomingIntent(
    intent: Intent,
    loader: ExternalAssetLoader,
    onViewMedia: suspend (List<MediaAsset>) -> Unit,
    onViewAudio: suspend (AudioAsset) -> Unit,
    onEditImage: suspend (MediaAsset) -> Unit,
) {
    val mimeType = intent.type ?: intent.data?.let { runCatching { contentResolver.getType(it) }.getOrNull() }
    val streamUris = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { listOf(it.toString()) } ?: emptyList()
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.map { it.toString() } ?: emptyList()
        else -> emptyList()
    }
    val route = classifyIncomingIntent(
        action = intent.action,
        dataUri = intent.data?.toString(),
        mimeType = mimeType,
        streamUris = streamUris,
    )
    when (route) {
        is IncomingIntentRoute.ViewMedia -> {
            val assets = route.uris.mapNotNull { uriString ->
                runCatching {
                    val uri = Uri.parse(uriString)
                    if (route.isVideo) loader.loadVideo(uri) else loader.loadImage(uri)
                }.getOrNull()
            }
            if (assets.isNotEmpty()) onViewMedia(assets)
        }
        is IncomingIntentRoute.ViewAudio -> {
            runCatching { loader.loadAudio(Uri.parse(route.uri)) }.getOrNull()?.let { onViewAudio(it) }
        }
        is IncomingIntentRoute.EditImage -> {
            runCatching { loader.loadImage(Uri.parse(route.uri)) }.getOrNull()?.let { onEditImage(it) }
        }
        IncomingIntentRoute.Unhandled -> Unit
    }
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

/**
 * A short-lived, non-modal result notice -- "Renamed to x.", "Copied 3 items." -- replacing the
 * blocking `AlertDialog` every successful action used to show. [onOpen], when set, is what a tap
 * on the notice does (opening the viewer on a freshly saved copy, say); the notice always
 * dismisses itself after [TRANSIENT_NOTICE_MILLIS] regardless of whether it was tapped.
 */
private data class TransientNotice(val message: String, val onOpen: (() -> Unit)? = null)

/** How long a [TransientNotice] stays up before dismissing itself. */
private const val TRANSIENT_NOTICE_MILLIS = 3_000L

/** How long [FotoXplorrActivity.openViewerForSavedUri] waits for the row it just inserted to
 *  reach the repository's own `observeAll()` before giving up quietly. */
private const val SAVED_COPY_SETTLE_MILLIS = 600L

/**
 * The bar a [TransientNotice] renders as: a plain surface near the bottom of the screen, tappable
 * when the notice carries an action, dismissible with a tap on its own close glyph, and otherwise
 * left to expire on its own.
 */
@Composable
private fun TransientNoticeBar(notice: TransientNotice, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(Color(0xFF232329), RoundedCornerShape(10.dp))
                .clickable(enabled = notice.onOpen != null) {
                    notice.onOpen?.invoke()
                    onDismiss()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(notice.message, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White.copy(alpha = 0.7f)) }
        }
    }
}

/**
 * "Android would not let this app replace the original -- save a copy instead?" Rendered over
 * the still-open editor rather than as a dialog that would hide it, because the whole point is
 * that the edit the person just made is still right there, unlost, waiting on this one decision.
 */
@Composable
private fun OverwriteDeclinedBanner(onSaveCopy: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(Color(0xFF232329), RoundedCornerShape(10.dp))
                .padding(16.dp),
        ) {
            Text(
                "Android did not allow replacing the original photo.",
                color = Color.White,
            )
            Text(
                "Your edit is not lost -- save it as a new photo instead.",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Discard", color = Color.White.copy(alpha = 0.7f)) }
                TextButton(onClick = onSaveCopy) { Text("Save a copy") }
            }
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
