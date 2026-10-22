package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.ActionsRoomFrame
import com.fotoxplorr.app.ui.QuickActionItem
import com.fotoxplorr.app.ui.RoomGeography

/**
 * The viewer's BOTTOM room: what you can do to this photo, as icons, tilted up from under it.
 *
 * Actions live at the bottom in the gallery too ([RoomGeography]). They were the right room here
 * until the owner asked for one geography: view options sit at the side, where you can watch their
 * effect, while an action does not need the photo in view, so tilting the photo up out of the way
 * suits it better.
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
 * The room is two-stage: a short pull stops on a strip of the four actions you reach for most
 * (Favourite, Share, Edit, Move to trash -- [viewerQuickActions]), pinned at the head of the room
 * because that is the part of a bottom room that arrives first, and a longer pull carries on to the
 * full list beneath it.
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
    /** "Share with options…" -- the advanced sheet (frame, watermark, strip metadata) that used
     *  to be reachable from nowhere at all once the room replaced the old overlay's menu. */
    onShareClean: (() -> Unit)? = null,
    /** Rename, previously reachable only via Select -> one item -> the gallery's own actions
     *  room. Null hides the row -- see [isExternal]. */
    onRename: (() -> Unit)? = null,
    /** Shows the row below at all — a still photo has no codec to convert. */
    isVideo: Boolean = false,
    isConvertingToMp4: Boolean = false,
    /** Re-encodes this video into H.264/AAC/MP4 as a new file beside it — video's own Save As,
     *  see [com.fotoxplorr.app.video.VideoExporter]. Null leaves the row hidden entirely, the
     *  same shape [com.fotoxplorr.app.viewer.PhotoDetailRoom]'s optional actions already use. */
    onConvertToMp4: (() -> Unit)? = null,
    /**
     * False for an ad-hoc asset opened from outside this app's own library (P0-18's
     * `ExternalViewerActivity`, which has no editor, no catalogue and no write access): hides
     * every row that would read or write this app's own catalogue -- Edit, Convert to MP4,
     * Favourite, Mark sensitive and Move to trash. Slideshow, Share and Open with all stay, since
     * none of the three depends on the photo being catalogued.
     */
    catalogueActions: Boolean = true,
    /** True for an asset opened from another app (open-with/share-to) into [FotoXplorrActivity]'s
     *  own viewer rather than this app's own library -- see
     *  [com.fotoxplorr.app.media.MediaAsset.isExternal]. Unlike [catalogueActions] = false, Edit
     *  and Convert stay (the editor and exporter work on any readable Uri); it hides favourite,
     *  mark sensitive, rename and trash, none of which have anything to act on for a file this app
     *  holds no library record for. */
    isExternal: Boolean = false,
    /**
     * "Lift subject" -- cut the subject out of this photo as a transparent sticker. Null hides the
     * row; the host passes it only for a still raster photo (see
     * [com.fotoxplorr.app.lift.canLiftSubject]), never for video, SVG, RAW or animated images.
     *
     * Deliberately NOT behind [catalogueActions]: lifting reads nothing but the photo's URI and
     * its only output is a new sticker file the person asks for, so it makes as much sense on a
     * photo opened from another app as on one from the library.
     */
    onLiftSubject: (() -> Unit)? = null,
    /**
     * "Select text" -- open the Select text mode over this photo. Null hides the row; the host
     * passes it for any still raster photo (see [com.fotoxplorr.app.lift.canLiftSubject], the same
     * test the word reader applies), whether or not the stored index found text in it: the index
     * reads at a low resolution and can miss small print, and a photo opened from another app has
     * no index at all. Like Lift, not behind [catalogueActions]: it reads only the photo itself.
     */
    onSelectText: (() -> Unit)? = null,
    /** Closes the room. Only the quick strip's Favourite needs it: every other handler passed in
     *  closes the room itself. */
    onCloseRoom: () -> Unit = {},
    /** [dev.aarso.cellshell.SpatialController.hasBottomPeek]: false on a window too short for the
     *  peek, where the room is single-stage and the strip would only repeat the rows below it. */
    hasBottomPeek: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // The strip is the room's first inch: a short pull stops on it (RoomGeography.ActionsPeek).
    // Share, Edit and Move to trash arrive here already closing the room; Favourite is the one
    // handler that does not (the full row keeps the room open so the heart can be seen to fill),
    // so the strip closes it before toggling.
    val quickActions = viewerQuickActions(catalogueActions, isExternal, canMoveToTrash).map { action ->
        when (action) {
            ViewerQuickAction.FAVOURITE -> QuickActionItem(
                label = if (isFavorite) "Favourited" else "Favourite",
                icon = if (isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                onClick = { onCloseRoom(); onToggleFavorite() },
                highlighted = isFavorite,
            )
            ViewerQuickAction.SHARE -> QuickActionItem("Share", Icons.Outlined.Share, onShare)
            ViewerQuickAction.EDIT -> QuickActionItem("Edit", Icons.Outlined.Edit, onEdit)
            ViewerQuickAction.TRASH -> QuickActionItem("Trash", Icons.Outlined.Delete, onMoveToTrash)
        }
    }

    ActionsRoomFrame(
        quickActions = quickActions,
        showStrip = hasBottomPeek,
        modifier = modifier,
        // Scrolls for a short phone: twelve rows can outgrow what the band leaves.
        contentPadding = PaddingValues(vertical = 20.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        ActionRow(
            // "Stop", not "Pause": the row ends the slideshow. Pausing and resuming is the bar's.
            icon = if (slideshowActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
            label = if (slideshowActive) "Stop slideshow" else "Slideshow",
            onClick = onToggleSlideshow,
        )
        ActionRow(Icons.Outlined.Share, "Share", onShare)
        if (onShareClean != null) {
            ActionRow(Icons.Outlined.Tune, "Share with options…", onShareClean)
        }
        if (catalogueActions) {
            ActionRow(Icons.Outlined.Edit, "Edit", onEdit)
        }
        if (!isVideo && onLiftSubject != null) {
            ActionRow(Icons.Outlined.ContentCut, "Lift subject", onLiftSubject)
        }
        if (!isVideo && onSelectText != null) {
            ActionRow(Icons.Outlined.TextFields, "Select text", onSelectText)
        }
        ActionRow(Icons.Outlined.OpenInNew, "Open with", onOpenWith)
        if (catalogueActions && !isExternal && onRename != null) {
            ActionRow(Icons.Outlined.DriveFileRenameOutline, "Rename", onRename)
        }
        if (catalogueActions && isVideo && onConvertToMp4 != null) {
            ActionRow(
                icon = Icons.Outlined.SwapHoriz,
                label = if (isConvertingToMp4) "Converting…" else "Convert to MP4 (H.264)",
                onClick = onConvertToMp4,
                enabled = !isConvertingToMp4,
            )
        }
        if (catalogueActions && !isExternal) {
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
                // Says where it goes, because it does not delete: it hands the file to Android's
                // own system trash, which is the only route that can be undone.
                label = if (canMoveToTrash) "Move to trash" else "Trash unavailable",
                onClick = onMoveToTrash,
                enabled = canMoveToTrash,
            )
        }
    }
}

/**
 * One row of the room: an icon and its word. The WHOLE row is the button (it used to be only the
 * 22 dp icon inside an [IconButton], so a tap on the word did nothing), at least 48 dp tall, and
 * reads to a screen reader as one control named by its label.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            // Null, not the label: the Text beside it is already read out, and a described
            // icon next to its own caption makes every action announce itself twice.
            contentDescription = null,
            tint = tint.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

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

/** The viewer's quick actions, in the order they sit in the strip. */
enum class ViewerQuickAction { FAVOURITE, SHARE, EDIT, TRASH }

/**
 * The quick actions allowed for this photo -- the same rules as the full list below them, so the
 * strip never offers what the room would hide:
 *
 * - Share is always there (it needs nothing from the catalogue).
 * - Edit needs [catalogueActions]; an externally opened photo ([isExternal]) keeps it.
 * - Favourite and Move to trash need [catalogueActions] and a photo this app holds a record for
 *   (not [isExternal]); trash also needs [canMoveToTrash], and is left out rather than shown dead
 *   when the platform cannot do it.
 */
fun viewerQuickActions(
    catalogueActions: Boolean,
    isExternal: Boolean,
    canMoveToTrash: Boolean,
): List<ViewerQuickAction> = buildList {
    val ownRecord = catalogueActions && !isExternal
    if (ownRecord) add(ViewerQuickAction.FAVOURITE)
    add(ViewerQuickAction.SHARE)
    if (catalogueActions) add(ViewerQuickAction.EDIT)
    if (ownRecord && canMoveToTrash) add(ViewerQuickAction.TRASH)
}
