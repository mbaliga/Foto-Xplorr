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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fotoxplorr.app.backup.rememberMetadataBackup
import com.fotoxplorr.app.curate.AutoCurationPass
import com.fotoxplorr.app.editor.EditedCopyWriter
import com.fotoxplorr.app.editor.EditorScreen
import com.fotoxplorr.app.editor.OverwriteRecovery
import com.fotoxplorr.app.editor.RecoveryEntry
import com.fotoxplorr.app.editor.decodeRecoveryEntries
import com.fotoxplorr.app.editor.encodeRecoveryEntries
import com.fotoxplorr.app.editor.recoveryNoticeMessage
import com.fotoxplorr.app.editor.toRecoveryEntries
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.fileops.MediaFileOperations
import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GalleryScreen
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.gallery.rememberGeoRepository
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobForegroundService
import com.fotoxplorr.app.jobs.humanMessage
import com.fotoxplorr.app.jobs.notificationPromptWasAnswered
import com.fotoxplorr.app.jobs.shouldAskForJobNotifications
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.GpsCoordinate
import com.fotoxplorr.app.metadata.MetadataEdit
import com.fotoxplorr.app.metadata.MetadataWriter
import com.fotoxplorr.app.metadata.isMetadataWritable
import com.fotoxplorr.app.openwith.ExternalAssetLoader
import com.fotoxplorr.app.openwith.IncomingIntentRoute
import com.fotoxplorr.app.openwith.OpenRequestLookup
import com.fotoxplorr.app.openwith.MediaStoreUri
import com.fotoxplorr.app.openwith.classifyIncomingIntent
import com.fotoxplorr.app.openwith.classifyMediaStoreUri
import com.fotoxplorr.app.openwith.lookupOpenRequest
import com.fotoxplorr.app.openwith.lookupUncataloguedRow
import com.fotoxplorr.app.openwith.restoreViewerRun
import com.fotoxplorr.app.openwith.withSafeExtras
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.shell.copySavedOriginalAtRiskMessage
import com.fotoxplorr.app.shell.OVERWRITE_NOT_STARTED_MESSAGE
import com.fotoxplorr.app.shell.OVERWRITE_NOT_ALLOWED_MESSAGE
import com.fotoxplorr.app.shell.OverwriteFailure
import com.fotoxplorr.app.shell.PendingMediaOperation
import com.fotoxplorr.app.shell.PendingTreeOperation
import com.fotoxplorr.app.shell.overwriteFailureOf
import com.fotoxplorr.app.shell.shouldRefreshRecoveryNotice
import com.fotoxplorr.app.video.VideoExportOptions
import com.fotoxplorr.app.recognition.RecognitionIndexer
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.app.share.PreparedItem
import com.fotoxplorr.app.share.ShareFrame
import com.fotoxplorr.app.share.ShareOptions
import com.fotoxplorr.app.share.ShareOptionsSheet
import com.fotoxplorr.app.share.SharePreparer
import com.fotoxplorr.app.share.fileProviderAuthority
import com.fotoxplorr.app.share.ZipExporter
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.crashRecoveryStyle
import com.fotoxplorr.app.video.VideoConversionWriter
import com.fotoxplorr.app.videoeditor.VideoEditorScreen
import com.fotoxplorr.app.viewer.ExternalViewerActivity
import com.fotoxplorr.app.viewer.ViewerScreen
import com.fotoxplorr.core.model.MediaId
import dev.aarso.crashrecovery.CrashRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fotoxplorr.app.ui.HelpCopy

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
        // Unpacked once, guarded, before anything reads an extra: see withSafeExtras.
        setIntent(intent.withSafeExtras())
        incomingIntentGeneration++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Any app can send this activity an intent; a hostile extra must not crash it on launch.
        intent?.withSafeExtras()
        // First thing, per dev.aarso:crash-recovery's documented contract: if a crash was
        // captured last run, show the recovery screen instead of this activity's real
        // content and finish this instance.
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fotoz", style = crashRecoveryStyle(this))) return
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

// MEDIA_CHANGE_DEBOUNCE_MS moved to LibraryRuntime.kt (P0-09) along with the change-observer
// subscription it debounces.

/**
 * "No media id": the default for an Intent extra that was never set (P0-18's
 * [FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID]). Every real [MediaId] comes from MediaStore's own
 * `_ID` column, which is never negative. (The ids of the open photo / editor are
 * saved across process death by [AppStateViewModel]'s SavedStateHandle, not here.)
 */
private const val NO_MEDIA_ID = -1L

/**
 * The key the gallery's saveable state is held under while the viewer or the editor replaces it
 * (see `galleryStateHolder` in [FotoXplorrApp]). One key: there is one browser.
 */
internal const val GALLERY_STATE_KEY = "gallery"

