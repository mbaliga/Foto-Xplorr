package com.fotoxplorr.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.core.formats.TileState

/**
 * The honest tile for a file that cannot be drawn: a neutral surface with a short format badge
 * ("CR3") and, when there is room, a one-line reason. Never black and never empty.
 *
 * Size-aware: a stamp-sized tile (under 72 dp) shows the badge alone; the reason appears when the
 * tile is at least 140 dp wide or [showReason] is set (the viewer sets it). A protected item has an
 * empty badge, and the tile is then just the neutral surface.
 *
 * [nameForReader]: when set, it is the content description and the "type, can't be shown here"
 * sentence becomes the state description instead.
 */
@Composable
fun FormatPlaceholder(
    state: TileState.Placeholder,
    modifier: Modifier = Modifier,
    showReason: Boolean = false,
    nameForReader: String? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                // A file the loader gave up on keeps its name for a reader (and for anything that
                // finds the tile by it); the type and "can't be shown here" ride as its state.
                if (nameForReader == null) {
                    contentDescription = state.description
                } else {
                    contentDescription = nameForReader
                    stateDescription = state.description
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val small = maxWidth < 72.dp
        val roomForReason = showReason || maxWidth >= 140.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
            if (state.badge.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                        .padding(horizontal = if (small) 4.dp else 10.dp, vertical = if (small) 2.dp else 4.dp),
                ) {
                    Text(
                        state.badge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (small) 9.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            if (roomForReason && !small) {
                Text(
                    state.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
