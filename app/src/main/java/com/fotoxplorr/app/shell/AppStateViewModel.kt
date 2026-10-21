package com.fotoxplorr.app.shell

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fotoxplorr.app.editor.OverwriteException
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.JobRunnerRegistry
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.app.metadata.MetadataEdit
import kotlinx.coroutines.launch

/**
 * Everything [com.fotoxplorr.app.FotoXplorrActivity]'s navigation used to lose to a rotation,
 * held where a rotation cannot reach it.
 *
 * Two tiers, because the two failure modes are different:
 *
 *  - **Rotation** recreates the Activity and every `remember` in it, but this ViewModel instance
 *    is handed straight back by the same [androidx.lifecycle.ViewModelStore] — a plain
 *    `mutableStateOf` property here (the viewer's asset list, the editor's open asset, a pending
 *    copy/move) survives it with no extra work, the same way it always has for any ViewModel.
 *  - **Process death** does not preserve this instance at all; only [savedStateHandle] comes back,
 *    and only what was written into it as a primitive. The two ids below are exactly what
 *    [com.fotoxplorr.app.FotoXplorrActivity] needs to REBUILD its navigation once the repository
 *    has reloaded — open the same photo, keep editing the same asset
 *    — even though the richer objects those ids pointed to (the surrounding [viewerAssets] list,
 *    the decoded [pendingOverwriteBitmap]) are gone and are not attempted to be recovered.
 */
class AppStateViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    /** Runs conversions, editor saves, copies/moves and exports as cancellable, observable jobs. */
    val jobRunner = JobRunner(viewModelScope)

    init {
        // Mirrored so JobForegroundService -- which cannot reach this ViewModel -- has a job list
        // to build a notification from. See JobRunnerRegistry's own doc.
        viewModelScope.launch { jobRunner.jobs.collect(JobRunnerRegistry::publish) }
    }

    // ---- viewer ----

    /** The photos/videos the open viewer is paging through. Empty when the viewer is closed. */
    var viewerAssets: List<MediaAsset> by mutableStateOf(emptyList())

    private var selectedAssetIdState: MediaId? by mutableStateOf(readId(KEY_SELECTED_ASSET_ID))

    /** Which of [viewerAssets] is open. Mirrored into [savedStateHandle] on every write. */
    var selectedAssetId: MediaId?
        get() = selectedAssetIdState
        set(value) {
            selectedAssetIdState = value
            writeId(KEY_SELECTED_ASSET_ID, value)
        }

    var slideshowActive: Boolean by mutableStateOf(false)

    // ---- editor ----

    // Backed by a differently-named property (rather than `var editingAsset: MediaAsset? by
    // mutableStateOf(null) private set`) purely to dodge a JVM signature clash: a Kotlin property
    // named `editingAsset` with a private setter still compiles to a method literally called
    // `setEditingAsset`, which collides with the explicit one below. The public surface is the
    // read-only `editingAsset` val plus the `setEditingAsset` function -- nobody outside this
    // class ever sees `editingAssetState`.
    private var editingAssetState: MediaAsset? by mutableStateOf(null)

    /** The asset the in-app editor is open on, if any. */
    val editingAsset: MediaAsset? get() = editingAssetState

    private var editingAssetIdState: MediaId? by mutableStateOf(readId(KEY_EDITING_ASSET_ID))

    /** Survives process death even though [editingAsset] itself cannot; see the class doc. */
    val editingAssetId: MediaId? get() = editingAssetIdState

    fun setEditingAsset(asset: MediaAsset?) {
        editingAssetState = asset
        editingAssetIdState = asset?.id
        writeId(KEY_EDITING_ASSET_ID, asset?.id)
    }

    /**
     * Non-null exactly while a consent sheet for replacing [pendingOverwriteAsset]'s own file is
     * outstanding, or was just declined — see [overwriteDeclined]. Kept separate from
     * [editingAsset] being non-null: the editor stays open across the whole consent round trip
     * (that is the fix — see docs on `requestOverwrite`), while this pair describes only the
     * write attempt itself.
     */
    var pendingOverwriteAsset: MediaAsset? by mutableStateOf(null)
    var pendingOverwriteBitmap: Bitmap? by mutableStateOf(null)

    /**
     * True after the user has said no to Android's own "allow Foto Xplorr to modify this file?"
     * sheet, so the editor can offer "Save a copy instead" using the very bitmap it already
     * rendered — [pendingOverwriteBitmap] — rather than losing the edit outright.
     */
    var overwriteDeclined: Boolean by mutableStateOf(false)

    /**
     * Why the last attempt to replace the original failed, in words to show as they are; non-null
     * only while [overwriteDeclined] is. See [overwriteFailureOf] for the wording per cause.
     */
    var overwriteFailureMessage: String? by mutableStateOf(null)

    /**
     * True when the failed write may have left the original damaged (it was opened, the write
     * failed, and putting the old bytes back failed too). The editor then keeps the edited bitmap
     * and offers only "Save a copy": a discard would throw away the only good version.
     */
    var overwriteOriginalAtRisk: Boolean by mutableStateOf(false)

    // ---- video ----

    var convertingVideoId: MediaId? by mutableStateOf(null)

    // ---- pending Android consent round-trips (trash/restore/delete, rename, metadata) ----

    var pendingOperation: PendingMediaOperation? by mutableStateOf(null)
    var pendingOperationIds: Set<MediaId> by mutableStateOf(emptySet())
    var pendingTreeOperation: PendingTreeOperation? by mutableStateOf(null)
    var pendingTreeItems: List<MediaAsset> by mutableStateOf(emptyList())
    var pendingRenameAsset: MediaAsset? by mutableStateOf(null)
    var pendingRenameName: String? by mutableStateOf(null)
    var pendingMetadataAsset: MediaAsset? by mutableStateOf(null)
    var pendingMetadataEdit: MetadataEdit? by mutableStateOf(null)

    // ---- misc navigation ----

    /** Bumped whenever a metadata write lands, so the viewer's own EXIF/XMP cache re-reads. */
    var metadataRevision: Int by mutableStateOf(0)

    /** A failure that needs a decision -- shown as the one remaining blocking `AlertDialog`. Every
     *  plain "it worked" result goes through a transient notice instead (held in the Activity's
     *  own `remember`, not here: it is exactly the kind of short-lived UI state a rotation losing
     *  is an acceptable trade, unlike the rest of this class). */
    var userMessage: String? by mutableStateOf(null)

    /** A search handed over from the viewer's "Search inside this photo" card. One-shot. */
    var pendingSearch: String? by mutableStateOf(null)

    /** Non-null while the advanced share sheet is up. */
    var pendingShare: List<MediaAsset>? by mutableStateOf(null)

    var recognitionGeneration: Int by mutableStateOf(0)

    /**
     * True once the first (full) library scan has been requested for this process.
     *
     * The scan pipeline used to key its "first pass after a grant is a full one" purely on
     * `permissionGranted`, which a rotation does not change — but a rotation used to recreate the
     * whole composable tree from scratch (no ViewModel to remember anything in), so the
     * `LaunchedEffect(permissionGranted)` that sends that full-scan request ran again on every
     * single rotation. Reading and setting this flag from a ViewModel field is what stops a
     * 20,000-photo library being re-enumerated because the phone turned sideways.
     */
    var initialScanSent: Boolean by mutableStateOf(false)

    override fun onCleared() {
        jobRunner.cancelAll()
    }

    private fun readId(key: String): MediaId? = savedStateHandle.get<Long>(key)?.let(::MediaId)

    private fun writeId(key: String, id: MediaId?) {
        savedStateHandle[key] = id?.value
    }

    private companion object {
        const val KEY_SELECTED_ASSET_ID = "shell.selectedAssetId"
        const val KEY_EDITING_ASSET_ID = "shell.editingAssetId"
    }
}

