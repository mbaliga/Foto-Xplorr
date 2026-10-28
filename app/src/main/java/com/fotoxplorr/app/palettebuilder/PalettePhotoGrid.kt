package com.fotoxplorr.app.palettebuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage

/** For tests: the photo grid, a lazy grid, so a photo off the screen is not there until it is scrolled to. */
internal const val PHOTO_GRID_TAG = "palette-photo-grid"

/**
 * A thumbnail grid of [stills] where the ones in [chosen] are ticked and numbered in the order ticked. [header]
 * adds full-width items above the photos. [canChoose] says whether a photo that is not ticked may be (false once
 * the limit is reached): it is then dimmed and not clickable, and a ticked one can always be unticked.
 *
 * A tick is a checkbox with a border and a number badge, so the state is not told by colour alone.
 */
@Composable
internal fun PhotoGrid(
    stills: List<MediaAsset>,
    chosen: List<Long>,
    canChoose: Boolean,
    onToggle: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
    header: LazyGridScope.() -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxWidth().testTag(PHOTO_GRID_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        header()
        items(stills, key = { it.id.value }) { asset ->
            val position = chosen.indexOf(asset.id.value).takeIf { it >= 0 }?.plus(1)
            PhotoCell(asset, position, enabled = position != null || canChoose, onToggle = { onToggle(asset) })
        }
    }
}

/** A full-width item of a [PhotoGrid] header. */
internal fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** One thumbnail of the grid: a checkbox that shows its place once ticked. */
@Composable
private fun PhotoCell(asset: MediaAsset, position: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val ticked = position != null
    Box(
        Modifier
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = asset.displayName }
            .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .then(if (ticked) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        if (position != null) NumberBadge(position, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
    // 24 dp at least, and wider for two digits or a large font, so the number is never wrapped or cut.
    Box(
        modifier.defaultMinSize(24.dp, 24.dp).background(Color.White, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", color = Color.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
