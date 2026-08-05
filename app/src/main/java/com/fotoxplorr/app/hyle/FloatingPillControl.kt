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
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The floating control from the mockups that replaced the old four-tab bottom `NavigationBar`:
 * a dark rounded pill holding search on the left, a readout across the middle, and the
 * grid-density toggle on the right.
 *
 * The middle used to be a horizontal position scrubber. It is a readout now because the
 * Niagara-style [dev.aarso.cellshell.EdgeTimelineScrubber] down the right edge does that job
 * properly — it names the month under the finger, which a 200px track with a dot on it never
 * could — and two position controls on one screen that can disagree about where you are is
 * worse than either alone. So the pill stops competing to *set* position and reports it
 * instead, which is the thing the edge strip is too narrow to say in words.
 *
 * @param caption what the middle reads. Empty renders nothing rather than an empty gap, so a
 *   destination with no timeline (Places, Protected) gets a two-button pill instead of a hole.
 */
@Composable
fun FloatingPillControl(
    caption: String,
    onSearch: () -> Unit,
    onToggleDensity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(PILL_HEIGHT.dp / 2))
            .background(PILL_BACKGROUND)
            .padding(horizontal = 6.dp)
            .height(PILL_HEIGHT.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIconButton(onClick = onSearch, description = "Search") {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White)
        }

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

        PillIconButton(onClick = onToggleDensity, description = "Change grid density") {
            Icon(Icons.Outlined.GridView, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun PillIconButton(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(BUTTON_SIZE.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val PILL_BACKGROUND = Color(0xFF141414)
private const val PILL_HEIGHT = 52
private const val BUTTON_SIZE = 44
