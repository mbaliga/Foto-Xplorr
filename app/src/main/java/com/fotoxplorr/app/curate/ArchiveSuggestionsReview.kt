package com.fotoxplorr.app.curate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.curate.ArchiveAdvisor.ArchiveReasonCategory
import com.fotoxplorr.app.curate.ArchiveAdvisor.ArchiveSuggestion
import com.fotoxplorr.app.hyle.HyleToggle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle

/**
 * One [ArchiveSuggestion] paired with the asset it is about.
 *
 * [ArchiveSuggestion] itself only carries a [MediaId] -- correct for [ArchiveAdvisor], which has
 * no business depending on the media package, but useless for a screen that has to show a
 * thumbnail. Building this pairing is the caller's job (it already has both lists in hand
 * wherever it called [ArchiveAdvisor.suggestions]), not this composable's.
 */
data class ArchiveReviewItem(val suggestion: ArchiveSuggestion, val asset: MediaAsset)

/**
 * The review queue [ArchiveAdvisor] suggests into -- suggestions grouped by reason, with an
 * immediate per-item accept and reject plus a separate batch-select-then-archive flow for
 * clearing several at once. Not wired into any screen; see this feature's own report for exactly
 * where it is meant to hang. Owned entirely by `curate/` so it can evolve without touching the
 * settings or gallery screens other work is editing concurrently.
 *
 * ## Three actions, on purpose, not two
 *
 * [HyleToggle] here is a BATCH SELECTION mark, not a stand-in for accept: switching one on says
 * "include this in what I archive next", nothing more, and starts every row off (deliberately
 * unchecked) so the archive bar at the bottom stays disabled until a person has actually chosen
 * something -- a queue that pre-selects everything for you is a queue where "accept all" and
 * "accept the ones I actually reviewed" look identical, which is exactly the silent-bulk-action
 * shape the governing brief for this feature ruled out. "Accept" and "Reject" beside the reason
 * text are separate, immediate, single-photo actions for the common case of reviewing one photo
 * at a time without touching the toggle at all.
 *
 * ## Why rows disappear on their own
 *
 * There is no local "dismissed" list here. [onAccept] and [onReject] are expected to update
 * whatever state [items] is derived from (an accepted photo becomes archived, a rejected one is
 * remembered so [ArchiveAdvisor] never offers it again -- see
 * [com.fotoxplorr.app.organize.LibraryStore.rejectArchiveSuggestions]), and the next [items] this
 * composable receives simply will not contain that photo any more. Recomposing from a shorter
 * list rather than tracking a local exclusion set matches how the rest of this app moves state --
 * see `GalleryUiState`/`GalleryActions` -- and means this screen cannot drift out of sync with
 * what is actually stored.
 *
 * @param items What to review, any order -- grouping and ordering within each group is this
 *   composable's job, done once from [ArchiveReasonCategory]'s own declared order so the
 *   sections always read DUPLICATE, OLD_SCREENSHOT, LOW_RESOLUTION, BLURRY regardless of the
 *   order the caller happened to build the list in.
 * @param onAccept Archive these photos. May be called with one id (a row's own "Accept") or many
 *   (the bottom bar). The caller is expected to route this to
 *   [com.fotoxplorr.app.organize.LibraryStore.setArchived] with `archived = true` --
 *   this composable does not, and cannot, archive anything itself.
 * @param onReject Never suggest these photos again. Same one-or-many shape as [onAccept].
 */
