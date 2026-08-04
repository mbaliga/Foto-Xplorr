package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage

/**
 * The filmstrip from the viewer mockup: a horizontal strip of thumbnails pinned to the
 * bottom of the full-screen photo, for scrubbing between shots without leaving the viewer.
 *
 * The current shot is drawn larger and fully opaque with a white hairline; its neighbours
 * are smaller and dimmed, which is what gives the strip its "current position" reading in
 * the mockup. The strip keeps the current item centred as the selection moves, so paging
 * with a swipe and scrubbing with the strip stay in agreement.
 */
@Composable
fun FilmstripScrubber(
    assets: List<MediaAsset>,
    currentIndex: Int,
    onSelect: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assets.size <= 1) return
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex, assets.size) {
        val target = currentIndex.coerceIn(0, assets.lastIndex)
        // Two items of lead-in keeps the active thumbnail near the centre rather than
        // snapping it to the left edge.
        listState.animateScrollToItem((target - 2).coerceAtLeast(0))
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .height(STRIP_HEIGHT.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        items(assets, key = { it.id.value }) { asset ->
            val isCurrent = assets.getOrNull(currentIndex)?.id == asset.id
            val size = if (isCurrent) CURRENT_THUMB.dp else NEIGHBOUR_THUMB.dp
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1A1A1A))
                    .then(
                        if (isCurrent) {
                            Modifier.border(1.dp, Color.White, RoundedCornerShape(3.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(asset) },
            ) {
                MediaImage(
                    asset = asset,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                if (!isCurrent) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

private const val STRIP_HEIGHT = 72
private const val CURRENT_THUMB = 56
private const val NEIGHBOUR_THUMB = 44
