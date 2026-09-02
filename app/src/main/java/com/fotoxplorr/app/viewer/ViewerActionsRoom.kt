package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The viewer's RIGHT room: what you can do to this photo, as icons.
 *
 * These nine actions used to be a 78%-opaque black plate of **text buttons** pinned over the top
 * of the photo, up by default and back up again on every swipe (owner, 2026-08-14: *"Why do I see
 * this overlay here on the photo? The idea was for no such UI... These options should present with
 * icons in a room"*). A viewer whose default state is "photo, plus a panel of words covering the
 * photo" is not a viewer.
 *
 * As a room, the actions cost the photo nothing until asked for, and the shell already carries
 * the gesture and the motion for asking. Being a room is also why they can be icons at all: a
 * label is what an overlay needs to stay legible at a glance over arbitrary image content, while
 * a room has a quiet ground of its own, room to breathe, and — because it is a deliberate
 * destination rather than something you land on — the space to caption every glyph.
 *
 * Each row is therefore icon **and** word. Icon-only would be the overlay's mistake inverted:
 * unlabelled glyphs are a guessing game, and "Move to trash" is not a guess worth getting wrong.
 */
@Composable
fun ViewerActionsRoom(
    isFavorite: Boolean,
    isSensitive: Boolean,
    canMoveToTrash: Boolean,
    slideshowActive: Boolean,
    onToggleSlideshow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSensitive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onMoveToTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        ActionRow(
            icon = if (slideshowActive) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            label = if (slideshowActive) "Pause slideshow" else "Slideshow",
            onClick = onToggleSlideshow,
        )
        ActionRow(Icons.Outlined.Share, "Share", onShare)
        ActionRow(Icons.Outlined.Edit, "Edit", onEdit)
        ActionRow(Icons.Outlined.OpenInNew, "Open with", onOpenWith)
        ActionRow(
            icon = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            label = if (isFavorite) "Favourited" else "Favourite",
            onClick = onToggleFavorite,
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
        )
        ActionRow(
            icon = if (isSensitive) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            label = if (isSensitive) "Marked sensitive" else "Mark sensitive",
            onClick = onToggleSensitive,
            tint = if (isSensitive) MaterialTheme.colorScheme.primary else Color.White,
        )
        ActionRow(
            icon = Icons.Outlined.Delete,
            // Says where it goes, because it does not delete: it hands the file to Android's own
            // system trash, which is the only route that can be undone.
            label = if (canMoveToTrash) "Move to trash" else "Trash unavailable",
            onClick = onMoveToTrash,
            enabled = canMoveToTrash,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                // Null, not the label: the Text beside it is already read out, and a described
                // icon next to its own caption makes every action announce itself twice.
                contentDescription = null,
                tint = tint.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

/**
 * The width the actions room wants. The shell insets a right room by its own band, so this is
 * what remains for content; exported so the host can keep the room from stretching to the full
 * screen on a tablet, where a column of seven rows across 900dp would be mostly empty.
 */
val ViewerActionsRoomWidth = 260.dp

/** A hairline spacer used where the room needs to separate destructive actions from safe ones. */
@Composable
internal fun ActionDivider() {
    androidx.compose.foundation.layout.Spacer(
        Modifier
            .padding(vertical = 6.dp)
            .width(120.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape),
    )
}
