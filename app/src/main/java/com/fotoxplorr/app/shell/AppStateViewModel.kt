package com.fotoxplorr.app.shell

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fotoxplorr.app.editor.OverwriteException
import com.fotoxplorr.app.editor.quotedPhotoName
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.JobRunnerRegistry
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.app.metadata.MetadataEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /**
     * Opens the editor on [asset], or closes it for null; true when it did. The failed-overwrite
     * state belongs to the one photo whose replace failed, so it is cleared whenever the editor
     * closes or opens on a different photo: left behind, the next editor would show the old
     * banner, and "Save a copy" would write the old photo's bitmap.
     *
     * While [editorCloseBlocked] a change to a different photo (or to none) is refused and returns
     * false with nothing changed: a replace is in flight, or it failed and the held bitmap is the
     * only good version of the photo. Every path that opens an editor on someone else's say-so (an
     * EDIT or SEND intent) reaches this, so none is a side door around the block. The paths that
     * end the replace themselves ([finishOverwriteSucceeded], [finishCopyOfDeclinedOverwrite]) clear
     * the state first, which unblocks them.
     */
    fun setEditingAsset(asset: MediaAsset?): Boolean {
        val sameAsset = asset != null && asset.id == editingAssetState?.id
        if (!sameAsset && editorCloseBlocked) return false
        if (!sameAsset) clearOverwriteFailure()
        editingAssetState = asset
        editingAssetIdState = asset?.id
        writeId(KEY_EDITING_ASSET_ID, asset?.id)
        return true
    }

    /**
     * True while the editor must not be dismissed or pointed at another photo:
     *  - a replace of the original is in flight ([overwriteInFlight]): the write is running or its
     *    consent sheet is up, and closing now would drop the bitmap while the file is half written;
     *  - a copy of the edit is being saved ([overwriteCopySaving]);
     *  - a save is still being rendered or written ([saveInProgress]);
     *  - the replace failed after the original was opened and it could not be put back, so the
     *    edited bitmap held here is the only good version. Back and close then change nothing and
     *    the banner (which offers only "Save a copy") stays up.
     */
    val editorCloseBlocked: Boolean
        get() = saveInProgress || overwriteInFlight || overwriteCopySaving || (overwriteDeclined && overwriteOriginalAtRisk)

    /** Closes the editor unless [editorCloseBlocked]; true when it closed. */
    fun closeEditor(): Boolean = setEditingAsset(null)

    /**
     * Closes the editor only if it is still open on the photo [assetId], for a save that reports
     * back late: its result must not close an editor that has since been pointed at another photo.
     */
    fun closeEditorFor(assetId: MediaId): Boolean =
        editingAssetState?.id == assetId && setEditingAsset(null)

    /**
     * True from Save being tapped until that save has reached its outcome, a copy or a replace
     * request (which then holds the editor itself, see [overwriteInFlight]). The render alone takes
     * seconds, and the editor reports it from its own `saving`; without this the editor could be
     * switched to another photo meanwhile (an EDIT or SEND intent), and the late result of the
     * first photo's save would land on the second photo's editor. Always cleared by the editor
     * when the save ends or the editor leaves composition.
     */
    var saveInProgress: Boolean by mutableStateOf(false)

    /** Forgets a failed overwrite and the photo and bitmap it was holding for "Save a copy". */
    fun clearOverwriteFailure() {
        pendingOverwriteAsset = null
        pendingOverwriteBitmap = null
        overwriteDeclined = false
        overwriteFailureMessage = null
        overwriteOriginalAtRisk = false
    }

    /**
     * Non-null exactly while a consent sheet for replacing [pendingOverwriteAsset]'s own file is
     * outstanding, the write is running, or the replace was just declined or failed — see
     * [overwriteDeclined]. Kept separate from [editingAsset] being non-null: the editor stays open
     * across the whole consent round trip (that is the fix — see docs on `requestOverwrite`),
     * while this pair describes only the write attempt itself.
     */
    var pendingOverwriteAsset: MediaAsset? by mutableStateOf(null)
    var pendingOverwriteBitmap: Bitmap? by mutableStateOf(null)

    /**
     * True after the user has said no to Android's own "allow Foto Xplorr to modify this file?"
     * sheet, or the replace failed, so the editor can offer "Save a copy instead" using the very
     * bitmap it already rendered — [pendingOverwriteBitmap] — rather than losing the edit outright.
     */
    var overwriteDeclined: Boolean by mutableStateOf(false)

    /**
     * Why the last attempt to replace the original failed, in words to show as they are; non-null
     * only while [overwriteDeclined] is. See [overwriteFailureOf] for the wording per cause.
     */
    var overwriteFailureMessage: String? by mutableStateOf(null)

    /**
     * True when a failed write may have left the original damaged (it was opened, the write
     * failed, and putting the old bytes back failed too). The editor then keeps the edited bitmap
     * and offers only "Save a copy": a discard would throw away the only good version.
     *
     * Sticky: once set it stays set through a further attempt that fails safely or is declined, and
     * only a replace that succeeds, a copy that was saved, or an explicit [clearOverwriteFailure]
     * clears it. Otherwise a second try that Android refused would re-enable Discard and Back while
     * the original is still damaged.
     */
    var overwriteOriginalAtRisk: Boolean by mutableStateOf(false)

    /** True while "Save a copy" of a failed replace is running, so a second tap does nothing. */
    var overwriteCopySaving: Boolean by mutableStateOf(false)
        private set

    /** True from the moment a replace is requested until its outcome is recorded. */
    val overwriteInFlight: Boolean get() = pendingOverwriteAsset != null && !overwriteDeclined

    /**
     * Whether an outcome for the replace now being waited on still belongs to the open editor: a
     * replace was requested for the photo the editor is open on. An outcome that arrives when it
     * does not (the editor closed or moved on, which [editorCloseBlocked] prevents, or the state
     * was cleared) must change nothing, least of all show one photo's banner over another's editor.
     */
    fun overwriteOutcomeApplies(): Boolean {
        val pending = pendingOverwriteAsset ?: return false
        return pending.id == editingAssetState?.id
    }

    /**
     * Records that a replace of [asset] with [bitmap] is starting; false (and nothing changed) when
     * one is already in flight or a copy is being saved, or when [asset] is not the photo the editor
     * is open on. The at-risk flag is deliberately NOT reset: a retry does not make a damaged
     * original safe until it succeeds.
     */
    fun beginOverwrite(asset: MediaAsset, bitmap: Bitmap): Boolean {
        if (overwriteInFlight || overwriteCopySaving) return false
        if (asset.id != editingAssetState?.id) return false
        pendingOverwriteAsset = asset
        pendingOverwriteBitmap = bitmap
        overwriteDeclined = false
        overwriteFailureMessage = null
        return true
    }

    /**
     * The replace failed or was declined. Keeps the editor open on the banner. Ignored (false) when
     * [overwriteOutcomeApplies] is false. [OverwriteFailure.originalAtRisk] is OR-ed into the flag
     * rather than assigned: see [overwriteOriginalAtRisk].
     */
    fun recordOverwriteFailure(failure: OverwriteFailure): Boolean {
        if (!overwriteOutcomeApplies()) return false
        overwriteFailureMessage = failure.message
        overwriteOriginalAtRisk = overwriteOriginalAtRisk || failure.originalAtRisk
        overwriteDeclined = true
        return true
    }

    /**
     * The replace succeeded: forgets the failure state and closes the editor. Ignored (false) when
     * [overwriteOutcomeApplies] is false.
     */
    fun finishOverwriteSucceeded(): Boolean {
        if (!overwriteOutcomeApplies()) return false
        clearOverwriteFailure()
        return setEditingAsset(null)
    }

    /**
     * "Save a copy" is starting; false when there is nothing held to save or a copy is already
     * being saved (a double tap). While true Back and the banner's Discard are off.
     */
    fun beginCopyOfDeclinedOverwrite(): Boolean {
        if (!overwriteDeclined || pendingOverwriteAsset == null || pendingOverwriteBitmap == null) return false
        if (overwriteCopySaving) return false
        overwriteCopySaving = true
        return true
    }

    /**
     * The copy finished. On success the failure state is forgotten (the edit is saved, and the
     * original, however damaged, is no longer the only place it lives) and the editor closes. On
     * failure everything stays as it was: the banner, the at-risk flag and the held bitmap, so the
     * person can retry.
     *
     * Returns true when the copy was saved and the state it cleared had the original at risk: the
     * copy does not repair the original, so the caller must say that it may still be damaged.
     */
    fun finishCopyOfDeclinedOverwrite(saved: Boolean): Boolean {
        overwriteCopySaving = false
        if (saved && overwriteOutcomeApplies()) {
            val wasAtRisk = overwriteOriginalAtRisk
            clearOverwriteFailure()
            setEditingAsset(null)
            return wasAtRisk
        }
        return false
    }

    /**
     * Runs a replace or a "Save a copy" on this ViewModel's scope instead of the composition's: an
     * Activity recreation cancels the composition's coroutines, which left the lock flags above set
     * for good (the editor stuck, no at-risk banner even if the write had failed). The write itself
     * is blocking file I/O and runs to its end either way; what this keeps alive is the code that
     * records how it ended.
     *
     * Backstop: if [block] dies without recording an outcome, [settleInterruptedWrite] does, so
     * the flags cannot stay set. (A cancellation by [onCleared] needs none: the state goes too.)
     */
    fun launchWrite(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            settleInterruptedWrite()
        }
    }

    /**
     * A write that was started ended without an outcome being recorded: nothing says what state
     * the original is in, so a replace is recorded as a failure with the original at risk, and a
     * copy that did not finish is released so it can be tried again.
     */
    fun settleInterruptedWrite() {
        overwriteCopySaving = false
        if (overwriteInFlight) recordOverwriteFailure(OverwriteFailure(overwriteInterruptedMessage(pendingOverwriteAsset?.displayName), originalAtRisk = true))
    }

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

