package com.fotoxplorr.app.hyle

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/**
 * Side padding from [SheetGeometry.contentInsets], with the pull [lift] read in the LAYOUT phase.
 *
 * Reading the lift in composition recomposed the whole room on every frame of a pull; here only
 * the layout reruns, and the content's own composition is left alone.
 */
fun Modifier.sheetInsets(
    lift: () -> Float,
    depthDp: Float,
    baseDp: Float,
    gutterStartDp: Float = 0f,
    gutterEndDp: Float = 0f,
): Modifier = layout { measurable, constraints ->
    val insets = SheetGeometry.contentInsets(
        cornerDp = SheetGeometry.sheetCornerDp(lift()),
        depthDp = depthDp,
        baseDp = baseDp,
        gutterStartDp = gutterStartDp,
        gutterEndDp = gutterEndDp,
    )
    val start = insets.startDp.dp.roundToPx()
    val end = insets.endDp.dp.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = -(start + end)))
    layout(constraints.constrainWidth(placeable.width + start + end), placeable.height) {
        placeable.place(start, 0)
    }
}
