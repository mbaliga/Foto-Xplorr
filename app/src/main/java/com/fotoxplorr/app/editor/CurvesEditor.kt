package com.fotoxplorr.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Which curve a tap/drag in [CurvesEditor] is currently editing. */
enum class CurveChannel(val label: String, val color: Color) {
    RGB("RGB", Color.White),
    RED("R", Color(0xFFEF5350)),
    GREEN("G", Color(0xFF66BB6A)),
    BLUE("B", Color(0xFF42A5F5)),
}

/**
 * A draggable tone-curve graph: RGB/R/G/B tabs, tap to add a control point, drag to move one,
 * drag it out of the graph (or long-press it) to remove it.
 *
 * Delegates every bit of actual curve maths to [ToneCurve] — [ToneCurve.withPoint] and
 * [ToneCurve.withoutPointAt], the same monotone-Hermite-interpolated curve
 * [ToneCurveTest] already covers, and the same field [Adjustments] already modelled before this
 * editor existed (`rgbCurve`/`redCurve`/`greenCurve`/`blueCurve`). This file is UI glue only: pixel
 * hit-testing and drag-to-canvas-coordinate conversion, nothing that belongs on the JVM-testable
 * side of the line.
 *
 * @param curveFor reads whichever of the four curves [channel] refers to.
 * @param onCurveChange called with the channel that changed and its new curve — a callback
 *   shaped this way, rather than four separate lambdas, is what keeps this composable's own
 *   signature from growing every time [Adjustments] gains another curve field.
 */
@Composable
fun CurvesEditor(
    curveFor: (CurveChannel) -> ToneCurve,
    onCurveChange: (CurveChannel, ToneCurve) -> Unit,
    modifier: Modifier = Modifier,
) {
    var channel by remember { mutableStateOf(CurveChannel.RGB) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CurveChannel.entries.forEach { entry ->
            val selected = entry == channel
            Text(
                entry.label,
                color = if (selected) Color.Black else entry.color,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(50),
                    )
                    .clickable { channel = entry; draggingIndex = null }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }

    val curve = curveFor(channel)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .pointerInput(channel) {
                detectTapGestures { tap ->
                    val point = CurvePoint(
                        (tap.x / size.width).coerceIn(0f, 1f),
                        (1f - tap.y / size.height).coerceIn(0f, 1f),
                    )
                    onCurveChange(channel, curveFor(channel).withPoint(point))
                }
            }
            .pointerInput(channel) {
                detectDragGestures(
                    onDragStart = { start ->
                        val current = curveFor(channel)
                        val nx = start.x / size.width
                        val ny = 1f - start.y / size.height
                        draggingIndex = current.points.indices.minByOrNull { index ->
                            val p = current.points[index]
                            kotlin.math.hypot((p.x - nx).toDouble(), (p.y - ny).toDouble())
                        }?.takeIf { index ->
                            val p = current.points[index]
                            kotlin.math.hypot((p.x - nx).toDouble(), (p.y - ny).toDouble()) < CURVE_HIT_RADIUS_NORM
                        }
                    },
                    onDragEnd = { draggingIndex = null },
                    onDragCancel = { draggingIndex = null },
                ) { change, _ ->
                    val index = draggingIndex ?: return@detectDragGestures
                    change.consume()
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    val current = curveFor(channel)
                    val isEndpoint = index == 0 || index == current.points.lastIndex
                    val draggedOut = change.position.x < -DRAG_OUT_MARGIN_PX ||
                        change.position.x > size.width + DRAG_OUT_MARGIN_PX ||
                        change.position.y < -DRAG_OUT_MARGIN_PX ||
                        change.position.y > size.height + DRAG_OUT_MARGIN_PX
                    onCurveChange(
                        channel,
                        // Endpoints (x=0 or x=1) are structural -- see ToneCurve.withoutPointAt's
                        // own doc -- so dragging one out just pins it at the nearest edge instead
                        // of trying and failing to remove it.
                        if (draggedOut && !isEndpoint) {
                            current.withoutPointAt(current.points[index].x)
                        } else {
                            current.withPoint(CurvePoint(if (isEndpoint) current.points[index].x else nx, ny))
                        },
                    )
                }
            },
    ) {
        val stepX = size.width / 255f
        val path = androidx.compose.ui.graphics.Path()
        val lut = curve.toLut()
        lut.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value / 255f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = channel.color, style = Stroke(width = 3f, cap = StrokeCap.Round))

        curve.points.forEach { point ->
            drawCircle(
                color = channel.color,
                radius = 10f,
                center = Offset(point.x * size.width, size.height - point.y * size.height),
            )
            drawCircle(
                color = Color.Black,
                radius = 4f,
                center = Offset(point.x * size.width, size.height - point.y * size.height),
            )
        }
    }
    Text(
        "Tap to add a point, drag to move it, drag it off the graph to remove it.",
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** How close (in normalised curve-space) a drag has to start to a control point to grab it,
 *  rather than starting a new point at the tap location. */
private const val CURVE_HIT_RADIUS_NORM = 0.08

/** How far outside the graph's own bounds, in pixels, a drag has to travel before it counts as
 *  "dragged out" and removes the point, rather than merely clamping to the edge. */
private const val DRAG_OUT_MARGIN_PX = 48f
