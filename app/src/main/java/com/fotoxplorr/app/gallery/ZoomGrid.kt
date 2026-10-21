package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.adaptive.ZOOM_RUNG_LAST_GRID
import com.fotoxplorr.app.adaptive.columnsAcross
import com.fotoxplorr.app.adaptive.isStampRung
import com.fotoxplorr.app.adaptive.stampAcross
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.GridIndexMap

/** The paper a stamp is printed on. */
private val STAMP_PAPER = Color(0xFFEDE8DC)

/** The paper left showing around the photo of a stamp. */
private val STAMP_FRAME = 3.dp

/** The round holes along a stamp's edge: how big each is, and how far apart. */
private val STAMP_HOLE = 2.5.dp
private val STAMP_HOLE_PITCH = 5.dp

/**
 * The one grid the gallery is: rungs 1..6 are that many columns of photo tiles and the last rung is
 * the stamp view, a sheet of tiny stamps with a header row per month -- all in the same
 * [LazyVerticalGrid], one continuous scroll (owner #21: "still a long and continuous scroll, not
 * the left right movement it is now").
 *
 * One composable for every rung is what lets the pinch work at every rung in both directions: the
 * detector ([gridZoomGestures]) sits on this grid's root, and there is no rung that draws something
 * else without it. A rung change only swaps the span count (and, for the stamps, the item list and
 * the tile), and the grid keeps the first visible photo's place by its key.
 *
 * [layout] is built once by the screen (see [zoomLayoutFor]) and shared with the scrubber, the
 * pill, the arrow keys and the list the viewer pages through. Its headers are drawn here as full
 * width items; the grid's own item positions are then mapped by `GridIndexMap`, not assumed equal
 * to photo positions (TRAPS #5 and #9).
 *
 * A masonry grid (the "fit to tile" setting off) is the old staggered layout, still zoomable but
 * only through the column rungs: it never draws stamps or headers.
 */
@Composable
internal fun ZoomGrid(
    layout: ZoomLayout,
    rung: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    selectionActive: Boolean,
    emptyMessage: String,
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    gridState: LazyGridState,
    fitToTile: Boolean,
    loopAnimations: Boolean,
    animatedIds: Set<MediaId>,
) {
    val assets = layout.renderedAssets
    if (assets.isEmpty()) {
        GalleryMessage(emptyMessage)
        return
    }
    if (!fitToTile) {
        MediaGridScreen(
            assets = assets,
            columns = columnsAcross(rung, ZOOM_RUNG_LAST_GRID),
            favoriteIds = favoriteIds,
            sensitiveIds = sensitiveIds,
            blurSensitive = blurSensitive,
            selectedIds = selectedIds,
            emptyMessage = emptyMessage,
            selectionActive = selectionActive,
            onOpen = onOpen,
            onToggleSelection = onToggleSelection,
            gridState = gridState,
            fitToTile = false,
            loopAnimations = loopAnimations,
            animatedIds = animatedIds,
        )
        return
    }

    val bridge = LocalGridChromeBridge.current
    val stamps = isStampRung(rung)
    // The peeked photo, if any: one for the whole grid, drawn above all of it rather than inside
    // one cell's bounds, and only one can ever be open.
    var peeked by remember { mutableStateOf<MediaAsset?>(null) }

    // Holes in the stamps' edges, as ONE dotted outline per tile: a round-capped dash of no length is
    // a dot. Cheap enough for the ~150 stamps a screen holds, where clipping each to a
    // scalloped path would cost a boolean path operation per tile as it scrolls in.
    val density = LocalDensity.current
    val perforation = remember(density) {
        PathEffect.dashPathEffect(floatArrayOf(0.01f, with(density) { STAMP_HOLE_PITCH.toPx() }), 0f)
    }

    // The grid keeps its place across a rung change by item KEY, which a month header (Stamps)
    // does not survive into the headerless columns: re-anchor by ASSET, before the next measure.
    val gridIndexMap = remember(layout) {
        GridIndexMap(layout.groups.map { it.assets.size }, layout.hasHeaders, trailingFooter = true)
    }
    val anchor = remember { LayoutAnchor() }
    SideEffect {
        val previous = anchor.map
        if (previous != null && previous !== gridIndexMap) {
            reanchoredGridIndex(
                old = previous,
                oldHasHeaders = anchor.hasHeaders,
                new = gridIndexMap,
                newHasHeaders = layout.hasHeaders,
                // Still the old layout's top line: this runs before the next measure.
                oldFirstVisible = gridState.firstVisibleItemIndex,
            )?.let { gridState.requestScrollToItem(it) }
        }
        anchor.map = gridIndexMap
        anchor.hasHeaders = layout.hasHeaders
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .gridZoomGestures(bridge)
            // Pinching is not the only way: a screen reader can zoom the grid from its actions.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Zoom in") { bridge.onZoomStep(-1); true },
                    CustomAccessibilityAction("Zoom out") { bridge.onZoomStep(1); true },
                )
            },
    ) {
        val across = columnsAcross(rung, stampAcross(maxWidth.value))
        SideEffect { bridge.columnsAcross = across }

        LazyVerticalGrid(
            columns = GridCells.Fixed(across),
            state = gridState,
            modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
            verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        ) {
            layout.groups.forEach { group ->
                if (layout.hasHeaders) {
                    item(
                        key = "header:${group.key}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) { MonthHeader(group) }
                }
                items(group.assets, key = { it.id.value }, contentType = { if (stamps) "stamp" else "tile" }) { asset ->
                    if (stamps) {
                        StampTile(
                            asset = asset,
                            sensitive = asset.id in sensitiveIds,
                            blurSensitive = blurSensitive,
                            selected = asset.id in selectedIds,
                            selectionActive = selectionActive,
                            perforation = perforation,
                            onOpen = { onOpen(asset) },
                            onToggleSelection = { onToggleSelection(asset.id) },
                            onPeek = { peeked = asset },
                            onPeekEnd = { peeked = null },
                        )
                    } else {
                        MediaTile(
                            asset = asset,
                            favorite = asset.id in favoriteIds,
                            sensitive = asset.id in sensitiveIds,
                            blurSensitive = blurSensitive,
                            selected = asset.id in selectedIds,
                            selectionActive = selectionActive,
                            fitToTile = true,
                            loopAnimations = loopAnimations,
                            animated = asset.id in animatedIds,
                            onOpen = { onOpen(asset) },
                            onToggleSelection = { onToggleSelection(asset.id) },
                            onPeek = { peeked = asset },
                            onPeekEnd = { peeked = null },
                        )
                    }
                }
            }
            // The 88dp spacer every grid here ends with, so the last row clears the pill; it is
            // the one trailing item `GridIndexMap(trailingFooter = true)` counts.
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                Spacer(Modifier.height(88.dp))
            }
        }

        peeked?.let { asset ->
            MediaPeek(asset = asset, loopAnimations = loopAnimations, animated = asset.id in animatedIds)
        }
    }
}

