package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlin.math.abs

/**
 * The filmstrip from the viewer mockup: a horizontal strip of thumbnails for scrubbing between
 * shots without leaving the viewer.
 *
 * **The current shot is always in the middle of the strip, and dragging the strip navigates.**
 * Both halves of that were previously claimed and neither was true (owner, 2026-08-14: *"The
 * current photo must be centre always, and dragging/swiping on the strip should navigate"*):
 *
 * - Centring was `animateScrollToItem(target - 2)`, which places the target flush against the
 *   viewport's *start* with two thumbnails of lead-in — near the left edge, not the middle — and
 *   pins hard left for the first two photos. Real centring needs half a viewport of padding on
 *   both ends, so the first and last items can reach the middle at all; with that padding,
 *   scrolling to the item *is* centring it.
 * - Navigation was a `clickable` per thumbnail and nothing else. Dragging scrolled a decorative
 *   ribbon that reported nothing, so the strip and the photo could disagree.
 *
 * Every thumbnail is laid out at the same width, and the current one is enlarged by a draw-time
 * scale rather than a larger box. A wider current item would shift the strip's geometry as the
 * selection moved, which makes the centre drift by a few pixels per photo — visible as the strip
 * creeping sideways over a long scrub.
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
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentAssets by rememberUpdatedState(assets)

    // The last index this strip itself reported. Without it the two effects below fight: a drag
    // reports index N, the host feeds N back as currentIndex, and the auto-centre effect then
    // animates to N while the user is still dragging — the strip stutters under the finger.
    var selfReported by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .height(STRIP_HEIGHT.dp),
    ) {
        // Half a viewport either side, so item 0 and the last item can both reach the centre.
        // Without this the ends are unreachable and "always centred" is simply not expressible.
        val sidePadding = (maxWidth - THUMB.dp) / 2

        LaunchedEffect(currentIndex, assets.size) {
            val target = currentIndex.coerceIn(0, assets.lastIndex)
            // Skip the echo of our own report, and never fight a finger that is still down.
            if (target == selfReported || listState.isScrollInProgress) return@LaunchedEffect
            listState.animateScrollToItem(target)
        }

        // Turning the strip IS selecting: resolve whatever ended up nearest the middle and tell
        // the host, so the photo follows the strip instead of only the other way round.
        LaunchedEffect(listState, assets.size) {
            var wasScrolling = false
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                // Only a true -> false edge is a release. The initial `false` would fire on
                // composition, before anything has been touched.
                if (wasScrolling && !scrolling) {
                    val info = listState.layoutInfo
                    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    val landed = info.visibleItemsInfo
                        .minByOrNull { abs(it.offset + it.size / 2 - centre) }
                        ?.index
                    if (landed != null && landed in currentAssets.indices && landed != currentIndex) {
                        selfReported = landed
                        currentOnSelect(currentAssets[landed])
                    }
                }
                wasScrolling = scrolling
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(THUMB_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
            // A strip that stops between thumbnails has no "current" shot, and the release
            // handler above would then pick one the user did not aim at.
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            items(assets, key = { it.id.value }) { asset ->
                val isCurrent = assets.getOrNull(currentIndex)?.id == asset.id
                Box(
                    modifier = Modifier
                        .size(THUMB.dp)
                        // Draw-time only: the box stays THUMB wide whatever is selected, so the
                        // centre never drifts as the selection moves along the strip.
                        .graphicsLayer {
                            val s = if (isCurrent) CURRENT_SCALE else 1f
                            scaleX = s
                            scaleY = s
                        }
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF1A1A1A))
                        .then(
                            if (isCurrent) {
                                Modifier.border(1.dp, Color.White, RoundedCornerShape(3.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable {
                            selfReported = assets.indexOfFirst { it.id == asset.id }
                            onSelect(asset)
                        },
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
}

private const val STRIP_HEIGHT = 72

/** One width for every thumbnail — see the KDoc on why the current one is not laid out larger. */
private const val THUMB = 48
private const val THUMB_GAP = 6

/** How much the current thumbnail grows at draw time. Enough to read as selected, small enough
 *  that it does not collide with its neighbours across the [THUMB_GAP]. */
private const val CURRENT_SCALE = 1.18f
