package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.adaptive.isStampRung
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.organize.GridIndexMap

/**
 * Whether the justified rows are what the grid draws: the setting is on, on a column rung (the
 * stamps keep their own look, so the year/month/day stamps are untouched), and not in masonry,
 * which has its own shapes.
 */
internal fun justifiedActive(setting: Boolean, masonry: Boolean, rung: Int): Boolean =
    setting && !masonry && !isStampRung(rung)

/** A [JustifiedLayout] together with the exact list it was built for, as the grid publishes it. */
internal class JustifiedSnapshot(val assets: List<MediaAsset>, val layout: JustifiedLayout)

/**
 * The one index space the scrubber, the pill, the arrow keys and the anchor share. The square grid
 * maps through [GridIndexMap] (headers and the footer); the justified grid has one lazy item per
 * ROW plus the footer, so an asset's grid index is its row.
 */
internal interface GridIndexSpace {
    val itemCount: Int
    fun gridIndexOf(assetIndex: Int): Int
    fun assetIndexAt(gridIndex: Int): Int
}

internal class MapIndexSpace(private val map: GridIndexMap) : GridIndexSpace {
    override val itemCount: Int get() = map.itemCount
    override fun gridIndexOf(assetIndex: Int): Int = map.gridIndexOf(assetIndex)
    override fun assetIndexAt(gridIndex: Int): Int = map.assetIndexAt(gridIndex)
}

internal class JustifiedIndexSpace(private val layout: JustifiedLayout) : GridIndexSpace {
    /** The rows and the one trailing footer. */
    override val itemCount: Int get() = layout.rows + 1
    override fun gridIndexOf(assetIndex: Int): Int =
        layout.rowOf(assetIndex.coerceIn(0, (layout.itemCount - 1).coerceAtLeast(0))).coerceAtLeast(0)
    override fun assetIndexAt(gridIndex: Int): Int {
        if (layout.rows == 0) return 0
        return layout.rowItems(gridIndex.coerceIn(0, layout.rows - 1)).first
    }
}

/** The index space for what is on screen: justified only when its snapshot is for these photos. */
internal fun gridIndexSpace(map: GridIndexMap, justified: JustifiedSnapshot?, rendered: List<MediaAsset>): GridIndexSpace =
    if (justified != null && justified.assets === rendered) JustifiedIndexSpace(justified.layout) else MapIndexSpace(map)

/**
 * The justified grid: one lazy item per row, each row a [Row] of tiles at their own shape. A
 * [LazyVerticalGrid] of one column rather than a LazyColumn so it drives the SAME [LazyGridState]
 * as every other rung, and the scrubber, the pill and the arrow keys keep working through
 * [JustifiedIndexSpace]. Vertical only; headers are not drawn here.
 */
@Composable
internal fun JustifiedGrid(
    assets: List<MediaAsset>,
    columns: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    selectionActive: Boolean,
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    gridState: LazyGridState,
    loopAnimations: Boolean,
    animatedIds: Set<MediaId>,
    anchor: LayoutAnchor? = null,
) {
    val bridge = LocalGridChromeBridge.current
    var peeked by remember { mutableStateOf<MediaAsset?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().gridZoomGestures(bridge)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { GRID_GUTTER.toPx() }
        // The rung's column count sets the row height: about as tall as a tile of that rung.
        val target = widthPx / columns.coerceAtLeast(1)
        val layout = remember(assets, widthPx, target, gapPx) {
            JustifiedLayout.build(assets.size, { assets[it].aspectRatio }, widthPx, target, gapPx)
        }
        SideEffect {
            bridge.justified = JustifiedSnapshot(assets, layout)
            // Arrow keys step about one row.
            bridge.columnsAcross = if (layout.rows == 0) 1 else (assets.size / layout.rows).coerceAtLeast(1)
            // Keep the photo at the top across a rung change or a switch from the square grid:
            // firstVisibleItemIndex is still the previous layout's top line here.
            anchor?.settleJustified(layout, assets, gridState.firstVisibleItemIndex)
                ?.let { gridState.requestScrollToItem(it) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            state = gridState,
            modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
            verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        ) {
            items(count = layout.rows, key = { row -> "jrow:${assets[layout.rowItems(row).first].id.value}" }, contentType = { "jrow" }) { row ->
                val h = with(density) { layout.rowHeight(row).toDp() }
                Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                    for (i in layout.rowItems(row)) {
                        val asset = assets[i]
                        Box(Modifier.width(with(density) { layout.itemWidth(i).toDp() }).height(h)) {
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
                                tileAspectRatio = JustifiedLayout.normalizedAspect(asset.aspectRatio),
                            )
                        }
                    }
                }
            }
            item(key = "footer", contentType = "footer") { Spacer(Modifier.height(88.dp)) }
        }
        peeked?.let { asset ->
            MediaPeek(asset = asset, loopAnimations = loopAnimations, animated = asset.id in animatedIds)
        }
    }
}
