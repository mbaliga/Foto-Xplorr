package com.fotoxplorr.app.hyle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * The search entry on the browsing surface: a black disc at the bottom right with a white
 * magnifier, as the owner's root-grid reference (2026-10) draws it. 48dp across, placed
 * [SEARCH_FAB_MARGIN_END] in from the right edge and [SEARCH_FAB_MARGIN_BOTTOM] up from the
 * bottom by its host.
 *
 * The glyph is drawn, not Material's. At this size Material's search icon is a ring two thirds
 * the width of its box with a long handle, where the reference's ring is nearly the whole mark
 * and its handle a short stub to the south-east: a 23dp ring in a 2.5dp stroke, the handle 3dp
 * wide, both rounded. The glyph as a whole is centred on the disc, which puts the ring itself a
 * touch up and left of centre — exactly where the reference has it.
 */
@Composable
fun SearchFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(SEARCH_FAB_DP.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .semantics { contentDescription = "Search" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: the disc is the one solid shape over the photographs and a flash
                // across it would be the only chrome that ever changes colour.
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        val shift = RING_SHIFT_DP.dp.toPx()
        val centre = Offset(size.width / 2f + shift, size.height / 2f + shift)
        drawCircle(
            color = Color.White,
            radius = RING_RADIUS_DP.dp.toPx(),
            center = centre,
            style = Stroke(width = RING_STROKE_DP.dp.toPx()),
        )
        val diagonal = 1f / sqrt(2f)
        val from = HANDLE_FROM_DP.dp.toPx()
        val to = HANDLE_TO_DP.dp.toPx()
        drawLine(
            color = Color.White,
            start = Offset(centre.x + from * diagonal, centre.y + from * diagonal),
            end = Offset(centre.x + to * diagonal, centre.y + to * diagonal),
            strokeWidth = HANDLE_STROKE_DP.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** The disc, and where the host puts it, in dp — measured off the reference. */
const val SEARCH_FAB_DP = 48
const val SEARCH_FAB_MARGIN_END = 24
const val SEARCH_FAB_MARGIN_BOTTOM = 20

/** The ring's centre-line radius and stroke: a 23dp outer diameter in a 2.5dp line. */
private const val RING_RADIUS_DP = 10.25f
private const val RING_STROKE_DP = 2.5f

/** How far up and left of the disc's centre the ring sits, so ring plus handle are centred. */
private const val RING_SHIFT_DP = -0.2f

/** The handle, measured along the 45-degree diagonal from the ring's centre. */
private const val HANDLE_FROM_DP = 10.25f
private const val HANDLE_TO_DP = 17.5f
private const val HANDLE_STROKE_DP = 3f
