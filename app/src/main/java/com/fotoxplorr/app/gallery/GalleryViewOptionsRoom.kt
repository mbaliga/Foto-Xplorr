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
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_LAST_GRID
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_MIN
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_STAMPS
import com.fotoxplorr.app.adaptive.zoomRungValue
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle
import com.fotoxplorr.app.ui.clearSystemBarsFor
import com.fotoxplorr.app.ui.HelpCopy

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

        // Zoom, not columns: the rungs are 1..6 columns and then the stamps. A masonry grid has no
        // stamps, so its stepper stops at the sparsest column grid.
        val topRung = if (preferences.fitToTile) ZOOM_RUNG_STAMPS else ZOOM_RUNG_LAST_GRID
        val rung = preferences.gridZoomRung.coerceAtMost(topRung)
        // The date orders name the side the latest photo sits on, which depends on the way the grid
        // on screen scrolls (it is vertical at the stamps and in masonry).
        val horizontal = gridScrollsHorizontally(preferences.scrollAxis, rung, masonry = !preferences.fitToTile)
        RoomRow(label = "Zoom") {
            RoomStepper(
                value = zoomRungValue(rung),
                onDecrease = { actions.onSetGridZoomRung(rung - 1) },
                onIncrease = { actions.onSetGridZoomRung(rung + 1) },
                canDecrease = rung > ZOOM_RUNG_MIN,
                canIncrease = rung < topRung,
            )
        }

        // Which way the photos scroll. Its own row, since it is neither the sort nor where the
        // timeline sits; the stamps and the masonry layout stay vertical whatever it says.
        RoomRow(
            label = "Scroll direction",
            caption = HelpCopy.Caption.SCROLL_AXIS,
            onClick = {
                actions.onSetScrollAxis(
                    if (preferences.scrollAxis == ScrollAxis.VERTICAL) ScrollAxis.HORIZONTAL else ScrollAxis.VERTICAL,
                )
            },
        ) { RoomValue(preferences.scrollAxis.label) }

        // Stated rather than ticked: a checkmark column would be the only iconography in a room
        // that has none, and the selected row stays full-strength while the alternatives mute --
        // the same way the destination rail marks the destination you are in. The two date orders
        // say where the latest photo sits (the grid opens at that end), since that is what the
        // choice is for.
        GallerySort.entries.forEach { sort ->
            val current = preferences.sort == sort
            RoomRow(
                label = timelineSortLabel(sort, horizontal),
                onClick = { actions.onSetSort(sort) },
            ) { if (current) RoomValue("now") }
        }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("TIMELINE")

        RoomRow(
            label = "Timeline",
            caption = HelpCopy.Caption.TIMELINE,
            onClick = { actions.onSetTimelineOn(!preferences.timelineOn) },
        ) { RoomToggle(preferences.timelineOn, actions.onSetTimelineOn) }

        // Where the strip sits, stated like the sort rows. Not offered while the timeline is off.
        if (preferences.timelineOn) {
            TimelinePlacement.entries.forEach { placement ->
                val current = preferences.timelinePlacement == placement
                RoomRow(
                    label = placement.label,
                    onClick = { actions.onSetTimelinePlacement(placement) },
                ) { if (current) RoomValue("now") }
            }
        }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("SHOW")

        RoomRow(
            label = "Videos",
            onClick = { actions.onSetShowVideos(!preferences.showVideos) },
        ) { RoomToggle(preferences.showVideos, actions.onSetShowVideos) }

        RoomRow(
            label = "Blur sensitive",
            caption = HelpCopy.Caption.BLUR_GRID_ONLY,
            onClick = { actions.onSetBlurSensitive(!preferences.blurSensitive) },
        ) { RoomToggle(preferences.blurSensitive, actions.onSetBlurSensitive) }

        RoomRow(
            label = "Hide sensitive entirely",
            caption = HelpCopy.Caption.HIDE_FROM_GRID,
            onClick = { actions.onSetHideSensitive(!preferences.hideSensitive) },
        ) { RoomToggle(preferences.hideSensitive, actions.onSetHideSensitive) }
    }
}

/** A stated value at the end of a row, in the room's own muted ink. */
@Composable
private fun RoomValue(text: String) {
    Text(text = text, color = RoomStyle.InkFaint, style = RoomStyle.Caption)
}
