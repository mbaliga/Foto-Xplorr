package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle

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
    selectionActive: Boolean,
    onStartSelection: () -> Unit,
    onNewCollection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preferences = state.preferences
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
}

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
