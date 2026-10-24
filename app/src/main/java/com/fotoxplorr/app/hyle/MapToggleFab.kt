package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The quick toggle between the grid and the map: a black disc at the bottom left, tap to see the
 * same photos on a map, tap again to go back (owner #27).
 *
 * The mirror of [SearchFab] across the bottom edge -- the same 48dp disc, the same black, the same
 * lack of a ripple -- so the two read as a pair either side of the pill. It shows where a tap will
 * TAKE you, not where you are: the map glyph while the grid is up, the grid glyph while the map
 * is. [showingMap] is also what the content description says (`Show grid` / `Show map`), so a
 * screen reader hears the action, not the state.
 *
 * Placed by its host [SEARCH_FAB_MARGIN_END] in from the left edge (more on a wide window with a
 * pinned rail, which is the host's to account for) and [SEARCH_FAB_MARGIN_BOTTOM] up from the
 * bottom, above the navigation bar.
 */
@Composable
fun MapToggleFab(
    showingMap: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(SEARCH_FAB_DP.dp)
            .clip(CircleShape)
            // Fixed on purpose (reference design): the same black disc as the search button.
            .background(Color.Black)
            .semantics { contentDescription = if (showingMap) "Show grid" else "Show map" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple, for the reason SearchFab gives.
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (showingMap) Icons.Outlined.GridView else Icons.Outlined.Map,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(MAP_FAB_GLYPH_DP.dp),
        )
    }
}

private const val MAP_FAB_GLYPH_DP = 24
