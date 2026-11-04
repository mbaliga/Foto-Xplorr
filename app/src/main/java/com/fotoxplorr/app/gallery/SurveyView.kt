package com.fotoxplorr.app.gallery

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.absoluteOffset

/**
 * The compare / survey view: the photos of [set] in a shared-zoom grid. The photo itself is drawn
 * by [tile] (the caller's existing image slot), so this file has no image loading. Pinch and drag
 * move every cell together; the two buttons are the non-gesture path. Unverified on a device.
 */
@Composable
internal fun SurveyView(
    set: SurveySet,
    onDrop: (Long) -> Unit,
    modifier: Modifier = Modifier,
    tile: @Composable (id: Long, modifier: Modifier) -> Unit,
) {
    if (set.mode == SurveyMode.NONE) {
        Box(modifier.fillMaxSize(), Alignment.Center) { Text(SurveyCopy.NEED_TWO) }
        return
    }
    var view by remember { mutableStateOf(SyncedView()) }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val grid = surveyGrid(set.ids.size, wPx, hPx)
        val cellW = wPx / grid.columns
        val cellH = hPx / grid.rows
        Box(
            Modifier.fillMaxSize().pointerInput(grid) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    view = view.transformed(
                        zoom, pan.x, pan.y,
                        centroid.x % cellW - cellW / 2f, centroid.y % cellH - cellH / 2f,
                        cellW, cellH,
                    )
                }
            },
        ) {
            set.ids.forEachIndexed { i, id ->
                val r = surveyCell(i, set.ids.size, grid, wPx, hPx)
                Box(
                    Modifier
                        .absoluteOffset { IntOffset(r.left.toInt(), r.top.toInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .clipToBounds(),
                ) {
                    tile(
                        id,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = view.zoom
                            scaleY = view.zoom
                            translationX = view.panX
                            translationY = view.panY
                        },
                    )
                    IconButton(
                        onClick = { onDrop(id) },
                        modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                            .semantics { contentDescription = SurveyCopy.DROP },
                    ) { Text("x") }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter)) {
            IconButton(
                onClick = { view = view.stepZoom(false, cellW, cellH) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = SurveyCopy.ZOOM_OUT },
            ) { Text("-") }
            IconButton(
                onClick = { view = view.stepZoom(true, cellW, cellH) },
                modifier = Modifier.size(48.dp).semantics { contentDescription = SurveyCopy.ZOOM_IN },
            ) { Text("+") }
        }
    }
}
