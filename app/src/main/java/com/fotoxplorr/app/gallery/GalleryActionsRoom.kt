package com.fotoxplorr.app.gallery

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.fileops.BulkRenameOutcome
import com.fotoxplorr.app.fileops.MediaFileOperations
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle
import kotlinx.coroutines.launch

/**
 * The gallery's RIGHT room: what you can do with the view you are looking at.
 *
 * The counterpart of the viewer's actions room, and the point of it is that they are the same
 * room in the same place (owner, 2026-08-18: *"the info and actions views are applicable to the
 * gallery views just as much as the single photo view -- the info would be different as well as
 * actions, but the model needs to remain the same"*).
 *
 * So the whole app now reads one way round, whatever is on screen:
 *
 * | edge   | holds                                          |
 * |--------|------------------------------------------------|
 * | LEFT   | where you can go — the destination rail        |
 * | RIGHT  | what you can do here — this room               |
 * | TOP    | settings                                       |
 * | BOTTOM | what this is — counts here, EXIF in the viewer |
 *
 * The contents differ because a grid of 22,000 photos and one open photo can be *done* different
 * things to. The geography does not, which is the part a user actually memorises.
 *
 * This room also carries the way into selection mode. Long press used to start a selection and now
 * holds a preview instead (owner: *"the quick preview must disappear when the long press is
 * released"*), so an explicit, findable entry point had to exist somewhere — and "select photos"
 * is an action on the current view, which is exactly what this room is for.
 */
