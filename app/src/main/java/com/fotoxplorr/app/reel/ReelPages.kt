package com.fotoxplorr.app.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.RoomStyle
import java.text.DateFormat
import java.util.Date

/** The bar at the top of a page: Back and the title. */
@Composable
internal fun ReelTitleBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReelIconButton(Icons.AutoMirrored.Outlined.ArrowBack, ReelCopy.BACK, onBack)
        Text(title, modifier = Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The first page: Draft one for me, Start with nothing, and the saved reels (name, how many items and how long,
 * when it was saved) each as one button that opens it with a Delete button beside it that asks first. A file that
 * cannot be read still has a row, with only Delete. Reels save as the person works, so there is no Save button;
 * one line under the title says so. The page only scrolls up and down and stops short of the bottom edge
 * (TRAPS #72).
 */
@Composable
internal fun ReelHomePage(
    list: SavedReels?,
    opening: Boolean,
    openFailed: Boolean,
    onDraft: () -> Unit,
    onBlank: () -> Unit,
    onOpen: (SavedReel) -> Unit,
    onDelete: (SavedReel) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ReelTitleBar(ReelCopy.TITLE, onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ReelCopy.BLURB, style = MaterialTheme.typography.bodyMedium)
            Text(ReelCopy.SAVES_BY_ITSELF, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            val full = (list?.reels?.size ?: 0) >= ReelProjectStore.MAX_REELS
            ReelButton(ReelCopy.START_DRAFT, onDraft, enabled = !full && !opening, primary = true)
            ReelButton(ReelCopy.START_BLANK, onBlank, enabled = !full && !opening)
            if (full) Text(ReelCopy.LIST_FULL, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            if (opening) Text(ReelCopy.OPENING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            if (openFailed) Text(ReelCopy.COULD_NOT_OPEN, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)

            Text(ReelCopy.YOUR_REELS, modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = RoomStyle.InkMuted)
            when {
                list == null -> Unit
                list.reels.isEmpty() -> Text(ReelCopy.NO_REELS, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                else -> list.reels.forEach { reel -> ReelRow(reel, enabled = !opening, onOpen = { onOpen(reel) }, onDelete = { onDelete(reel) }) }
            }
            // Clear of the bottom edge zone, as the hub's last item is.
            Spacer(Modifier.size(72.dp))
        }
    }
}

@Composable
private fun ReelRow(reel: SavedReel, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled && reel.readable, role = Role.Button, onClick = onOpen)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(reel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (reel.readable) {
                Text(ReelCopy.reelSummary(reel.itemCount, reel.durationMs), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reel.modifiedMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomStyle.InkFaint,
                )
            }
        }
        ReelIconButton(Icons.Outlined.Delete, ReelCopy.deleteReel(reel.name), onDelete)
    }
}

/** The question before a reel is deleted for good. */
@Composable
internal fun DeleteReelDialog(reel: SavedReel, onDelete: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(ReelCopy.DELETE_ASK) },
        text = { Text(reel.name) },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(ReelCopy.DELETE) } },
        dismissButton = { TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) { Text(ReelCopy.DELETE_KEEP) } },
    )
}

/**
 * Setting up a draft: which months (none chosen is every date), how long, what shape, then Make the draft; or
 * Choose by hand, which opens the picker. A month is a switch (several can be on); a length and a shape are a
 * choice of one. The draft works from dates, favourites and the order of the photos, without looking at them,
 * so it may keep a blurred shot or a near-twin, and skips a photo taken within three seconds of one it kept: the words
 * under the title do not promise more.
 */
