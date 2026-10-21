package com.fotoxplorr.app.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The filmstrip: a horizontal strip of thumbnails for scrubbing between shots, drawn **over the
 * open photo** rather than inside any room (owner, second round, 2026-08-14: *"The filmstrip has
 * to appear not in the details view but in the view where the photo is selected"*).
 *
 * Three properties, two from that round and one from the owner's latest direction:
 *
 * - **The centre stays put; the strip moves through it.** Whatever thumbnail sits at the
 *   container's horizontal centre is the current photo. [sidePadding] makes the ends reachable
 *   and the snap fling lands a thumbnail exactly there. At rest the strip is flat -- the
 *   reference (5.png) has no frame, no dimming and no magnification, just the current photo
 *   centred in a row of [FilmstripGeometry.THUMB_WIDTH_DP]-wide thumbnails.
 * - **The strip DRIVES the photo, live.** Owner: *"swiping through the filmstrip cycles through
 *   the image that is focused — like an index strip swipe"*. Whenever the thumbnail nearest the
 *   centre CHANGES index mid-drag (or mid-fling), [onSelect] fires at once -- not on release.
 *   [FilmstripGeometry.nearestToCentre] is computed in a [derivedStateOf] over the list's own
 *   layout info, so it is re-evaluated per scroll frame but only *emits* when the index changes,
 *   and the host swaps the large photo exactly once per thumbnail, never per frame. The echo
 *   guard ([selfReported]) keeps the host's `currentIndex` feedback from fighting the finger.
 * - **It bulges while it is being dragged.** Owner: *"I want the strip to bulge up as I swipe
 *   through it, so that the strip is visible as I browse."* While `isScrollInProgress`, [bulge]
 *   animates to 1: the band grows from [FilmstripGeometry.REST_BAND_DP] to
 *   [FilmstripGeometry.BULGE_BAND_DP] (upward -- the bottom edge stays put) and the loupe's peak
 *   rises from 1x to [FilmstripGeometry.BULGE_PEAK_SCALE], so the thumbnails under the finger
 *   grow up into the headroom. On release it eases back. The band's height is read in a layout
 *   lambda and the scale in each thumbnail's layer lambda, so every frame of the bulge is
 *   re-layout and re-draw, never recomposition.
 *
 * **Roll-level, never asset-level.** The strip does not know or care what the current asset is: it
 * is shown for a video exactly as for a photo (see [ViewerChromePolicy.filmstripShown]). It must
 * stay that way, because it drives the host live -- a strip that vanished when the thumbnail
 * under the finger turned out to be a video would cancel the drag it was in the middle of.
 *
 * Every thumbnail is laid out at the same width; magnification is a draw-time effect on top of
 * it, never a change to the box itself. A wider current item would shift the strip's own
 * geometry as selection moved, which is what made the centre drift in the very first version.
 */
@Composable
fun FilmstripScrubber(
    assets: List<MediaAsset>,
    currentIndex: Int,
    onSelect: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
    /** Test seam: start the strip already bulged, as if a finger were on it. */
    initialBulge: Float = 0f,
    /**
     * True while the user is moving the strip (a finger on it, or the fling that finger threw),
     * false once it comes to rest -- and false again if the strip leaves composition mid-gesture,
     * so a host can never be left believing someone is still scrubbing. Not true while the strip
     * animates itself to the host's current photo. Lets the viewer pause a playing video the
     * moment the strip is touched and hold off starting the next one until the strip settles.
     */
    onScrubbingChanged: (Boolean) -> Unit = {},
) {
    if (assets.size <= 1) return
    val listState = rememberLazyListState()
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentAssets by rememberUpdatedState(assets)
    val currentOnScrubbingChanged by rememberUpdatedState(onScrubbingChanged)
    val density = LocalDensity.current
    val magnifyRadiusPx = with(density) { FilmstripGeometry.MAGNIFY_RADIUS_DP.dp.toPx() }
    val scope = rememberCoroutineScope()

    // The last index this strip itself reported. Without it the two effects below fight: a drag
    // reports index N, the host feeds N back as currentIndex, and the auto-centre effect then
    // animates to N while the user is still dragging — the strip stutters under the finger.
    var selfReported by remember { mutableIntStateOf(-1) }
    val bulge = remember { Animatable(initialBulge.coerceIn(0f, 1f)) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // The band's height follows the bulge at LAYOUT time: reading the Animatable here
            // re-lays out the strip each frame of the bulge without recomposing it.
            .layout { measurable, constraints ->
                val height = FilmstripGeometry.bandHeightDp(bulge.value).dp.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = height, maxHeight = height),
                )
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        // Half a viewport either side, so item 0 and the last item can both reach the centre.
        // Without this the ends are unreachable and "always centred" is simply not expressible.
        val sidePadding = (maxWidth - FilmstripGeometry.THUMB_WIDTH_DP.dp) / 2

        // Which thumbnail is under the centre, re-derived per scroll frame, emitted per CHANGE.
        val centreIndex by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                // The accessor form: evaluated per scroll frame, so it builds no list to do it.
                FilmstripGeometry.nearestToCentre(
                    viewportStart = info.viewportStartOffset,
                    viewportEnd = info.viewportEndOffset,
                    count = visible.size,
                    indexAt = { visible[it].index },
                    offsetAt = { visible[it].offset },
                    sizeAt = { visible[it].size },
                )
            }
        }

        // Centre the host's current photo. The first landing is a jump, not an animation: a
        // strip that scrolls in from item 0 every time the viewer opens is motion nobody asked
        // for. Skips the echo of our own report, and never fights a finger that is still down.
        var centredOnce by remember { mutableIntStateOf(0) }
        LaunchedEffect(currentIndex, assets.size) {
            val target = currentIndex.coerceIn(0, assets.lastIndex)
            if (target == selfReported || listState.isScrollInProgress) return@LaunchedEffect
            if (centredOnce == 0) {
                centredOnce = 1
                listState.scrollToItem(target)
            } else {
                listState.animateScrollToItem(target)
            }
        }

        // Whether the strip is moving because of the USER: a finger on it, or the fling that
        // finger threw. Not merely `isScrollInProgress`, which is also true while the strip
        // animates itself to the host's current photo (on open, after a page swipe, after a tap
        // on a far thumbnail) -- and a strip that bulged and reported every thumbnail it passed
        // on its own way to a photo would be driving the viewer, not following it.
        val dragged by listState.interactionSource.collectIsDraggedAsState()
        var userScroll by remember { mutableStateOf(false) }
        LaunchedEffect(listState) {
            snapshotFlow { dragged to listState.isScrollInProgress }.collect { (isDragged, scrolling) ->
                userScroll = isDragged || (userScroll && scrolling)
            }
        }

        // Tell the host when the user starts and stops moving the strip. The dispose guard matters
        // because the strip is chrome: toggling the chrome off (or opening the lift editor) in the
        // middle of a fling removes it with `userScroll` still true, and a host left holding
        // "scrubbing" would keep its video paused and un-armed for good.
        LaunchedEffect(listState) {
            snapshotFlow { userScroll }.collect { currentOnScrubbingChanged(it) }
        }
        DisposableEffect(Unit) {
            onDispose { currentOnScrubbingChanged(false) }
        }

        // Turning the strip IS selecting: the moment a different thumbnail is nearest the
        // centre while the strip is moving under the user's finger or fling, tell the host.
        LaunchedEffect(listState) {
            snapshotFlow { centreIndex to userScroll }
                .collect { (index, byUser) ->
                    if (!byUser || index == null) return@collect
                    val list = currentAssets
                    if (index !in list.indices || index == selfReported) return@collect
                    selfReported = index
                    currentOnSelect(list[index])
                }
        }

        // The bulge follows the finger: up while the user moves the strip, back down once it
        // stops. One job at a time, so a quick stop-start does not leave two animations racing.
        // The first emission is the strip's initial state, not a change, and is skipped:
        // animating "to rest" on composition would undo a deliberate initialBulge unseen.
        LaunchedEffect(listState) {
            var job: Job? = null
            snapshotFlow { userScroll }.drop(1).collect { scrolling ->
                job?.cancel()
                job = launch {
                    bulge.animateTo(
                        targetValue = if (scrolling) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (scrolling) BULGE_UP_MILLIS else BULGE_DOWN_MILLIS,
                            easing = BULGE_EASING,
                        ),
                    )
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = sidePadding,
                end = sidePadding,
                bottom = FilmstripGeometry.BOTTOM_PAD_DP.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(FilmstripGeometry.THUMB_GAP_DP.dp),
            // Bottom-aligned, so the band's extra height during the bulge is headroom ABOVE the
            // thumbnails and the magnified ones grow upward into it -- "bulge up".
            verticalAlignment = Alignment.Bottom,
            // A strip that stops between thumbnails has no "current" shot. The deceleration
            // leading up to the snap is still the ordinary velocity-based fling -- snapping only
            // adjusts where the motion that fling already produced comes to rest.
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            itemsIndexed(assets, key = { _, asset -> asset.id.value }) { index, asset ->
                // Draw order: the thumbnail nearest the centre on top, its neighbours under it,
                // so the loupe's magnified photo covers the ones beside it rather than being
                // covered by whichever happens to come later in the row. Keyed to centreIndex,
                // which changes once per thumbnail passed -- not to the per-frame scroll offset
                // -- so re-ordering costs a recomposition per thumbnail, never per frame.
                val depth = centreIndex?.let { -kotlin.math.abs(index - it) } ?: 0
                Box(
                    modifier = Modifier
                        .zIndex(depth.toFloat())
                        .width(FilmstripGeometry.THUMB_WIDTH_DP.dp)
                        .height(FilmstripGeometry.THUMB_HEIGHT_DP.dp)
                        // Draw-time only, and read from the list's OWN layout info rather than
                        // captured state: this block re-runs on every frame layoutInfo or the
                        // bulge changes, with no recomposition, which is what lets every visible
                        // thumbnail's scale track the drag continuously.
                        .graphicsLayer {
                            val info = listState.layoutInfo
                            val itemInfo = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val scale = if (itemInfo != null) {
                                val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                                val itemCentre = itemInfo.offset + itemInfo.size / 2f
                                FilmstripGeometry.magnification(
                                    distancePx = itemCentre - viewportCentre,
                                    radiusPx = magnifyRadiusPx,
                                    peakScale = FilmstripGeometry.peakScale(bulge.value),
                                )
                            } else {
                                1f
                            }
                            scaleX = scale
                            scaleY = scale
                            // Grow from the bottom edge, so the bottom of the strip stays a
                            // straight line and the bulge is all upward.
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val tapped = currentAssets.indexOfFirst { it.id == asset.id }
                            if (tapped >= 0) {
                                selfReported = tapped
                                scope.launch { listState.animateScrollToItem(tapped) }
                                currentOnSelect(currentAssets[tapped])
                            }
                        },
                ) {
                    MediaImage(
                        asset = asset,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

/** A quick rise and an unhurried settle: the strip should feel pulled up, then set down. */
private const val BULGE_UP_MILLIS = 180
private const val BULGE_DOWN_MILLIS = 260
private val BULGE_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