@Composable
fun GalleryActionsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    selection: GallerySelection,
    selectedAssets: List<MediaAsset>,
    currentIds: Set<MediaId>,
    inTrash: Boolean,
    inArchive: Boolean,
    tagRoute: BrowserRoute.Tag?,
    collectionRoute: BrowserRoute.Collection?,
    onSelectionChange: (GallerySelection) -> Unit,
    onRenameAsset: (MediaAsset) -> Unit,
    onAddToCollection: (Set<MediaId>) -> Unit,
    onAddTag: (Set<MediaId>) -> Unit,
    onStartSelection: () -> Unit,
    onNewCollection: () -> Unit,
    onCloseRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = state.preferences
    val selectionActive = selection.isActive

    // Bulk rename lives entirely in this room rather than going through `onRenameAsset` (which
    // only ever carried ONE asset, into a single-file dialog this room does not own). That means
    // this composable has to hold its own activity-result launcher and its own
    // MediaFileOperations instance instead of delegating up to the activity the way trash and
    // delete do — a composable CAN own a launcher (`rememberLauncherForActivityResult` is not
    // restricted to an Activity root), so this stays self-contained rather than needing a new
    // callback threaded through GalleryScreen and the activity above it.
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fileOperations = remember(context) { MediaFileOperations(context) }
    var renameTarget by remember { mutableStateOf<List<MediaAsset>?>(null) }
    var pendingConsentRename by remember { mutableStateOf<BulkRenameRequest?>(null) }
    var renameProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var renameOutcome by remember { mutableStateOf<BulkRenameOutcome?>(null) }

    fun runBulkRename(request: BulkRenameRequest) {
        coroutineScope.launch {
            renameProgress = 0 to request.assets.size
            renameOutcome = fileOperations.renameBatch(
                assets = request.assets,
                pattern = request.pattern,
                startAt = request.startAt,
            ) { completed, total -> renameProgress = completed to total }
            renameProgress = null
        }
    }

    // Android 11+ can gather consent for the WHOLE batch in one system dialog via
    // createWriteRequest, exactly the mechanism `requestMediaOperation`'s trash/restore/delete
    // flow already uses for a list of Uris -- this is that same pattern, just for a write instead
    // of a trash/delete request, and living here because only a composable can hold an activity
    // result launcher.
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val request = pendingConsentRename
        pendingConsentRename = null
        if (request == null) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            runBulkRename(request)
        } else {
            renameOutcome = BulkRenameOutcome(
                succeeded = emptyList(),
                failed = request.assets.map { it to "Android cancelled the rename request." },
            )
        }
    }

    fun startBulkRename(assets: List<MediaAsset>, pattern: String, startAt: Int) {
        val request = BulkRenameRequest(assets, pattern, startAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Ask up front for every file this app does not own, in one dialog, rather than
            // discovering the need file-by-file mid-batch the way a bare ContentResolver.update
            // loop would (each failure throwing its own RecoverableSecurityException).
            runCatching {
                MediaStore.createWriteRequest(context.contentResolver, assets.map { it.contentUri })
            }.onSuccess { pending ->
                pendingConsentRename = request
                consentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            }.onFailure { error ->
                renameOutcome = BulkRenameOutcome(
                    succeeded = emptyList(),
                    failed = assets.map { it to (error.message ?: "Could not request rename permission.") },
                )
            }
        } else {
            // No batched consent API below API 30; MediaFileOperations.renameBatch still handles
            // this correctly per-file (including the Android 10 RecoverableSecurityException
            // case, reported honestly rather than attempted as a dialog storm — see its KDoc).
            runBulkRename(request)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                start = RoomStyle.GutterStart,
                end = RoomStyle.GutterEnd,
                top = 28.dp,
                bottom = 28.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Everything a SELECTION can do, when there is one. This is where the top bar's overflow
        // menu went: the owner's mockup gives that bar exactly three glyphs and no fourth, and a
        // dropdown hanging off a 209dp strip was never the same idea as a room anyway. "What you
        // can do here" is this room's whole job, and with photos chosen, this is what that means.
        if (selectionActive) {
            RoomEyebrow("${selection.count} SELECTED")
            SelectionActions(
                selection = selection,
                selectedAssets = selectedAssets,
                currentIds = currentIds,
                inTrash = inTrash,
                inArchive = inArchive,
                state = state,
                actions = actions,
                tagRoute = tagRoute,
                collectionRoute = collectionRoute,
                onSelectionChange = onSelectionChange,
                onRenameAsset = onRenameAsset,
                onBulkRename = { renameTarget = it },
                onAddToCollection = onAddToCollection,
                onAddTag = onAddTag,
                onCloseRoom = onCloseRoom,
            )
            RoomRule(Modifier.padding(vertical = 14.dp))
        }

        RoomEyebrow("THIS VIEW")

        RoomRow(
            label = if (selectionActive) "Selecting" else "Select photos",
            caption = if (selectionActive) {
                "Tap photos to add them. The count is at the bottom of the screen."
            } else {
                "Then tap the ones you want."
            },
            enabled = !selectionActive,
            onClick = onStartSelection,
        )

        RoomRow(
            label = "New collection",
            caption = "An album you make, rather than one a folder made for you.",
            onClick = onNewCollection,
        )

        RoomRow(
            label = "Rescan the library",
            caption = "Look for photos added or removed outside Foto Xplorr.",
            onClick = actions.onRefresh,
        )

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("ARRANGE")

        RoomRow(label = "Columns") {
            RoomStepper(
                value = "${preferences.gridColumns}",
                onDecrease = { actions.onSetGridColumns(preferences.gridColumns - 1) },
                onIncrease = { actions.onSetGridColumns(preferences.gridColumns + 1) },
                canDecrease = preferences.gridColumns > MIN_GRID_COLUMNS,
                canIncrease = preferences.gridColumns < MAX_GRID_COLUMNS,
            )
        }

        // Stated rather than ticked: a checkmark column would be the only iconography in a room
        // that has none, and the selected row stays full-strength while the alternatives mute --
        // the same way the destination rail marks the destination you are in.
        GallerySort.entries.forEach { sort ->
            val current = preferences.sort == sort
            RoomRow(
                label = sortLabel(sort),
                onClick = { actions.onSetSort(sort) },
            ) { if (current) RoomValue("now") }
        }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("SHOW")

        RoomRow(
            label = "Videos",
            caption = "Include videos alongside photos.",
            onClick = { actions.onSetShowVideos(!preferences.showVideos) },
        ) { RoomToggle(preferences.showVideos, actions.onSetShowVideos) }

        RoomRow(
            label = "Blur sensitive",
            caption = "Photos you have marked sensitive are blurred in the grid.",
            onClick = { actions.onSetBlurSensitive(!preferences.blurSensitive) },
        ) { RoomToggle(preferences.blurSensitive, actions.onSetBlurSensitive) }

        RoomRow(
            label = "Hide sensitive entirely",
            caption = "They are left out of the grid rather than blurred in it.",
            onClick = { actions.onSetHideSensitive(!preferences.hideSensitive) },
        ) { RoomToggle(preferences.hideSensitive, actions.onSetHideSensitive) }
    }

    // Rendered outside the scrolling Column deliberately -- an AlertDialog is its own window
    // regardless of where in the composition it is declared, but keeping it out of the Column
    // keeps it from being pulled into that Column's scroll/measure pass for no reason.
    renameTarget?.let { assets ->
        BulkRenameDialog(
            assets = assets,
            onDismiss = { renameTarget = null },
            onConfirm = { pattern, startAt ->
                renameTarget = null
                startBulkRename(assets, pattern, startAt)
            },
        )
    }
    renameProgress?.let { (completed, total) ->
        BulkRenameProgressDialog(completed = completed, total = total)
    }
    renameOutcome?.let { outcome ->
        BulkRenameResultDialog(
            outcome = outcome,
            onDismiss = {
                renameOutcome = null
                // Only clear the selection and leave the room on a rename that actually did
                // something -- a batch that failed outright (permission denied, bad pattern)
                // should leave the user exactly where they were, selection intact, so they can
                // see what happened and try again rather than losing their picks along with the
                // failure.
                if (outcome.succeeded.isNotEmpty()) {
                    actions.onRefresh()
                    onSelectionChange(selection.clear())
                    onCloseRoom()
                }
            },
        )
    }
}

