package com.fotoxplorr.app.gallery

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.fileops.BulkRenameOutcome
import com.fotoxplorr.app.fileops.MediaFileOperations
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.ActionsRoomFrame
import com.fotoxplorr.app.ui.QuickActionItem
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.launch
import com.fotoxplorr.app.ui.HelpCopy

/**
 * The gallery's BOTTOM room: what you can do with the view you are looking at.
 *
 * The counterpart of the viewer's actions room, and the point of it is that they are the same
 * room in the same place (owner, 2026-08-18: *"the info and actions views are applicable to the
 * gallery views just as much as the single photo view -- the info would be different as well as
 * actions, but the model needs to remain the same"*).
 *
 * So the whole app reads one way round, whatever is on screen ([RoomGeography] holds the table):
 *
 * | edge   | holds                                                            |
 * |--------|------------------------------------------------------------------|
 * | LEFT   | where you can go — the destination rail                          |
 * | TOP    | details — counts here (GalleryInfoRoom), the photo's facts there |
 * | BOTTOM | actions — this room                                              |
 * | RIGHT  | view options — columns and sort here, the filmstrip there        |
 *
 * The contents differ because a grid of 22,000 photos and one open photo can be *done* different
 * things to. The geography does not, which is the part a user actually memorises.
 *
 * The view options that used to share this room (ARRANGE and SHOW) moved to
 * [GalleryViewOptionsRoom] at the right: you want to see the effect of those, whereas an action
 * does not need the grid in view, so tilting the grid up out of the way suits this room.
 *
 * It is the two-stage room: a short pull stops on a strip of the few actions people reach for most
 * ([galleryQuickActions]; with a selection, the selection's) and a longer one carries on to everything below it. The strip is the
 * room's head, which is the part of a bottom room that arrives first.
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
    /** [dev.aarso.cellshell.SpatialController.hasBottomPeek]: false on a window too short for the
     *  peek, where the room is single-stage and the strip would only repeat the rows below it. */
    hasBottomPeek: Boolean = true,
    modifier: Modifier = Modifier,
) {
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

    // Rescanning closes the room, like every other action that finishes the job: its result lands
    // behind the panel that triggered it. The strip's Refresh and the full list's "Rescan the
    // library" are one handler, so they cannot disagree about that.
    val refreshLibrary = { onCloseRoom(); actions.onRefresh() }

    // The strip is the room's first inch (it is what a short pull stops on, see RoomGeography), so
    // it is built from the same handlers the rows below use: browsing, the room's own entry
    // points; with a selection, the selection's actions through the same finish{} the rows use.
    val selectedCount = selection.count
    val markFavourite = bulkMarkAction(selection.selectedIds, state.favoriteIds) == BulkMarkAction.MARK
    val favouritesOn = galleryFavouriteStripOn(selectedCount, markFavourite)
    val quickActions = galleryQuickActions(selectionActive, inTrash).map { action ->
        val stripFavouriteOn = action == GalleryQuickAction.FAVOURITE && favouritesOn
        QuickActionItem(
            label = galleryQuickActionLabel(action, stripFavouriteOn),
            icon = action.icon,
            onClick = when (action) {
                GalleryQuickAction.SELECT -> onStartSelection
                GalleryQuickAction.NEW_COLLECTION -> onNewCollection
                GalleryQuickAction.REFRESH -> refreshLibrary
                GalleryQuickAction.SHARE -> ({
                    finishSelectionAction(selection, onSelectionChange, onCloseRoom) { actions.onShare(selectedAssets) }
                })
                GalleryQuickAction.ADD_TO_COLLECTION -> ({
                    onCloseRoom()
                    onAddToCollection(selection.selectedIds)
                })
                GalleryQuickAction.FAVOURITE -> ({
                    finishSelectionAction(selection, onSelectionChange, onCloseRoom) {
                        actions.onSetFavorite(selection.selectedIds, markFavourite)
                    }
                })
                GalleryQuickAction.MOVE_TO_TRASH -> ({
                    finishSelectionAction(selection, onSelectionChange, onCloseRoom) {
                        actions.onMoveToTrash(selectedAssets)
                    }
                })
                GalleryQuickAction.RESTORE -> ({
                    finishSelectionAction(selection, onSelectionChange, onCloseRoom) { actions.onRestore(selectedAssets) }
                })
                GalleryQuickAction.DELETE_PERMANENTLY -> ({
                    finishSelectionAction(selection, onSelectionChange, onCloseRoom) {
                        actions.onDeletePermanently(selectedAssets)
                    }
                })
            },
            enabled = galleryQuickActionEnabled(action, selectedCount, Build.VERSION.SDK_INT),
            // Favourite reads as "on" when every chosen photo already is one: tapping removes,
            // so the word says "Unfavourite" and a screen reader is told the state it is in.
            highlighted = stripFavouriteOn,
            stateDescription = if (stripFavouriteOn) "Favourited" else null,
        )
    }

    ActionsRoomFrame(
        quickActions = quickActions,
        showStrip = hasBottomPeek,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = RoomStyle.GutterStart,
            end = RoomStyle.GutterEnd,
            top = 16.dp,
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

        // Not "THIS VIEW": that is the details room's own eyebrow, from the top edge.
        RoomEyebrow("ORGANISE")

        RoomRow(
            label = if (selectionActive) "Selecting" else "Select photos",
            caption = if (selectionActive) {
                HelpCopy.Caption.SELECT_WHILE_SELECTING
            } else {
                HelpCopy.Caption.SELECT_TO_START
            },
            enabled = !selectionActive,
            onClick = onStartSelection,
        )

        RoomRow(
            label = "New collection",
            caption = HelpCopy.Caption.NEW_COLLECTION,
            onClick = onNewCollection,
        )

        RoomRow(
            label = "Rescan the library",
            caption = HelpCopy.Caption.RESCAN,
            onClick = refreshLibrary,
        )
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

/**
 * What can be done to the photos currently chosen.
 *
 * Every one of these was a `DropdownMenuItem` hanging off the selection bar's overflow glyph. The
 * owner's mockup gives that bar exactly three glyphs and no fourth, so the menu had nowhere to
 * hang — and rows in a room are a better home for them regardless: they carry a line of
 * explanation, they are reachable one-handed from the bottom edge, and they do not vanish the
 * moment a finger slips.
 *
 * Ordered by how much they change: marks first, then filing, then the destructive end. `Move to
 * trash` stays here as well as on the bottom-right block because the two are reached from different
 * places and someone deep in this room should not have to leave it to finish.
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
    fun finish(block: () -> Unit) = finishSelectionAction(selection, onSelectionChange, onCloseRoom, block)

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
        caption = HelpCopy.Caption.SHARE_DEFAULT,
        onClick = { finish { actions.onShare(selectedAssets) } },
    )
    RoomRow(
        label = "Share with options…",
        caption = HelpCopy.Caption.SHARE_OPTIONS,
        onClick = { finish { actions.onShareClean(selectedAssets) } },
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
        caption = HelpCopy.Caption.BLUR_OR_HIDE,
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
        caption = HelpCopy.Caption.TRASH_ALSO,
        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        onClick = { finish { actions.onMoveToTrash(selectedAssets) } },
    )
}

/**
 * Finish a selection action: run it, clear the selection, close the room. The rows of the full
 * list and the cells of the quick-action strip both go through this, so a strip cell and its row
 * cannot leave the room in different states.
 */
private fun finishSelectionAction(
    selection: GallerySelection,
    onSelectionChange: (GallerySelection) -> Unit,
    onCloseRoom: () -> Unit,
    block: () -> Unit,
) {
    block()
    onSelectionChange(selection.clear())
    onCloseRoom()
}

/**
 * The gallery's quick actions, in the order they sit in the strip. [label] is the short word under
 * the glyph; the full row for the same action, further down the room, keeps its explanatory caption.
 * Four cells share a 360dp phone, about 75dp of text each at 12sp, so the selection's labels are
 * single short words ("Collection", "Trash"); the rows below keep "Add to collection" and "Move to
 * trash" in full.
 *
 * The strip is context-aware, as the rows are: with nothing chosen it is the room's own entry points
 * (SELECT, NEW_COLLECTION, REFRESH); once a selection is under way it is the selection's most used
 * actions (SHARE, ADD_TO_COLLECTION, FAVOURITE, MOVE_TO_TRASH), or RESTORE and DELETE_PERMANENTLY in
 * the trash, where the list offers no more than those.
 */
enum class GalleryQuickAction(val label: String, val icon: ImageVector) {
    SELECT("Select", Icons.Outlined.CheckCircleOutline),
    NEW_COLLECTION("New collection", Icons.Outlined.CreateNewFolder),
    REFRESH("Refresh", Icons.Outlined.Refresh),
    SHARE("Share", Icons.Outlined.Share),
    ADD_TO_COLLECTION("Collection", Icons.Outlined.Folder),
    FAVOURITE("Favourite", Icons.Outlined.FavoriteBorder),
    MOVE_TO_TRASH("Trash", Icons.Outlined.DeleteOutline),
    RESTORE("Restore", Icons.Outlined.Restore),
    DELETE_PERMANENTLY("Delete forever", Icons.Outlined.DeleteForever),
}

/**
 * The quick actions allowed right now. Once a selection is under way, starting one is not an
 * action any more, and New collection and Refresh do not act on the chosen photos (reaching the
 * real selection actions would need the longer pull), so the strip becomes the selection's own.
 * An empty answer would mean no strip and no peek stop; no state currently gives one.
 */
fun galleryQuickActions(selectionActive: Boolean, inTrash: Boolean = false): List<GalleryQuickAction> = when {
    !selectionActive -> listOf(
        GalleryQuickAction.SELECT,
        GalleryQuickAction.NEW_COLLECTION,
        GalleryQuickAction.REFRESH,
    )
    inTrash -> listOf(GalleryQuickAction.RESTORE, GalleryQuickAction.DELETE_PERMANENTLY)
    else -> listOf(
        GalleryQuickAction.SHARE,
        GalleryQuickAction.ADD_TO_COLLECTION,
        GalleryQuickAction.FAVOURITE,
        GalleryQuickAction.MOVE_TO_TRASH,
    )
}

/**
 * Whether the strip's Favourite cell reads as switched on: a selection exists and every photo in it
 * is already a favourite, so a tap removes them ([markFavourite] false is the bulk rule's answer
 * for exactly that).
 */
fun galleryFavouriteStripOn(selectedCount: Int, markFavourite: Boolean): Boolean =
    selectedCount > 0 && !markFavourite

/**
 * The word under [action]'s glyph. Favourite becomes "Unfavourite" when it is [favouriteOn],
 * because the cell then removes favourites and the tint alone must not be the only thing saying so
 * (TalkBack would otherwise read "Favourite, button" for a cell that un-favourites).
 */
fun galleryQuickActionLabel(action: GalleryQuickAction, favouriteOn: Boolean = false): String =
    if (action == GalleryQuickAction.FAVOURITE && favouriteOn) "Unfavourite" else action.label

/**
 * Whether [action]'s cell answers. A selection action needs a photo chosen (a selection that has
 * just begun is empty), and trash, restore and permanent delete need Android 11's batched system
 * request, exactly as the rows' own `enabled` says. Browsing actions always answer.
 */
fun galleryQuickActionEnabled(action: GalleryQuickAction, selectionCount: Int, sdkInt: Int): Boolean = when (action) {
    GalleryQuickAction.SELECT, GalleryQuickAction.NEW_COLLECTION, GalleryQuickAction.REFRESH -> true
    GalleryQuickAction.SHARE, GalleryQuickAction.ADD_TO_COLLECTION, GalleryQuickAction.FAVOURITE ->
        selectionCount > 0
    GalleryQuickAction.MOVE_TO_TRASH, GalleryQuickAction.RESTORE, GalleryQuickAction.DELETE_PERMANENTLY ->
        selectionCount > 0 && sdkInt >= Build.VERSION_CODES.R
}