@Composable
fun ArchiveSuggestionsReview(
    items: List<ArchiveReviewItem>,
    onAccept: (Set<MediaId>) -> Unit,
    onReject: (Set<MediaId>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(emptySet<MediaId>()) }
    // Derived, not synchronised: an id can only mean something once its row is on screen, so the
    // set actually used for counting and archiving is always intersected against what is
    // currently in `items`. Without this, accepting or rejecting one row would leave its id
    // sitting in `selected` forever -- invisible, but still counted the next time someone uses
    // the batch bar, quietly archiving a photo nobody meant to touch a second time.
    val visibleIds = remember(items) { items.mapTo(HashSet(items.size)) { it.suggestion.mediaId } }
    val activeSelection = selected.intersect(visibleIds)

    val grouped = remember(items) { items.groupBy { it.suggestion.category } }

    // Solid black rather than trusting whatever container hosts this screen to already be dark --
    // this composable is deliberately not wired into a parent yet (see the class KDoc), so it
    // cannot assume one. Matches `gallery/GalleryInfoRoom.kt`'s own room background exactly.
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (items.isEmpty()) {
            EmptyReviewState()
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ArchiveReasonCategory.entries.forEach { category ->
                val group = grouped[category].orEmpty()
                if (group.isEmpty()) return@forEach

                item(key = "header-${category.name}") {
                    GroupHeader(
                        category = category,
                        count = group.size,
                        onSelectAll = { selected = selected + group.map { it.suggestion.mediaId } },
                    )
                }
                items(group, key = { it.suggestion.mediaId.value }) { reviewItem ->
                    ReviewRow(
                        item = reviewItem,
                        selected = reviewItem.suggestion.mediaId in activeSelection,
                        onSelectedChange = { checked ->
                            val id = reviewItem.suggestion.mediaId
                            selected = if (checked) selected + id else selected - id
                        },
                        onAccept = { onAccept(setOf(reviewItem.suggestion.mediaId)) },
                        onReject = { onReject(setOf(reviewItem.suggestion.mediaId)) },
                    )
                }
                item(key = "rule-${category.name}") {
                    RoomRule(Modifier.padding(vertical = 8.dp))
                }
            }
        }

        BatchBar(
            selectedCount = activeSelection.size,
            onArchiveSelected = {
                onAccept(activeSelection)
                selected = emptySet()
            },
        )
    }
}

@Composable
private fun GroupHeader(category: ArchiveReasonCategory, count: Int, onSelectAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RoomStyle.GutterStart, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomEyebrow(text = "${category.title()} · $count")
        Text(
            text = "Select all",
            color = RoomStyle.InkMuted,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.clickable(onClick = onSelectAll).padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

@Composable
private fun ReviewRow(
    item: ArchiveReviewItem,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RoomStyle.GutterStart, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaImage(
            asset = item.asset,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(text = item.suggestion.reason, color = RoomStyle.Ink, style = RoomStyle.Row)
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                RowAction(label = "Accept", emphasised = true, onClick = onAccept)
                RowAction(label = "Reject", emphasised = false, onClick = onReject)
            }
        }
        HyleToggle(
            checked = selected,
            onCheckedChange = onSelectedChange,
            description = "Include ${item.asset.displayName} in the next batch archive",
        )
    }
}

@Composable
private fun RowAction(label: String, emphasised: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (emphasised) RoomStyle.Ink else RoomStyle.InkMuted,
        style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun BatchBar(selectedCount: Int, onArchiveSelected: () -> Unit) {
    val enabled = selectedCount > 0
    RoomRule()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RoomStyle.GutterStart, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (enabled) "$selectedCount selected" else "Toggle photos to archive them together",
            color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint,
            style = RoomStyle.Caption,
        )
        Text(
            text = "Archive selected",
            color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = if (enabled) {
                Modifier.clickable(onClick = onArchiveSelected).padding(vertical = 4.dp, horizontal = 2.dp)
            } else {
                Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
            },
        )
    }
}

@Composable
private fun EmptyReviewState() {
    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Nothing to review right now.",
            color = RoomStyle.InkFaint,
            style = RoomStyle.Row,
        )
    }
}

/** Mirrors `SmartAlbum.title()`'s convention in `gallery/GalleryProjection.kt` for the same purpose. */
private fun ArchiveReasonCategory.title(): String = when (this) {
    ArchiveReasonCategory.DUPLICATE -> "Possible duplicates"
    ArchiveReasonCategory.OLD_SCREENSHOT -> "Old screenshots"
    ArchiveReasonCategory.LOW_RESOLUTION -> "Low resolution"
    ArchiveReasonCategory.BLURRY -> "Blurry"
}