/** One bulk-rename request, from the moment the pattern dialog is confirmed to the moment it runs. */
private data class BulkRenameRequest(
    val assets: List<MediaAsset>,
    val pattern: String,
    val startAt: Int,
)

/** A stated value at the end of a row, in the room's own muted ink. */
@Composable
private fun RoomValue(text: String) {
    Text(text = text, color = RoomStyle.InkFaint, style = RoomStyle.Caption)
}

/** Sort orders in the words a reader uses, not the enum's. */
private fun sortLabel(sort: GallerySort): String = when (sort) {
    GallerySort.NEWEST -> "Newest first"
    GallerySort.OLDEST -> "Oldest first"
    GallerySort.NAME -> "By name"
    GallerySort.SIZE -> "Largest first"
}

/**
 * What can be done to the photos currently chosen.
 *
 * Every one of these was a `DropdownMenuItem` hanging off the selection bar's overflow glyph. The
 * owner's mockup gives that bar exactly three glyphs and no fourth, so the menu had nowhere to
 * hang — and rows in a room are a better home for them regardless: they carry a line of
 * explanation, they are reachable one-handed from the right edge, and they do not vanish the
 * moment a finger slips.
 *
 * Ordered by how much they change: marks first, then filing, then the destructive end. `Move to
 * trash` stays here as well as on the bottom-right block because the two are reached from opposite
 * corners and someone deep in this room should not have to leave it to finish.
 */
