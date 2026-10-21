package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle
import com.fotoxplorr.app.ui.clearSystemBarsFor

/**
 * The gallery's RIGHT room, the view options: how the view you are looking at is shown.
 *
 * Cut out of what used to be one right room that mixed these with the actions. They are different
 * kinds of thing, and [RoomGeography] gives each its own edge (owner, batch 2, #26): view options
 * sit at the side, where you want to see their effect, and actions go to the bottom, where they do
 * not need the grid in view. The viewer's right room ([com.fotoxplorr.app.viewer.ViewerSettingsRoom])
 * is the same room for one open photo.
 *
 * Two groups. ARRANGE is how the view is laid out; SHOW is what it includes. Every control writes
 * through [GalleryActions] straight away, as it did when it lived with the actions.
 */
@Composable
fun GalleryViewOptionsRoom(
    state: GalleryUiState,
    actions: GalleryActions,
    modifier: Modifier = Modifier,
) {
    val preferences = state.preferences

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // The shell insets a right room by its band at the LEFT (where the parked card's
            // sliver sits), so the system bar to clear here is the status bar at the top.
            .clearSystemBarsFor(RoomGeography.ViewOptions)
            .verticalScroll(rememberScrollState())
            .padding(
                start = RoomStyle.GutterStart,
                end = RoomStyle.GutterEnd,
                top = 28.dp,
                bottom = 28.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