/**
 * What [ZoomGrid] remembers of the layout before this one. Plain fields, not state: they are only
 * read by the next side effect, and a snapshot read would recompose the grid on every scroll.
 */
private class LayoutAnchor {
    var map: GridIndexMap? = null
    var hasHeaders = false
}

/** A month's header row in the stamp view: its name and how many photos follow. */
@Composable
private fun MonthHeader(group: TimelineGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GRID_BACKGROUND)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "${group.title}, ${group.assets.size} items"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.title, style = MaterialTheme.typography.titleSmall, color = Color.White)
        Text(
            "${group.assets.size}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * One photo as a postage stamp: a little paper around the photo, a ring of holes along the edge.
 *
 * Deliberately lean, because the stamp view draws a couple of hundred of these at once: one
 * gesture detector (shared with [MediaTile] through [tileGestures]), no right-click menu, no
 * badges and no clip. The photo's name stays its description for a screen reader, and a hold is
 * still the way to read a photo this small -- the long press setting applies here as everywhere.
 * A sensitive photo is blurred exactly as it is in the larger tiles.
 */
@Composable
private fun StampTile(
    asset: MediaAsset,
    sensitive: Boolean,
    blurSensitive: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    perforation: PathEffect,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onPeek: () -> Unit,
    onPeekEnd: () -> Unit,
) {
    val bridge = LocalGridChromeBridge.current
    Box(
        modifier = Modifier
            .aspectRatio(GRID_TILE_ASPECT)
            .background(STAMP_PAPER)
            .tileGestures(
                asset = asset,
                selectionActive = selectionActive,
                onOpen = onOpen,
                onToggleSelection = onToggleSelection,
                onPeek = onPeek,
                onPeekEnd = onPeekEnd,
            )
            .drawWithContent {
                drawContent()
                // The grid's ground is literal black, so black dots read as holes in the paper.
                drawRect(
                    color = Color.Black,
                    style = Stroke(width = STAMP_HOLE.toPx(), cap = StrokeCap.Round, pathEffect = perforation),
                )
            }
            .padding(STAMP_FRAME),
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier
                .fillMaxSize()
                .then(if (sensitive && blurSensitive) Modifier.blur(22.dp) else Modifier),
            contentScale = ContentScale.Crop,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
            }
        }
        // The keyboard's arrow-key cursor, as in MediaTile.
        if (bridge.keyboardFocusedId == asset.id) {
            Box(Modifier.fillMaxSize().border(1.5.dp, Color.White))
        }
    }
}