@Composable
private fun SelectionActions(
    selection: GallerySelection,
    selectedAssets: List<MediaAsset>,
    currentIds: Set<MediaId>,
    inTrash: Boolean,
    inArchive: Boolean,
    state: GalleryUiState,
    actions: GalleryActions,
    tagRoute: BrowserRoute.Tag?,
    collectionRoute: BrowserRoute.Collection?,
    onSelectionChange: (GallerySelection) -> Unit,
    onRenameAsset: (MediaAsset) -> Unit,
    onBulkRename: (List<MediaAsset>) -> Unit,
    onAddToCollection: (Set<MediaId>) -> Unit,
    onAddTag: (Set<MediaId>) -> Unit,
    onCloseRoom: () -> Unit,
) {
    // Every action that finishes the job clears the selection and closes the room, because the
    // result of an action landing behind the panel that triggered it is the classic way to make a
    // control feel like it did nothing.
    fun finish(block: () -> Unit) {
        block()
        onSelectionChange(selection.clear())
        onCloseRoom()
    }

    RoomRow(
        label = "Select all",
        caption = "Everything in this view.",
        onClick = { onSelectionChange(selection.selectAll(currentIds)) },
    )

    if (inTrash) {
        RoomRow(
            label = "Restore",
            caption = "Put these back where they came from.",
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            onClick = { finish { actions.onRestore(selectedAssets) } },
        )
        RoomRow(
            label = "Delete permanently",
            caption = "Gone for good. Android will ask you to confirm.",
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            onClick = { finish { actions.onDeletePermanently(selectedAssets) } },
        )
        return
    }

    RoomRow(
        label = "Share",
        caption = "With frames, a seal, and metadata stripped unless you say otherwise.",
        onClick = { finish { actions.onShare(selectedAssets) } },
    )
    RoomRow(
        label = if (bulkMarkAction(selection.selectedIds, state.favoriteIds) == BulkMarkAction.MARK) {
            "Add to favourites"
        } else {
            "Remove from favourites"
        },
        onClick = {
            val mark = bulkMarkAction(selection.selectedIds, state.favoriteIds) == BulkMarkAction.MARK
            finish { actions.onSetFavorite(selection.selectedIds, mark) }
        },
    )
    RoomRow(
        label = if (bulkMarkAction(selection.selectedIds, state.sensitiveIds) == BulkMarkAction.MARK) {
            "Mark sensitive"
        } else {
            "Unmark sensitive"
        },
        caption = "Sensitive photos are blurred in the grid, or hidden entirely.",
        onClick = {
            val mark = bulkMarkAction(selection.selectedIds, state.sensitiveIds) == BulkMarkAction.MARK
            finish { actions.onSetSensitive(selection.selectedIds, mark) }
        },
    )
    RoomRow(
        label = if (inArchive) "Unarchive" else "Archive",
        caption = "Out of the main grid, still in your library.",
        onClick = { finish { actions.onSetArchived(selection.selectedIds, !inArchive) } },
    )

    RoomRule(Modifier.padding(vertical = 10.dp))

    RoomRow(
        label = "Add to collection",
        onClick = { onCloseRoom(); onAddToCollection(selection.selectedIds) },
    )
    RoomRow(label = "Add tag", onClick = { onCloseRoom(); onAddTag(selection.selectedIds) })
    tagRoute?.let { tag ->
        RoomRow(
            label = "Remove #${tag.tag}",
            onClick = { finish { actions.onRemoveTag(selection.selectedIds, tag.tag) } },
        )
    }
    collectionRoute?.let { collection ->
        RoomRow(
            label = "Remove from this collection",
            onClick = { finish { actions.onRemoveFromCollection(collection.id, selection.selectedIds) } },
        )
    }
    // A single photo keeps the existing one-name-at-a-time dialog. More than one uses a naming
    // PATTERN instead — that used to mean `onRenameAsset(selectedAssets.first())`, silently
    // renaming exactly one of however many were selected and leaving the rest untouched, which is
    // the bug the owner asked for bulk rename to fix.
    if (selectedAssets.size == 1) {
        RoomRow(
            label = "Rename",
            onClick = { onCloseRoom(); onRenameAsset(selectedAssets.first()) },
        )
    } else if (selectedAssets.isNotEmpty()) {
        RoomRow(
            label = "Rename ${selectedAssets.size} photos",
            caption = "One naming pattern, applied to all of them.",
            onClick = { onBulkRename(selectedAssets) },
        )
    }

    RoomRow(
        label = "Move to trash",
        caption = "Also the block in the bottom-right corner of the grid.",
        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        onClick = { finish { actions.onMoveToTrash(selectedAssets) } },
    )
}