@Composable
internal fun ReelDraftPage(
    months: List<ReelDrafting.Month>,
    chosen: Set<String>,
    onToggleMonth: (String) -> Unit,
    onAllDates: () -> Unit,
    target: Long,
    onTarget: (Long) -> Unit,
    aspect: ReelAspect,
    onAspect: (ReelAspect) -> Unit,
    busy: Boolean,
    note: String?,
    onMake: () -> Unit,
    onByHand: () -> Unit,
    onBack: () -> Unit,
    locale: java.util.Locale = java.util.Locale.getDefault(),
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ReelTitleBar(ReelCopy.DRAFT_TITLE, onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(ReelCopy.DRAFT_BLURB, style = MaterialTheme.typography.bodyMedium)

            ReelSectionTitle(ReelCopy.WHEN)
            ReelWrap {
                ReelChoice(ReelCopy.ALL_DATES, selected = chosen.isEmpty(), onClick = onAllDates, enabled = !busy)
                months.forEach { m ->
                    ReelToggle(
                        ReelCopy.month(m.year, m.month, locale),
                        checked = m.code in chosen,
                        onChange = { onToggleMonth(m.code) },
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = ReelCopy.monthDescription(m.year, m.month, m.count, locale) },
                    )
                }
            }

            ReelSectionTitle(ReelCopy.LENGTH)
            ReelWrap {
                ReelDrafting.LENGTHS.forEach { ms -> ReelChoice(ReelCopy.lengthChoice(ms), selected = target == ms, onClick = { onTarget(ms) }, enabled = !busy) }
            }

            ReelSectionTitle(ReelCopy.SHAPE)
            ReelWrap {
                ReelAspect.entries.forEach { a -> ReelChoice(ReelCopy.aspect(a), selected = aspect == a, onClick = { onAspect(a) }, enabled = !busy) }
            }

            ReelButton(ReelCopy.MAKE_DRAFT, onMake, enabled = !busy, primary = true)
            ReelButton(ReelCopy.PICK_BY_HAND, onByHand, enabled = !busy)
            if (busy) Text(ReelCopy.DRAFTING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            note?.let { Text(it, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.size(72.dp))
        }
    }
}

/**
 * Choose photos and clips to put in the reel: a grid of the gallery's browsable photos and videos (the host's list,
 * so a protected one is not offered, TRAPS #33). Tapping one chooses it and numbers it, tapping again un-chooses
 * it; nothing is added until Add is pressed, so choosing never closes the picker (TRAPS #61). [room] is how many
 * more items the reel can take. The grid only scrolls up and down and stops short of the bottom edge with a bar.
 * The chosen ids are kept across the process being killed; the picture grid is not.
 */
@Composable
internal fun ReelItemPicker(
    candidates: List<MediaAsset>,
    room: Int,
    onAdd: (List<MediaAsset>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val byId = remember(candidates) { candidates.associateBy { it.id.value } }
    // A photo that was locked since it was chosen drops out of the choice.
    val live = chosen.filter { it in byId }
    Column(modifier.fillMaxSize()) {
        ReelTitleBar(ReelCopy.ADD_ITEMS, onBack)
        Text(ReelCopy.PICK_ITEMS, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (candidates.isEmpty()) {
            Text(
                ReelCopy.NO_ITEMS_HERE,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkFaint,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(candidates, key = { it.id.value }) { asset ->
                    val position = live.indexOf(asset.id.value).takeIf { it >= 0 }?.plus(1)
                    PickCell(
                        asset = asset,
                        position = position,
                        enabled = position != null || live.size < room,
                        onToggle = { chosen = if (asset.id.value in live) live - asset.id.value else live + asset.id.value },
                    )
                }
            }
        }
        // Clear of the bottom edge zone, and where Add is: the one thing this page leads to.
        Row(
            Modifier
                .fillMaxWidth()
                .background(RoomStyle.Surface)
                .navigationBarsPadding()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(ReelCopy.itemsChosen(live.size), modifier = Modifier.weight(1f).polite(), style = MaterialTheme.typography.bodyMedium)
            ReelButton(ReelCopy.addItems(live.size), { onAdd(live.mapNotNull(byId::get)) }, enabled = live.isNotEmpty(), primary = true)
        }
    }
}

/** One thumbnail of the picker: a checkbox that shows its place in the choice once chosen, and a clip's length. */
@Composable
private fun PickCell(asset: MediaAsset, position: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val chosen = position != null
    val words = if (asset.isVideo) "${asset.displayName}, ${ReelCopy.ITEM_CLIP}, ${ReelCopy.clock(asset.durationMillis)}" else asset.displayName
    Box(
        Modifier
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .then(if (chosen) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        if (asset.isVideo) {
            Text(
                ReelCopy.clock(asset.durationMillis),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        if (position != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .defaultMinSize(24.dp, 24.dp)
                    .background(Color.White, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$position", color = Color.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
}