/** Shown when a replace stopped without saying how it ended; see [AppStateViewModel.settleInterruptedWrite]. */
fun overwriteInterruptedMessage(photoName: String?): String {
    val photo = quotedPhotoName(photoName)?.let { "The original photo $it" } ?: "The original photo"
    return "A save was interrupted. $photo may be damaged. Save a copy of your edit now."
}

/** Said when "Save a copy" succeeded after a replace that left the original at risk: the copy does not repair it. */
fun copySavedOriginalAtRiskMessage(photoName: String?): String {
    val photo = quotedPhotoName(photoName)?.let { "The original $it" } ?: "The original"
    return "Saved a copy of your edit. $photo may still be damaged. Fotoz kept a private safety copy of it " +
        "and offers to share it."
}

/**
 * Whether the pending recovery notice must be recomputed now: a replace ended with the original
 * possibly damaged ([failure]), or a copy was saved while it still was ([copySavedOriginalAtRisk]).
 * The notice is otherwise computed once at start, so without this the safety copy kept by an
 * in-process failure would not be announced until the next cold start.
 */
fun shouldRefreshRecoveryNotice(failure: OverwriteFailure? = null, copySavedOriginalAtRisk: Boolean = false): Boolean =
    failure?.originalAtRisk == true || copySavedOriginalAtRisk

/** Shown when a replace could not start because another save of the editor had not finished. */
const val OVERWRITE_NOT_STARTED_MESSAGE = "That edit was not saved. Finish the current save first, then try again."

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