/** What to tell the person when replacing the original failed; see [overwriteFailureOf]. */
data class OverwriteFailure(val message: String, val originalAtRisk: Boolean)

/** Shown when Android itself refused write access to the original (a declined consent sheet, or a
 *  [SecurityException]); the only cause this wording is true for. */
const val OVERWRITE_NOT_ALLOWED_MESSAGE = "Android did not allow replacing the original photo."

/**
 * Maps the failure of [com.fotoxplorr.app.editor.EditedCopyWriter.overwrite] to what the editor's
 * banner says, rather than blaming Android for every cause.
 *
 *  - [OverwriteException.Refused]: the original was never touched; its message names the reason
 *    (any reason, so a newly added one needs no change here).
 *  - [OverwriteException.FailedAfterWrite] with `restored`: the message already says the original
 *    was put back.
 *  - [OverwriteException.FailedAfterWrite] without it: the message is kept and the original is
 *    flagged at risk, so the banner steers to "Save a copy" and drops "Discard".
 *  - [SecurityException] (which includes the Android 10 recoverable kind): Android said no.
 *  - anything else failed before the original was opened for writing.
 */
fun overwriteFailureOf(error: Throwable?): OverwriteFailure = when (error) {
    is OverwriteException.Refused -> OverwriteFailure(error.message.orEmpty().ifBlank { GENERIC_OVERWRITE_FAILURE }, false)
    is OverwriteException.FailedAfterWrite ->
        OverwriteFailure(error.message.orEmpty().ifBlank { GENERIC_OVERWRITE_FAILURE }, originalAtRisk = !error.restored)
    is SecurityException -> OverwriteFailure(OVERWRITE_NOT_ALLOWED_MESSAGE, false)
    else -> OverwriteFailure(GENERIC_OVERWRITE_FAILURE, false)
}

private const val GENERIC_OVERWRITE_FAILURE = "Could not replace the original photo, so it was left as it is."
