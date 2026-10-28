package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
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
 * pill, the arrow keys and the list the viewer pages through. Its headers (the stamp view's
 * months, and the column rungs' while the timeline is on) are drawn here as full width items; the grid's own item positions are then mapped by `GridIndexMap`, not assumed equal
 * to photo positions (TRAPS #5 and #9).
 *
 * [anchor] is the browser's, not the grid's own: Settings, a destination screen and the map replace
 * this grid in the browsing `when`, and what it drew last must outlive that (see [LayoutAnchor]).
 *
 * A masonry grid (the "fit to tile" setting off) is the old staggered layout, still zoomable but
 * only through the column rungs: it never draws stamps or headers.
 *
 * [horizontal] turns the column rungs on their side (see [ScrollAxis]): the same items in a
 * [LazyHorizontalGrid] whose rungs count ROWS across the short axis, each month's header a narrow
 * full-height column, and each tile as wide as its row is tall allows ([GRID_TILE_ASPECT] is a
 * width over height ratio). It is the same [LazyGridState] type, so the scrubber, the pill and
 * `GridIndexMap` need no change. The stamp view and masonry ignore it and stay vertical.
 *
 * Shell edge zones: the shell claims a horizontal drag that STARTS within 56dp of the screen's left
 * or right edge for the rail and view options rooms, before this grid sees it. So a sideways grid
 * cannot begin a scroll from its first or last 56dp (a fling there opens a room instead) and has
 * to be started from further in; a drag that starts inside and runs out is unaffected. This is
 * accepted rather than worked around (insetting the grid's ends would waste the width the sideways
 * grid exists for); the vertical grid has no such conflict.
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
    anchor: LayoutAnchor,
    horizontal: Boolean = false,
) {
    val assets = layout.renderedAssets
    if (assets.isEmpty()) {
        SideEffect { anchor.forget() }
        GalleryMessage(emptyMessage)
        return
    }
    if (!fitToTile) {
        SideEffect { anchor.forget() }
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
    // Whatever the screen asks for, the stamp view is laid out for a screen's width.
    val sideways = horizontal && !stamps
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
    SideEffect {
        anchor.settle(
            map = gridIndexMap,
            hasHeaders = layout.hasHeaders,
            grouping = layout.grouping,
            sideways = sideways,
            // Still the old layout's top line: this runs before the next measure.
            firstVisible = gridState.firstVisibleItemIndex,
        )?.let { gridState.requestScrollToItem(it) }
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
        // Tiles across the short side: columns for the vertical grid, rows for the sideways one.
        SideEffect { bridge.columnsAcross = across }

        val gridItems: LazyGridScope.() -> Unit = {
            zoomGridItems(
                layout = layout,
                stamps = stamps,
                sideways = sideways,
                favoriteIds = favoriteIds,
                sensitiveIds = sensitiveIds,
                blurSensitive = blurSensitive,
                selectedIds = selectedIds,
                selectionActive = selectionActive,
                loopAnimations = loopAnimations,
                animatedIds = animatedIds,
                perforation = perforation,
                onOpen = onOpen,
                onToggleSelection = onToggleSelection,
                onPeek = { peeked = it },
                onPeekEnd = { peeked = null },
            )
        }

        if (sideways) {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(across),
                state = gridState,
                modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
                // The pill and the two discs float over the bottom edge for good here (a vertical
                // grid scrolls clear of them with its footer), so the rows stop above them.
                contentPadding = PaddingValues(bottom = SIDE_GRID_BOTTOM_BAND_DP.dp),
                verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            ) {
                gridItems()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(across),
                state = gridState,
                modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
                verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            ) {
                gridItems()
            }
        }

        peeked?.let { asset ->
            MediaPeek(asset = asset, loopAnimations = loopAnimations, animated = asset.id in animatedIds)
        }
    }
}

/**
 * The grid's items, the same for both axes: per group an optional header (spanning the whole line,
 * so a full-width row on the vertical grid and a full-height column on the sideways one), then its
 * photos, then the one trailing footer `GridIndexMap(trailingFooter = true)` counts.
 */
private fun LazyGridScope.zoomGridItems(
    layout: ZoomLayout,
    stamps: Boolean,
    sideways: Boolean,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    selectionActive: Boolean,
    loopAnimations: Boolean,
    animatedIds: Set<MediaId>,
    perforation: PathEffect,
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    onPeek: (MediaAsset) -> Unit,
    onPeekEnd: () -> Unit,
) {
    layout.groups.forEach { group ->
        if (layout.hasHeaders) {
            item(
                key = "header:${group.key}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) { if (sideways) SideMonthHeader(group) else MonthHeader(group) }
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
                    onPeek = { onPeek(asset) },
                    onPeekEnd = onPeekEnd,
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
                    onPeek = { onPeek(asset) },
                    onPeekEnd = onPeekEnd,
                )
            }
        }
    }
    // The 88dp spacer every vertical grid here ends with, so the last row clears the pill (the
    // sideways grid keeps that room along its bottom instead, and ends with a thin column); either
    // way it is the one trailing item `GridIndexMap(trailingFooter = true)` counts.
    item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
        if (sideways) Spacer(Modifier.width(SIDE_FOOTER_DP.dp)) else Spacer(Modifier.height(88.dp))
    }
}

/**
 * A header row: the month (or, on the column rungs with day grouping, the day) and how many photos
 * follow. The stamp view always has them; the column rungs have them while the timeline is on.
 */
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
 * A month's header in the sideways grid: a narrow column as tall as the rows, its title and count
 * turned to read upward along it. The same words and the same heading semantics as [MonthHeader].
 */
@Composable
private fun SideMonthHeader(group: TimelineGroup) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(SIDE_HEADER_DP.dp)
            .background(GRID_BACKGROUND)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "${group.title}, ${group.assets.size} items"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .turnedToRead()
                .padding(start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
            Text(
                "${group.assets.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Lays the content out as a line of text as long as the space is tall, then turns it a quarter
 * turn so it reads upward and fills the column. The measured size is swapped to match, so it takes
 * a column's width rather than a row's.
 */
private fun Modifier.turnedToRead(): Modifier = layout { measurable, constraints ->
    val length = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints(minWidth = length, maxWidth = length, minHeight = 0, maxHeight = Constraints.Infinity),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2) {
            rotationZ = -90f
        }
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
