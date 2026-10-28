package com.fotoxplorr.app.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * Choose photos to put on the board: a grid of the gallery's browsable stills (the host's list, so a
 * protected photo is not offered, TRAPS #33). Tapping a photo chooses it and numbers it, tapping again
 * un-chooses it; nothing is added until Add is pressed, so choosing never closes the picker (TRAPS #61).
 * [room] is how many more layers the board can take. The grid only scrolls up and down and stops short
 * of the bottom edge with a bar, as the panorama's picker does. The chosen ids are kept across the process
 * being killed; the picture grid is not.
 */
@Composable
internal fun BoardPhotoPicker(
    stills: List<MediaAsset>,
    room: Int,
    onAdd: (List<MediaAsset>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val byId = remember(stills) { stills.associateBy { it.id.value } }
    // A photo that was locked since it was chosen drops out of the choice.
    val live = chosen.filter { it in byId }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardIconButton(Icons.AutoMirrored.Outlined.ArrowBack, BoardCopy.BACK, onBack)
            Text(
                BoardCopy.ADD_PHOTO,
                modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Text(BoardCopy.PICK_PHOTOS, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (stills.isEmpty()) {
            Text(
                BoardCopy.NO_PHOTOS,
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
                items(stills, key = { it.id.value }) { asset ->
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
            Text(BoardCopy.photosPicked(live.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            BoardButton(
                BoardCopy.addPhotos(live.size),
                { onAdd(live.mapNotNull(byId::get)) },
                enabled = live.isNotEmpty(),
                primary = true,
            )
        }
    }
}

/** One thumbnail of the picker: a checkbox that shows its place in the choice once chosen. */
@Composable
private fun PickCell(asset: MediaAsset, position: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val chosen = position != null
    Box(
        Modifier
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = asset.displayName }
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .then(if (chosen) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
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
