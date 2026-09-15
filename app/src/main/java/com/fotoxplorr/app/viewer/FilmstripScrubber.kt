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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlin.math.abs
import kotlin.math.cos

/**
 * The filmstrip: a horizontal strip of thumbnails for scrubbing between shots, drawn **over the
 * open photo** rather than inside any room (owner, second round, 2026-08-14: *"The filmstrip has
 * to appear not in the details view but in the view where the photo is selected"* -- reversing
 * the first round's placement inside [PhotoDetailRoom], which had put it at the bottom room
 * instead).
 *
 * Three properties, all from the same round of direction:
 *
 * - **The centre stays put; the strip moves through it.** A fixed viewfinder frame is drawn at
 *   the container's horizontal centre and never itself animates. What changes is which thumbnail
 *   sits behind it, via [sidePadding] making the ends reachable and the release handler snapping
 *   to whichever one lands there.
 * - **A loupe.** Thumbnails scale up continuously as they near the centre and ease back down as
 *   they leave it -- the macOS Dock's magnification, not a binary current/not-current switch.
 *   [filmstripMagnification] is the falloff curve, read at draw time per item from the list's own
 *   [androidx.compose.foundation.lazy.LazyListState.layoutInfo] so a drag repaints it every frame
 *   without recomposing anything.
 * - **Inertia.** [rememberSnapFlingBehavior] is not a fixed-duration animation -- it runs the
 *   platform's velocity-based decay first (a fast flick travels further and keeps moving longer
 *   than a slow one) and only adjusts the very end of that motion to land on a thumbnail, which
 *   is exactly "if I pull fast ... it keeps scrolling, if I do it slow it keeps scrolling but for
 *   a shorter duration."
 *
 * Every thumbnail is laid out at the same width; magnification and the "this one is selected"
 * treatment are both draw-time effects on top of it, never a change to the box itself. A wider
 * current item would shift the strip's own geometry as selection moved, which is what made the
 * centre drift in the very first version of this file.
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
    val density = LocalDensity.current
    val magnifyRadiusPx = with(density) { MAGNIFY_RADIUS_DP.dp.toPx() }

    // The last index this strip itself reported. Without it the two effects below fight: a drag
    // reports index N, the host feeds N back as currentIndex, and the auto-centre effect then
    // animates to N while the user is still dragging — the strip stutters under the finger.
    var selfReported by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
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
            modifier = Modifier
                .fillMaxSize()
                // A dark bed under the strip only, not the whole band -- the fixed frame drawn
                // after it needs to read as sitting ABOVE this surface, not as a hole in it.
                .background(Color.Black.copy(alpha = 0.55f)),
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(THUMB_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
            // A strip that stops between thumbnails has no "current" shot, and the release
            // handler above would then pick one the user did not aim at. The deceleration
            // leading up to that snap is still the ordinary velocity-based fling -- snapping
            // only adjusts where the motion that fling already produced comes to rest.
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            itemsIndexed(assets, key = { _, asset -> asset.id.value }) { index, asset ->
                val isCurrent = assets.getOrNull(currentIndex)?.id == asset.id
                Box(
                    modifier = Modifier
                        .size(THUMB.dp)
                        // Draw-time only, and read from the list's OWN layout info rather than
                        // captured state: this block re-runs on every frame layoutInfo changes
                        // (every scroll frame), with no recomposition, which is what lets every
                        // visible thumbnail's scale track the drag continuously instead of only
                        // the current item snapping in and out.
                        .graphicsLayer {
                            val info = listState.layoutInfo
                            val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val scale = if (itemInfo != null) {
                                val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                val itemCentre = itemInfo.offset + itemInfo.size / 2f
                                filmstripMagnification(
                                    distancePx = (itemCentre - viewportCentre).toFloat(),
                                    radiusPx = magnifyRadiusPx,
                                    peakScale = PEAK_SCALE,
                                )
                            } else {
                                1f
                            }
                            scaleX = scale
                            scaleY = scale
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
                    // The dim is the SELECTION cue, not a magnification one -- it stays keyed to
                    // isCurrent (which only updates on release) rather than to the continuous
                    // scale above, so there is one stable answer to "which photo is this" even
                    // while the loupe is sweeping across several thumbnails mid-drag.
                    if (!isCurrent) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    }
                }
            }
        }

        // The fixed viewfinder. It never moves and it answers no touches -- a plain decorative
        // Box with no pointerInput of its own is simply transparent to the LazyRow's gestures
        // beneath it. This is the "centre frame stays in focus" half of the request: the frame is
        // always exactly here, and it is the strip's content that travels past it.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(FRAME_SIZE.dp)
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)),
        )
    }
}

/**
 * The loupe curve: how much a thumbnail scales up, given its pixel distance from the strip's
 * fixed centre.
 *
 * A raised cosine (a Hann window), not linear or a hard cutoff: it starts and ends with ZERO
 * slope, so a thumbnail eases into and back out of magnification instead of visibly switching on
 * the instant it crosses [radiusPx] -- a kink there reads as a seam under a slow drag, which a
 * smooth falloff does not have.
 *
 * Pure and top-level so the curve's actual shape can be asserted without composing anything --
 * see [FilmstripMagnificationTest]. [radiusPx] and [peakScale] are parameters rather than
 * constants baked into the body precisely so those tests can probe values a real screen density
 * would never produce.
 */
internal fun filmstripMagnification(distancePx: Float, radiusPx: Float, peakScale: Float): Float {
    if (radiusPx <= 0f) return if (distancePx == 0f) peakScale else 1f
    val t = (abs(distancePx) / radiusPx).coerceIn(0f, 1f)
    val falloff = (cos(t * Math.PI.toFloat()) + 1f) / 2f
    return 1f + (peakScale - 1f) * falloff
}

private const val STRIP_HEIGHT = 84

/** One width for every thumbnail — see the KDoc on why the current one is not laid out larger. */
private const val THUMB = 48
private const val THUMB_GAP = 8

/** Scale applied to a thumbnail sitting exactly at the centre. */
private const val PEAK_SCALE = 1.4f

/**
 * Distance from centre at which magnification has fully eased back to 1x, in dp. Roughly one
 * thumbnail pitch either side of centre, so the effect reads as "the one under the frame, and a
 * hint of its immediate neighbours" rather than rippling down the whole visible strip.
 *
 * Deliberately not accompanied by any z-index management: at [PEAK_SCALE], neighbouring
 * thumbnails can touch or lightly overlap at the moment of a fast scrub, and later items simply
 * draw over earlier ones when that happens (LazyRow's ordinary child order). A dynamic z-index
 * keyed to a value that changes every scroll frame would mean re-evaluating draw order every
 * frame too, for a seam that is only visible for an instant during a fast fling -- not a trade
 * worth making for how briefly it shows.
 */
private const val MAGNIFY_RADIUS_DP = 60f

/**
 * The fixed viewfinder's own size -- a little larger than a peak-scaled thumbnail so its border
 * reads as containing the photo rather than clipping it.
 *
 * Not a `const val`: Kotlin's compile-time-constant rule permits the arithmetic here but not the
 * trailing `.toInt()`, so this is a plain top-level `val` instead, computed once at class load.
 */
private val FRAME_SIZE = (THUMB * PEAK_SCALE + 10f).toInt()
