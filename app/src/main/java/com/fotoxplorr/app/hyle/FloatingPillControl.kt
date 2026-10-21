package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The floating control from the mockups that replaced the old four-tab bottom `NavigationBar`:
 * a dark rounded pill holding a readout across the middle and the grid-density toggle on the
 * right.
 *
 * Search used to be its left-hand button. The owner's root-grid reference (2026-10) draws search
 * as a round button of its own in the bottom-right corner — [SearchFab] — and one entry is
 * enough, so the pill no longer carries one. It stops short of that corner and sits on the same
 * baseline as the disc, so the two read as one row of controls rather than a pill with a button
 * dropped on its end.
 *
 * The middle used to be a horizontal position scrubber. It is a readout now because the
 * Niagara-style [dev.aarso.cellshell.EdgeTimelineScrubber] down the right edge does that job
 * properly — it names the month under the finger, which a 200px track with a dot on it never
 * could — and two position controls on one screen that can disagree about where you are is
 * worse than either alone. So the pill stops competing to *set* position and reports it
 * instead, which is the thing the edge strip is too narrow to say in words.
 *
 * It keeps clear of BOTH corner discs: the search disc at the right and, mirrored, the map toggle
 * ([MapToggleFab]) at the left.
 *
 * @param caption what the middle reads. Empty renders nothing rather than an empty gap, so a
 *   destination with no timeline (Places, Protected) gets a two-button pill instead of a hole.
 * @param onToggleDensity the grid-density stepper, which wraps round the zoom rungs (1..6 columns,
 *   then the stamps); null leaves the button out (the map has no density).
 * @param densityState where the grid is now, in words ("3 columns", "Stamps"). A screen reader
 *   announces it as the button's state: this button is the only way to zoom without a gesture, so
 *   it has to say what it just did.
 * @param onOpenTimeRange opens the time-range editor; null leaves the button out (Places and
 *   Protected, which no range applies to).
 */
@Composable
fun FloatingPillControl(
    caption: String,
    onToggleDensity: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenTimeRange: (() -> Unit)? = null,
    densityState: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                // Clear of the map toggle and the search disc, each with its own margin and the
                // same gap again between.
                start = (SEARCH_FAB_MARGIN_END + SEARCH_FAB_DP + PILL_MARGIN).dp,
                end = (SEARCH_FAB_MARGIN_END + SEARCH_FAB_DP + PILL_MARGIN).dp,
                top = PILL_MARGIN.dp,
                bottom = SEARCH_FAB_MARGIN_BOTTOM.dp,
            )
            .clip(RoundedCornerShape(PILL_HEIGHT.dp / 2))
            .background(PILL_BACKGROUND)
            .padding(horizontal = 6.dp)
            .height(PILL_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = caption,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            // Quieter than the icons either side of it: this is a readout, not a control, and
            // it should not read as something to press.
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        if (onOpenTimeRange != null) {
            PillIconButton(onClick = onOpenTimeRange, description = "Filter by time") {
                Icon(Icons.Outlined.DateRange, contentDescription = null, tint = Color.White)
            }
        }
        if (onToggleDensity != null) {
            PillIconButton(onClick = onToggleDensity, description = "Change grid density", state = densityState) {
                Icon(Icons.Outlined.GridView, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun PillIconButton(
    onClick: () -> Unit,
    description: String,
    state: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// Fixed on purpose (reference design): the dark pill from the mockups, it has no selected or active
// state.
private val PILL_BACKGROUND = Color(0xFF141414)

/** As tall as the search disc beside it, so the two share a centre line. */
private const val PILL_HEIGHT = SEARCH_FAB_DP
private const val PILL_MARGIN = 12
private const val BUTTON_SIZE = 44