/** Shown when another app asks to edit a photo while a replace is in flight or its failure is being held. */
private const val FINISH_EDIT_FIRST_MESSAGE = "Finish saving your edit first"

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun FotoXplorrActivity.FotoXplorrApp(
    galleryPreferences: GalleryPreferences,
    preferences: GalleryPreferencesState,
) {
    val viewModel: AppStateViewModel = viewModel()
    val externalAssetLoader = remember { ExternalAssetLoader(applicationContext) }
    // P0-09: the one process-wide scanning engine. Was rebuilt from scratch, and re-triggered a
    // full scan, on every single Activity recreation -- see LibraryRuntime's own KDoc.
    val runtime = remember { LibraryRuntime.get(applicationContext) }
    val repository = runtime.repository
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
    // P0-14: which ids actually animate, kept fresh by LibraryRuntime itself after every scan --
    // this Activity only ever reads it, the same read-only-view shape scanState already has.
    val animatedIds by runtime.animationIndex.observeAnimatedIds().collectAsStateWithLifecycle(initialValue = emptySet())

    var permissionGranted by remember { mutableStateOf(hasMediaPermission()) }
    var partialMediaAccess by remember { mutableStateOf(hasPartialMediaAccess(applicationContext)) }
    // P0-09: the scan itself, and the request channel driving it, now live entirely in
    // LibraryRuntime -- this is a read-only view onto its process-wide state, so recomposing (or
    // recreating) this Activity never resets progress or re-triggers a full scan.
    val scanState by runtime.scanState.collectAsStateWithLifecycle(initialValue = ScanState.Idle)

    // Every one of these used to be `remember { mutableStateOf(...) }`, which any Activity
    // recreation tore down along with the whole composable tree -- closing the
    // viewer/editor, forgetting the current selection, and cancelling whatever
    // `rememberCoroutineScope()` job happened to be mid-flight. (The manifest's `configChanges`
    // keeps a plain rotation from recreating the Activity at all, P0-09, but not every
    // configuration change, and never process death.) Delegating to a property on `viewModel`
    // (`by viewModel::field`) keeps every downstream read/write in this function exactly as it
    // was; only the storage moved, to survive the recreation (and, for the two ids `viewModel`
    // mirrors into `SavedStateHandle`, process death).
    var viewerAssets by viewModel::viewerAssets
    var selectedAssetId by viewModel::selectedAssetId
    var slideshowActive by viewModel::slideshowActive
    var slideshowPaused by viewModel::slideshowPaused
    var pendingOperation by viewModel::pendingOperation
    var pendingOperationIds by viewModel::pendingOperationIds
    var pendingTreeOperation by viewModel::pendingTreeOperation
    var pendingTreeItems by viewModel::pendingTreeItems
    var pendingRenameAsset by viewModel::pendingRenameAsset
    var pendingRenameName by viewModel::pendingRenameName
    var pendingMetadataAsset by viewModel::pendingMetadataAsset
    var pendingMetadataEdit by viewModel::pendingMetadataEdit
    // P0-08 item 6: true from the moment requestMetadataWrite actually starts a write (or its
    // consent round trip) until it lands, so a second edit arriving in that window (the detail
    // room commits creator and copyright as two independent field commits, not one) is queued and
    // merged rather than clobbering pendingMetadataEdit outright -- seedable to be non-null and
    // still have the *other* field's edit silently discarded, exactly the loss the brief names.
    var metadataWriteInFlight by remember { mutableStateOf(false) }
    var queuedMetadataAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var queuedMetadataEdit by remember { mutableStateOf<MetadataEdit?>(null) }
    var pendingOverwriteAsset by viewModel::pendingOverwriteAsset
    var pendingOverwriteBitmap by viewModel::pendingOverwriteBitmap
    // True once Android's own consent sheet for replacing the original has been declined, so the
    // editor (still open -- see `editingAsset` below) can offer "Save a copy instead" using the
    // very bitmap already rendered, instead of the edit simply being lost.
    var overwriteDeclined by viewModel::overwriteDeclined
    var overwriteFailureMessage by viewModel::overwriteFailureMessage
    var overwriteOriginalAtRisk by viewModel::overwriteOriginalAtRisk
    // The one place a failed overwrite is recorded, so the banner's wording and its "original may
    // be damaged" state are always set together with the flag that shows it. Ignored when the
    // replace it reports no longer belongs to the open editor (see overwriteOutcomeApplies); a
    // failure that left the original damaged is then still said out loud, as it has no banner.
    //
    // The pending recovery notice (the dialog naming a photo whose safety copy was kept) is
    // computed at start; [refreshRecoveryNotice] recomputes it so a copy kept by a failure of THIS
    // session is announced now, not at the next cold start. Entries are kept as a string so the
    // dialog survives a recreation.
    var recoveryEncoded by rememberSaveable { mutableStateOf("") }
    fun refreshRecoveryNotice() {
        scope.launch {
            recoveryEncoded = withContext(Dispatchers.IO) {
                OverwriteRecovery.pendingCopies(applicationContext).toRecoveryEntries().encodeRecoveryEntries()
            }
        }
    }
    fun declineOverwrite(failure: OverwriteFailure) {
        if (!viewModel.recordOverwriteFailure(failure) && failure.originalAtRisk) {
            viewModel.userMessage = failure.message
        }
        if (shouldRefreshRecoveryNotice(failure)) refreshRecoveryNotice()
    }

    // P0-09 item 6 (process death): the ViewModel above keeps `viewerAssets` across an Activity
    // recreation, but a dead process takes the ViewModel with it, and MediaAsset is not
    // Bundle-saveable. So the run being paged through is ALSO mirrored here as plain ids, which the
    // restore effect further down rebuilds into real MediaAssets once the repository has loaded
    // (`selectedAssetId` itself is mirrored into SavedStateHandle by the ViewModel). External
    // assets (open-with / share-to) have no library row to look up, so they are deliberately not
    // saved: the original intent is re-delivered after process death and reloads them.
    var savedViewerAssetIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    // The one write path for the viewer's list, so no call site needs to know it is stored twice.
    fun setViewerAssets(list: List<MediaAsset>) {
        viewerAssets = list
        savedViewerAssetIds = list.filterNot { it.isExternal }.map { it.id.value }.toLongArray()
    }

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

    // On every start, off the main thread: ages out the leftover safety copies the person has
    // already been told about and, if a copy of a photo that was not put back is still waiting,
    // says so in a dialog that waits for an answer (a three-second notice is too easy to miss for a
    // warning that a photo may be damaged). Nothing is recorded until the dialog is answered, so a
    // process death or a recreation before then just shows it again; the entries are kept as a
    // string so the dialog itself survives a recreation. See OverwriteRecovery.pendingCopies.
    LaunchedEffect(Unit) {
        recoveryEncoded = withContext(Dispatchers.IO) {
            OverwriteRecovery.sweepOrphans(applicationContext)
            OverwriteRecovery.pendingCopies(applicationContext).toRecoveryEntries().encodeRecoveryEntries()
        }
    }

    // [assets] narrowed to what the person may see right now (not trashed, archived, in a locked
    // folder or sensitive-hidden), read from the stores' own StateFlows at CALL time. The values
    // collected into locals above start as empty sets for the first composition, which would let a
    // locked photo through; the stores themselves are loaded synchronously and never lag. Every
    // path that opens a photo on someone else's say-so (a redirect, an open-with / share Uri, a
    // restore after process death) goes through this, so none is a side door around the lock.
    fun browsableNow(list: List<MediaAsset>): List<MediaAsset> = browsableAssets(
        list,
        libraryStore.observe().value.archivedIds,
        sensitiveStore.observe().value,
        privateFolderStore.observeLockedFolders().value,
        privateFolderStore.observeUnlockedFolders().value,
        galleryPreferences.observe().value.hideSensitive,
    )

    // P0-18: ExternalViewerActivity redirects here, rather than reimplementing the catalogue
    // actions itself, when an incoming "Open with" URI turns out to already be a photo this app
    // has scanned. `assets` is empty on the very first composition and fills in asynchronously
    // (P0-09), so this waits for a real match rather than firing once and giving up; the
    // `rememberSaveable` guard is what stops a LATER background rescan (which also changes
    // `assets`) from reopening the viewer out from under someone who has since closed it.
    var consumedOpenMediaIdExtra by rememberSaveable { mutableStateOf(false) }
    // This Activity is singleTop (media3 viewer work), so a redirect that arrives while it is
    // already running comes in through onNewIntent and bumps incomingIntentGeneration. That is a
    // NEW request, so it re-arms the one-shot guard above -- which exists only to stop a later
    // rescan from reopening the viewer for the SAME, already-handled intent. Plain `remember`, not
    // saveable: a recreated Activity starts at generation 0 again, and that must NOT re-arm.
    var armedForIntentGeneration by remember { mutableIntStateOf(0) }
    // Unknown is only "not scanned yet" until the first scan has settled; after that a request for a
    // photo the catalogue does not hold is simply dropped, so it cannot pop the viewer open later,
    // when some other photo with that id is scanned in.
    val scanSettled = scanState is ScanState.Complete || scanState is ScanState.Error
    LaunchedEffect(assets, incomingIntentGeneration, scanSettled) {
        if (incomingIntentGeneration > armedForIntentGeneration) {
            armedForIntentGeneration = incomingIntentGeneration
            consumedOpenMediaIdExtra = false
        }
        if (consumedOpenMediaIdExtra) return@LaunchedEffect
        val requestedId = runCatching { intent.getLongExtra(FotoXplorrActivity.EXTRA_OPEN_MEDIA_ID, NO_MEDIA_ID) }
            .getOrDefault(NO_MEDIA_ID)
        if (requestedId == NO_MEDIA_ID) {
            consumedOpenMediaIdExtra = true
            return@LaunchedEffect
        }
        // Any app can send this extra, so it is held to the same rule as every gallery surface:
        // a hidden photo is NOT opened (nor shown as an "external" file), the person is told why.
        var lookup = lookupOpenRequest(assets, browsableNow(assets), requestedId)
        if (lookup == OpenRequestLookup.Unknown && scanSettled) {
            // The composable's `assets` can trail the repository by a frame; ask the repository
            // itself before deciding the id is not ours.
            val live = repository.awaitLoaded()
            lookup = lookupOpenRequest(live, browsableNow(live), requestedId)
        }
        when (val found = lookup) {
            is OpenRequestLookup.Found -> {
                setViewerAssets(listOf(found.asset))
                selectedAssetId = found.asset.id
            }
            OpenRequestLookup.Hidden -> transientNotice = TransientNotice(OPEN_REQUEST_HIDDEN_MESSAGE)
            OpenRequestLookup.Unknown -> if (!scanSettled) return@LaunchedEffect
            OpenRequestLookup.Unverified -> Unit
        }
        consumedOpenMediaIdExtra = true
    }

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
    // The progress notification needs POST_NOTIFICATIONS on Android 13+, and this is the only place
    // that asks for it: once, the first time a job actually runs (see shouldAskForJobNotifications).
    // "Asked" is recorded when the system dialog has been ANSWERED, not when it was launched: a
    // launch the system drops (the activity was not in front, or another permission request was
    // already pending, which is answered with an empty result and no dialog) must be tried again.
    var notificationAskInFlight by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationAskInFlight = false
        // The permission state afterwards tells a real answer from a dropped request.
        val answered = notificationPromptWasAnswered(
            granted = granted,
            shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this@FotoXplorrApp,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
        if (answered) {
            applicationContext.getSharedPreferences("job_notifications", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("asked", true).apply()
        }
    }
    LaunchedEffect(anyJobRunning) {
        if (anyJobRunning) {
            // First, and not behind the ask below: that waits for RESUMED, and a job started while
            // the app is in the background (the case this service exists for) must still get its
            // notification now.
            JobForegroundService.start(applicationContext)
            val prefs = applicationContext.getSharedPreferences("job_notifications", android.content.Context.MODE_PRIVATE)
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!notificationAskInFlight &&
                shouldAskForJobNotifications(Build.VERSION.SDK_INT, granted, prefs.getBoolean("asked", false))
            ) {
                // Only while the activity is in front (RESUMED); waits for that, and gives up with
                // this effect if the jobs finish first, leaving the question for the next job.
                lifecycle.withResumed {
                    notificationAskInFlight = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            JobForegroundService.stop(applicationContext)
        }
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

    // Open-with / share-to: classifies whatever this Activity was started or re-delivered with
    // (see onNewIntent's own doc on incomingIntentGeneration) and routes it straight to the
    // viewer or the editor. Keyed on the generation counter, not on `intent`
    // itself -- `Intent` is not stable equality for a Compose key across the SAME intent being
    // re-read after `setIntent`, and the counter is exactly "a new intent arrived" as a value.
    LaunchedEffect(incomingIntentGeneration) {
        processIncomingIntent(
            intent = intent,
            loader = externalAssetLoader,
            lookupCatalogued = { rowId ->
                val all = repository.awaitLoaded()
                when (val known = lookupOpenRequest(all, browsableNow(all), rowId)) {
                    // Not scanned yet (a photo written a moment ago): ask MediaStore which folder
                    // the row is in, so a locked folder stays locked inside the scan debounce.
                    OpenRequestLookup.Unknown -> lookupUncataloguedRow(
                        externalAssetLoader.readMediaStoreRow(rowId),
                        ::browsableNow,
                    ).also { if (it == OpenRequestLookup.Unknown) runtime.requestScan(false) }
                    else -> known
                }
            },
            onViewMedia = { loaded ->
                setViewerAssets(loaded)
                selectedAssetId = loaded.first().id
                slideshowActive = false
            },
            // Refused while a replace is in flight or its failure is being held: the edit on screen
            // is not yet safe, and opening another photo would drop it.
            onEditImage = { asset ->
                if (!viewModel.setEditingAsset(asset)) transientNotice = TransientNotice(FINISH_EDIT_FIRST_MESSAGE)
            },
            onHidden = { transientNotice = TransientNotice(OPEN_REQUEST_HIDDEN_MESSAGE) },
            onUnverified = { transientNotice = TransientNotice(OPEN_REQUEST_UNVERIFIED_MESSAGE) },
        )
    }

    // Process death: `selectedAssetId`/`editingAssetId` themselves survived
    // (SavedStateHandle) and so did `savedViewerAssetIds` (rememberSaveable), but `viewerAssets`/
    // `editingAsset` did not -- a fresh ViewModel instance starts those empty.
    // Rebuilds each one, by id, the FIRST time the relevant repository has something to look the
    // id up in; a no-op on an ordinary configuration change, where they were never emptied.
    // Restored through browsableNow: the unlocked folders live in memory, so after process death a
    // locked folder is locked again, and a photo trashed / archived / hidden in the meantime is
    // simply not reopened.
    LaunchedEffect(assets) {
        if (assets.isEmpty()) return@LaunchedEffect
        val browsable = browsableNow(assets)
        viewModel.selectedAssetId?.let { id ->
            if (viewerAssets.isEmpty()) {
                // The whole run that was being paged through; just the open photo if that list
                // did not survive.
                val restored = restoreViewerRun(browsable, savedViewerAssetIds.asList(), id.value)
                if (restored != null) {
                    viewerAssets = restored
                } else {
                    // The photo that was open no longer exists, is now hidden (or was an external
                    // one, which the re-delivered intent reloads on its own): nothing to reopen.
                    selectedAssetId = null
                }
            }
        }
        viewModel.editingAssetId?.let { id ->
            if (viewModel.editingAsset == null) {
                val restored = browsable.firstOrNull { it.id == id }
                // Dropping the id too, so it cannot resurface on a later rescan once unlocked.
                viewModel.setEditingAsset(restored)
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
            setViewerAssets(viewerAssets.filterNot { it.id in affectedIds })
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
            runtime.requestScan(false)
        } else if (affectedIds.isNotEmpty()) {
            transientNotice = TransientNotice("Android cancelled the media operation.")
        }
    }

    fun requestMediaOperation(items: List<MediaAsset>, operation: PendingMediaOperation) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            userMessage = HelpCopy.Hint.TRASH_NEEDS_ANDROID_11
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
                    runtime.requestScan(false)
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
                            runtime.requestScan(false)
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
                .onFailure { error -> userMessage = humanMessage(error, "Android did not allow this file's metadata to be updated.") }
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
            transientNotice = TransientNotice("Android cancelled the metadata update.")
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
                userMessage = "Fotoz can't write metadata into ${asset.mimeType} files yet"
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
                    userMessage = humanMessage(error, "Could not request metadata write permission.")
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
                        .onFailure { failure -> userMessage = humanMessage(failure, "Android did not allow this file's metadata to be updated.") }
                    finishMetadataWrite()
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
                // The file changed whether or not the editor is still the one that asked, so the
                // notice and the rescan do not depend on the state transition applying.
                viewModel.finishOverwriteSucceeded()
                transientNotice = TransientNotice("Replaced the original.")
                // The bytes at this Uri changed size and content; the library's cached
                // size/date-modified (and any thumbnail) are stale until MediaStore looks again.
                runtime.requestScan(false)
            },
            onFailure = { error -> declineOverwrite(overwriteFailureOf(error)) },
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
        if (!viewModel.overwriteOutcomeApplies()) {
            // A result for a replace nobody is waiting on any more: nothing to write or to show.
        } else if (result.resultCode == Activity.RESULT_OK && asset != null && bitmap != null) {
            viewModel.launchWrite { finishOverwrite(editedCopyWriter.overwrite(asset, bitmap)) }
        } else {
            // Declined, not merely cancelled -- see finishOverwrite's own doc for why this is
            // NOT treated the way a trash/rename cancellation is (nulling the pending state and
            // reporting failure): the edit itself must survive this.
            declineOverwrite(OverwriteFailure(OVERWRITE_NOT_ALLOWED_MESSAGE, originalAtRisk = false))
        }
    }

    // EditorScreen already rendered the edit at full size and decided OVERWRITE is even possible
    // for this asset's format (see EditorScreen's own canOverwriteInPlace) before calling this --
    // this function's only job is the consent dance and the actual write, the same division of
    // labour requestMetadataWrite already has with MetadataWriter.
    fun requestOverwrite(asset: MediaAsset, bitmap: Bitmap) {
        // A second request while one is in flight (or for another photo) is dropped; the editor
        // disables Save and Cancel for the duration, so this is the belt to that brace. Said out
        // loud: the edit it carried is not being saved.
        if (!viewModel.beginOverwrite(asset, bitmap)) {
            transientNotice = TransientNotice(OVERWRITE_NOT_STARTED_MESSAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                MediaStore.createWriteRequest(contentResolver, listOf(asset.contentUri))
            }.onSuccess { request ->
                overwritePermissionLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build(),
                )
            }.onFailure {
                // The system would not even offer the consent sheet: Android said no.
                declineOverwrite(OverwriteFailure(OVERWRITE_NOT_ALLOWED_MESSAGE, originalAtRisk = false))
            }
        } else {
            viewModel.launchWrite {
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
            runtime.requestScan(false)
            // The row EditedCopyWriter just inserted needs one delta scan to reach `assets`; a
            // short settle covers the gap between that scan landing and this composable's own
            // collection of it, without this function polling in a loop.
            delay(SAVED_COPY_SETTLE_MILLIS)
            assets.firstOrNull { it.contentUri == uri }?.let { opened ->
                setViewerAssets(listOf(opened))
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
        // A second tap while the first copy is being written would save the same edit twice.
        if (!viewModel.beginCopyOfDeclinedOverwrite()) return
        // On the ViewModel's scope, like the replace: a recreation must not strand the lock.
        viewModel.launchWrite {
            val outcome = editedCopyWriter.save(asset, bitmap)
            // The failure state is forgotten only once a copy exists: if the copy failed too (the
            // same full storage, say) the banner, the at-risk flag and the held bitmap are all
            // still here, and the person can try again.
            val originalStillAtRisk = viewModel.finishCopyOfDeclinedOverwrite(saved = outcome.isSuccess)
            outcome.fold(
                onSuccess = { uri ->
                    transientNotice = TransientNotice("Saved a copy.") { openViewerForSavedUri(uri) }
                    // The copy does not repair the original, and the safety copy of it is still
                    // kept (the notice for it stays pending until it has been answered).
                    if (originalStillAtRisk) userMessage = copySavedOriginalAtRiskMessage(asset.displayName)
                    // Offered again if the earlier dialog was dismissed without an answer.
                    if (shouldRefreshRecoveryNotice(copySavedOriginalAtRisk = originalStillAtRisk)) refreshRecoveryNotice()
                    runtime.requestScan(false)
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
            try {
                videoConversionWriter.convertToH264Mp4(
                    asset,
                    VideoExportOptions(),
                    onProgress = { fraction -> onProgress(fraction) },
                ).also { outcome ->
                    outcome.onSuccess { runtime.requestScan(false) }
                }.map { "Converted to MP4 (H.264)." }
            } finally {
                // In a finally, not after the call: the shade's Cancel throws a CancellationException
                // out of the suspend call above, which would skip a plain statement and leave this
                // video "converting" (and its action disabled) for the rest of the session.
                convertingVideoId = null
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
        partialMediaAccess = hasPartialMediaAccess(applicationContext)
        if (permissionGranted) runtime.requestScan(false)
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

    // Permission scope can change in system Settings while the process is backgrounded.
    // Re-read it on return so the Settings affordance and scan state never describe a
    // stale grant.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val hadPermission = permissionGranted
        permissionGranted = hasMediaPermission()
        partialMediaAccess = hasPartialMediaAccess(applicationContext)
        if (!hadPermission && permissionGranted) runtime.requestScan(false)
    }

    // The plain tags-and-collections backup: pickers, the review a file must pass and the confirmed
    // Replace all live in backup/MetadataBackupUi.kt; nothing is written before the person has seen it.
    val metadataBackup = rememberMetadataBackup(
        jobRunner = viewModel.jobRunner,
        libraryStore = libraryStore,
        favoriteStore = favoriteStore,
        sensitiveStore = sensitiveStore,
        photos = { assets },
        onMessage = { userMessage = it },
    )

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
     * Hands the private safety copy [entry] names to the system share sheet, under the photo's own
     * name, so the person can put it back over the damaged original with any app. The private file
     * is copied into the share directory first and never exposed itself.
     */
    fun shareSafetyCopy(entry: RecoveryEntry) {
        scope.launch {
            val staged = withContext(Dispatchers.IO) { OverwriteRecovery.stageForShare(applicationContext, entry.fileName) }
            if (staged == null) {
                userMessage = "The safety copy could not be found any more."
                return@launch
            }
            val uri = FileProvider.getUriForFile(applicationContext, fileProviderAuthority(applicationContext), staged.first)
            shareUris(listOf(uri), staged.second, "Share the safety copy")
        }
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
     *
     * Routed through the job runner rather than a blocking "Preparing your photo…" dialog: the
     * system share sheet appearing is the feedback that preparation finished, so a successful run
     * reports a blank message (see the outcomes collector's own convention for that) and only a
     * failure needs to say anything at all. A run where NOTHING could be prepared is thrown as a
     * failure for exactly that reason, so it reaches the same "needs a decision" dialog.
     */
    fun shareWith(items: List<MediaAsset>, options: ShareOptions) {
        if (items.isEmpty()) return
        viewModel.jobRunner.launch(
            title = "Preparing ${if (items.size == 1) "your photo" else "your photos"}",
            kind = ActivityKind.EXPORTING,
        ) { onProgress ->
            onProgress(null)
            sharePreparer.prepare(items, options, animatedIds = animatedIds).mapCatching { prepared ->
                val ready = prepared.filterIsInstance<PreparedItem.Ready>()
                val failed = prepared.filterIsInstance<PreparedItem.Failed>()
                if (ready.isEmpty()) {
                    error(
                        if (failed.size == 1) {
                            failed.first().reason
                        } else {
                            "None of the ${failed.size} photos could be prepared to share."
                        },
                    )
                }
                // Names what was left out; the share sheet below still goes ahead for the rest.
                unpreparedShareItemsMessage(failed)?.let { userMessage = it }
                shareUris(
                    uris = ready.map { it.uri },
                    // Derived from what was actually produced, not the original assets or a
                    // render flag: a stamp frame comes out as PNG, an unsupported source may
                    // have been re-encoded to JPEG, and a raw copy keeps its own MIME type.
                    mimeType = commonShareType(ready.map { it.mimeType }),
                    title = "Share ${ready.size} item${if (ready.size == 1) "" else "s"}",
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
        val chooser = Intent.createChooser(
            intent,
            if (action == Intent.ACTION_EDIT) "Edit with" else "Open with",
        ).apply {
            // P0-18: never offer this app back to itself here. Without this, ACTION_VIEW's
            // chooser would list ExternalViewerActivity, which -- for a catalogued asset -- just
            // redirects straight back to this exact activity/photo, a round trip through a
            // picker that answers nothing: the person is already looking at this photo, in this
            // app, right now.
            //
            // FotoXplorrActivity is excluded too: its EDIT filter (it also receives SEND) would
            // otherwise list this app in "Edit with" and re-open the photo that is already on
            // screen as an "external" asset. It has no VIEW filter any more; the entry is harmless
            // for ACTION_VIEW and still needed for ACTION_EDIT.
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(
                    ComponentName(this@FotoXplorrApp, ExternalViewerActivity::class.java),
                    ComponentName(this@FotoXplorrApp, FotoXplorrActivity::class.java),
                ),
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

    // Skipped while viewerAssets is empty: right after process death selectedAssetId has been
    // restored from SavedStateHandle but the list is only rebuilt once the repository has loaded
    // (the restore effect above), and clearing the id here first would throw that restore away.
    LaunchedEffect(selectedAssetId, activeAsset) {
        if (selectedAssetId != null && activeAsset == null && viewerAssets.isNotEmpty()) {
            selectedAssetId = null
            setViewerAssets(emptyList())
            slideshowActive = false
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

    val recoveryEntries = remember(recoveryEncoded) { decodeRecoveryEntries(recoveryEncoded) }
    recoveryNoticeMessage(recoveryEntries)?.let { message ->
        val names = recoveryEntries.map { it.fileName }
        // Dismissing without answering (a tap outside) records nothing: the notice is offered again.
        AlertDialog(
            onDismissRequest = { recoveryEncoded = "" },
            title = { Text("A save did not finish") },
            text = {
                Column {
                    Text(
                        message + "\n\nFotoz kept a private copy of each original from before the save. Share it to " +
                            "put the photo back with any app. The copies are deleted on their own some days " +
                            "after you tap OK; delete them now only if the photos look fine, which cannot be undone.",
                    )
                    recoveryEntries.forEach { entry ->
                        TextButton(onClick = { shareSafetyCopy(entry) }) {
                            Text(if (entry.photoName.isBlank()) "Share the safety copy" else "Share the copy of ${entry.photoName}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recoveryEncoded = ""
                        OverwriteRecovery.markNoticeSeen(applicationContext, names)
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        recoveryEncoded = ""
                        scope.launch(Dispatchers.IO) {
                            OverwriteRecovery.markNoticeSeen(applicationContext, names)
                            OverwriteRecovery.deleteCopies(applicationContext, names)
                        }
                    },
                ) { Text("Delete copies") }
            },
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

    // The gallery shares this slot with the editor and the viewer, so it is composed out whenever
    // either is up and everything it kept in `rememberSaveable` (destination, route, search,
    // selection, Settings, the grid's scroll position) would be dropped with it: Back from a photo
    // landed on the default destination at the top. The holder keeps that state while the gallery
    // is out of composition and hands it back when it returns. Remembered here, above the
    // branches, because it has to outlive each of them.
    val galleryStateHolder = rememberSaveableStateHolder()

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
                runtime.requestScan(false)
            },
        )
    } else if (editing != null) {
        // While a failed replace has left the original at risk, Back and close change nothing:
        // the banner below stays up, with only "Save a copy" to leave by (the edited bitmap held
        // for it is the only good version). Otherwise closing also clears the failure state.
        BackHandler { viewModel.closeEditor() }
        Box(Modifier.fillMaxSize()) {
            EditorScreen(
                asset = editing,
                saveMode = preferences.editorSaveMode,
                onSetSaveMode = galleryPreferences::setEditorSaveMode,
                // Cancel and Save are off while a replace is in flight or the original is at risk.
                locked = viewModel.editorCloseBlocked,
                onClose = { viewModel.closeEditor() },
                onSaved = { message ->
                    // Only if the editor is still on the photo this save belonged to.
                    viewModel.closeEditorFor(editing.id)
                    transientNotice = TransientNotice(message)
                    // The copy is a new file, so the library has to learn about it.
                    runtime.requestScan(false)
                },
                onSaveInProgress = { viewModel.saveInProgress = it },
                onOverwrite = ::requestOverwrite,
                onSavedUri = ::openViewerForSavedUri,
            )
            // Android's own consent sheet said no to replacing the original -- the editor stays
            // open (this whole branch is still `editing != null`) rather than the edit being
            // lost, and this is the "Save a copy instead" the decline earns it. See
            // AppStateViewModel.overwriteDeclined's own doc.
            if (overwriteDeclined) {
                OverwriteDeclinedBanner(
                    message = overwriteFailureMessage ?: OVERWRITE_NOT_ALLOWED_MESSAGE,
                    originalAtRisk = overwriteOriginalAtRisk,
                    copySaving = viewModel.overwriteCopySaving,
                    onSaveCopy = ::saveDeclinedOverwriteAsCopy,
                    onDismiss = { viewModel.clearOverwriteFailure() },
                )
            }
        }
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
            // Likewise the palette that pass sampled, and every photo's dominant colour for the
            // expanded map's dots.
            palette = recognition.paletteFor(activeAsset.id),
            dotColors = recognition.dominantColoursByMedia,
            // What this photo carries in the library, as opposed to what its file says. Read
            // from the same LibraryState the grid already renders, so a tag added in the grid's
            // selection menu is on the photo the moment the viewer opens over it.
            tags = library.tagsFor(activeAsset.id),
            autoTags = library.autoTagsFor(activeAsset.id),
            onRemoveTag = { tag -> libraryStore.removeTag(setOf(activeAsset.id), tag) },
            onAddTag = { tag -> libraryStore.addTag(setOf(activeAsset.id), tag) },
            // For the map the details room's plate grows into: every located photo in the library
            // becomes a dot on it, so it needs the whole library and its geo index, not only the
            // assets being paged through.
            geoState = geoState,
            libraryAssets = assets,
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
            slideshowPaused = slideshowPaused,
            onSetSlideshowPaused = { slideshowPaused = it },
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
            // The catalogue rows (tags, caption, location, rating) belong to library photos: an
            // external asset has none, and writing them would key rows to a synthetic id.
            catalogueActions = !activeAsset.isExternal,
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
                // A swipe, the bar's Next: the next photo, in order. The sequence wraps while a
                // slideshow is up (running or paused), and ends otherwise.
                val nextIndex = when {
                    selectedIndex < viewerAssets.lastIndex -> selectedIndex + 1
                    slideshowActive && viewerAssets.size > 1 -> 0
                    else -> -1
                }
                viewerAssets.getOrNull(nextIndex)?.let { selectedAssetId = it.id }
            },
            // The slideshow's own countdown. Shuffle belongs here and only here: it used to live
            // in onNext, so a swipe during a shuffled slideshow answered with a random photo,
            // which the comment on it said was a bug, not a setting. The screen never calls this
            // while the slideshow is paused or not running.
            onSlideshowAdvance = {
                val nextIndex = when {
                    preferences.slideshowShuffle && viewerAssets.size > 1 ->
                        randomOtherIndex(viewerAssets.size, selectedIndex)
                    selectedIndex < viewerAssets.lastIndex -> selectedIndex + 1
                    viewerAssets.size > 1 -> 0
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
            animated = activeAsset.id in animatedIds,
            autoplayVideos = preferences.autoplayVideos,
            pinchMode = preferences.viewerPinchMode,
            transparencyBackground = preferences.viewerTransparencyBackground,
            onSetSlideshowInterval = galleryPreferences::setSlideshowInterval,
            onSetBlurSensitive = galleryPreferences::setBlurSensitive,
            onSetShowFilmstrip = galleryPreferences::setShowFilmstrip,
            onSetKeepScreenOn = galleryPreferences::setKeepScreenOn,
            onSetSlideshowShuffle = galleryPreferences::setSlideshowShuffle,
            onSetLoopAnimations = galleryPreferences::setLoopAnimations,
            onSetAutoplayVideos = galleryPreferences::setAutoplayVideos,
            onSetPinchMode = galleryPreferences::setViewerPinchMode,
            onSetTransparencyBackground = galleryPreferences::setViewerTransparencyBackground,
            relatedAssets = viewerAssets,
            onSelectAsset = { picked ->
                // From the filmstrip, or from a dot on the expanded map. The filmstrip only ever
                // offers assets being paged through; the map offers the whole library, and a photo
                // outside the current run is opened on its own so the dot is never a dead tap.
                if (viewerAssets.any { it.id == picked.id }) {
                    selectedAssetId = picked.id
                    slideshowActive = false
                } else if (assets.any { it.id == picked.id }) {
                    setViewerAssets(listOf(picked))
                    selectedAssetId = picked.id
                    slideshowActive = false
                }
            },
        )
    } else {
        // Held under its own key so that opening a photo and coming Back finds the browser as it was
        // left (see [GALLERY_STATE_KEY]); the editor and the viewer are siblings of this branch.
        galleryStateHolder.SaveableStateProvider(GALLERY_STATE_KEY) {
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
                    partialMediaAccess = partialMediaAccess,
                    mediaPermissionPermanentlyDenied = mediaPermissionPermanentlyDenied,
                    scanState = scanState,
                    preferences = preferences,
                    recognition = recognition,
                    recognitionProgress = recognitionProgress,
                    animatedIds = animatedIds,
                    pendingSearch = pendingSearch,
                ),
                actions = GalleryActions(
                    onRequestPermission = ::requestMediaPermissionOrOpenSettings,
                    onRefresh = { runtime.requestScan(true) },
                    onSetSort = galleryPreferences::setSort,
                    onSetGridZoomRung = galleryPreferences::setGridZoomRung,
                    onSetBlurSensitive = galleryPreferences::setBlurSensitive,
                    onSetHideSensitive = galleryPreferences::setHideSensitive,
                    onSetShowVideos = galleryPreferences::setShowVideos,
                    onSetTimelineGrouping = galleryPreferences::setTimelineGrouping,
                    onSetTimelineOn = galleryPreferences::setTimelineOn,
                    onSetTimelinePlacement = galleryPreferences::setTimelinePlacement,
                    onSetScrollAxis = galleryPreferences::setScrollAxis,
                    onSetThemeMode = galleryPreferences::setThemeMode,
                    onSetAccentPalette = galleryPreferences::setAccentPalette,
                    onSetCustomTheme = galleryPreferences::setCustomTheme,
                    onSetSlideshowInterval = galleryPreferences::setSlideshowInterval,
                    onSetDefaultDestination = galleryPreferences::setDefaultDestination,
                    onSetKeepScreenOn = galleryPreferences::setKeepScreenOn,
                    onSetSlideshowShuffle = galleryPreferences::setSlideshowShuffle,
                    onSetAutoplayVideos = galleryPreferences::setAutoplayVideos,
                    onSetFitToTile = galleryPreferences::setFitToTile,
                    onSetLoopAnimations = galleryPreferences::setLoopAnimations,
                    onSetGridLongPress = galleryPreferences::setGridLongPress,
                    onSetShowFilmstrip = galleryPreferences::setShowFilmstrip,
                    onEditAsset = { asset ->
                        if (!viewModel.setEditingAsset(asset)) {
                            transientNotice = TransientNotice(FINISH_EDIT_FIRST_MESSAGE)
                        }
                    },
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
                            zipExporter.export(items, preferences.toShareOptions().stripMetadata) { done, total ->
                                onProgress(if (total > 0) done.toFloat() / total else null)
                            }.map { export ->
                                unpreparedShareItemsMessage(export.failed)?.let { userMessage = it }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, export.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(intent, null))
                                ""
                            }
                        }
                    },
                    onExportMetadata = metadataBackup.saveToFile,
                    onImportMetadata = metadataBackup.restoreFromFile,
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
 * [MediaAsset] objects via [ExternalAssetLoader] -- that a pure unit test has no way to exercise.
 *
 * A Uri that names a row this app has catalogued (a `content://media/...` photo) is never loaded
 * as an "external" file: [lookupCatalogued] decides, so a photo the library is hiding (locked,
 * archived, trashed, sensitive-hidden) cannot be put on screen just because another app handed
 * over its Uri. A hidden one is skipped and reported through [onHidden]. A `content://media` Uri
 * that cannot be read as a row, or whose row MediaStore will not describe, is refused the same way
 * ([onUnverified]): it is never read as an ordinary file, since that would skip the lock.
 */
private suspend fun FotoXplorrActivity.processIncomingIntent(
    intent: Intent,
    loader: ExternalAssetLoader,
    lookupCatalogued: suspend (rowId: Long) -> OpenRequestLookup,
    onViewMedia: suspend (List<MediaAsset>) -> Unit,
    onEditImage: suspend (MediaAsset) -> Unit,
    onHidden: () -> Unit,
    onUnverified: () -> Unit,
) {
    val mimeType = intent.type ?: intent.data?.let { runCatching { contentResolver.getType(it) }.getOrNull() }
    // A hostile EXTRA_STREAM can throw BadParcelableException on extraction; that must read as
    // "no stream", not crash the activity every time it is relaunched from Recents.
    val streamUris = runCatching {
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it.toString()) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.map { it.toString() } ?: emptyList()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
    val route = classifyIncomingIntent(
        action = intent.action,
        dataUri = intent.data?.toString(),
        mimeType = mimeType,
        streamUris = streamUris,
    )
    var anyHidden = false
    var anyUnverified = false
    suspend fun load(uriString: String, isVideo: Boolean): MediaAsset? {
        when (val ref = classifyMediaStoreUri(uriString)) {
            MediaStoreUri.NotMediaStore -> Unit
            MediaStoreUri.Unreadable -> {
                anyUnverified = true
                return null
            }
            is MediaStoreUri.Row -> when (val found = lookupCatalogued(ref.id)) {
                is OpenRequestLookup.Found -> return found.asset
                OpenRequestLookup.Hidden -> {
                    anyHidden = true
                    return null
                }
                OpenRequestLookup.Unverified -> {
                    anyUnverified = true
                    return null
                }
                // Not scanned yet, and MediaStore says nothing hides it: an ordinary external file,
                // read through its own grant.
                OpenRequestLookup.Unknown -> Unit
            }
        }
        return runCatching {
            val uri = Uri.parse(uriString)
            if (isVideo) loader.loadVideo(uri) else loader.loadImage(uri)
        }.getOrNull()
    }
    when (route) {
        is IncomingIntentRoute.ViewMedia -> {
            val assets = route.uris.mapNotNull { load(it, route.isVideo) }
            if (assets.isNotEmpty()) onViewMedia(assets)
        }
        is IncomingIntentRoute.EditImage -> load(route.uri, isVideo = false)?.let { onEditImage(it) }
        IncomingIntentRoute.Unhandled -> Unit
    }
    if (anyHidden) onHidden() else if (anyUnverified) onUnverified()
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

// ScanState moved to LibraryRuntime.kt (P0-09): it is no longer only ever produced from this
// file's own scan loop, since LibraryRuntime's scan loop is what produces it now.

/**
 * A short-lived, non-modal result notice -- "Renamed to x.", "Copied 3 items." -- replacing the
 * blocking `AlertDialog` every successful action used to show. [onOpen], when set, is what a tap
 * on the notice does (opening the viewer on a freshly saved copy, say); the notice always
 * dismisses itself after [TRANSIENT_NOTICE_MILLIS] regardless of whether it was tapped.
 */
private data class TransientNotice(val message: String, val onOpen: (() -> Unit)? = null)

/** How long a [TransientNotice] stays up before dismissing itself. */
private const val TRANSIENT_NOTICE_MILLIS = 3_000L

/** Shown when another app asks to open a catalogued photo the library is hiding right now
 *  (locked folder, archived, in the trash, sensitive-hidden). It does not name the photo or the
 *  reason, and it is the whole answer: nothing is opened. */
internal const val OPEN_REQUEST_HIDDEN_MESSAGE = "That photo is hidden or locked in Fotoz, so it was not opened."

/** Shown when a `content://media` Uri cannot be checked against the library's privacy rules (it
 *  names no readable row, or MediaStore will not describe the row), so it is not opened. */
internal const val OPEN_REQUEST_UNVERIFIED_MESSAGE =
    "That photo could not be checked against your library, so it was not opened. Try again in a moment."

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
 * "The original could not be replaced -- save a copy instead?" Rendered over the still-open
 * editor rather than as a dialog that would hide it, because the whole point is that the edit the
 * person just made is still right there, unlost, waiting on this one decision.
 *
 * [message] says why, as it is (see `overwriteFailureOf`). When [originalAtRisk] the original may
 * be damaged and the edit is the only good version, so Discard is off and the copy is the way out.
 * While [copySaving] both buttons are off, so a double tap cannot save the copy twice.
 */
@Composable
private fun OverwriteDeclinedBanner(
    message: String,
    originalAtRisk: Boolean,
    copySaving: Boolean,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            Text(message, color = Color.White)
            Text(
                if (originalAtRisk) {
                    "The original may be damaged. Your edit is still here: save it as a new photo now."
                } else {
                    "Your edit is not lost -- save it as a new photo instead."
                },
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, enabled = !originalAtRisk && !copySaving) {
                    Text("Discard", color = Color.White.copy(alpha = if (originalAtRisk || copySaving) 0.3f else 0.7f))
                }
                TextButton(onClick = onSaveCopy, enabled = !copySaving) { Text(if (copySaving) "Saving…" else "Save a copy") }
            }
        }
    }
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
