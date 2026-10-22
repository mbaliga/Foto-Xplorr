@file:OptIn(ExperimentalLayoutApi::class)

package com.fotoxplorr.app.aquarium

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.clearOfShellEdges
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.ui.RoomStyle

/** Finger moves smaller than this are not new points, in dp. */
private const val MIN_POINT_SPACING_DP = 3f

/** The board's paper: fixed so every palette colour reads on it, in either theme. */
private val PAPER = Color(0xFFF6F3EA)
private val PAPER_INK = Color(0xFF6B665C)

/**
 * The drawing step. Stateless: the [draft] comes in and an edit goes out through [onDraft] (a
 * function from the current draft to the next, so a quick sequence never works from a stale one).
 *
 * The board is a 4:3 sheet with a finger path and a no-finger path. Finger: one pointer draws a
 * stroke, undo, redo and start over are buttons. No finger (or no wish to draw): the Shapes row
 * adds a ready-made fish, which can then be recoloured and added to. Every control is a 48 dp
 * target with a spoken name, the chosen colour and brush are marked with a tick as well as a ring,
 * and the board itself announces how many strokes it holds.
 *
 * The part that takes drags is inset with [clearOfShellEdges] (TRAPS #72), so a stroke that starts
 * near the screen edge is not taken by the shell as a room opening; the paper is only that part.
 * That costs 120 dp of width on a 360 dp phone, so the board is small in portrait: its fit is a
 * device check. In landscape the controls move to the right and the board gets the height.
 */
@Composable
internal fun DrawScreen(
    draft: DrawDraft,
    onDraft: ((DrawDraft) -> DrawDraft) -> Unit,
    tankFull: Boolean,
    busy: Boolean,
    message: String?,
    onSwim: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val sketch = draft.sketch
    val controls: @Composable () -> Unit = {
        DrawControls(draft = draft, onDraft = onDraft)
    }
    val swimBar: @Composable () -> Unit = {
        SwimBar(draft = draft, tankFull = tankFull, busy = busy, message = message, onSwim = onSwim)
    }
    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AquariumIconButton(Icons.AutoMirrored.Outlined.ArrowBack, AquariumCopy.BACK, onBack)
            Text(
                AquariumCopy.DRAW_TITLE,
                style = MaterialTheme.typography.titleMedium,
                color = RoomStyle.Ink,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            AquariumIconButton(Icons.AutoMirrored.Outlined.Undo, AquariumCopy.UNDO, { onDraft { it.copy(sketch = it.sketch.undo()) } }, enabled = sketch.canUndo && !busy)
            AquariumIconButton(Icons.AutoMirrored.Outlined.Redo, AquariumCopy.REDO, { onDraft { it.copy(sketch = it.sketch.redo()) } }, enabled = sketch.canRedo && !busy)
            AquariumIconButton(Icons.Outlined.DeleteSweep, AquariumCopy.CLEAR, { confirmClear = true }, enabled = !sketch.isEmpty && !busy)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    Board(draft, onDraft, tall = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(260.dp).fillMaxHeight().padding(end = 8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                        swimBar()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // The board is as tall as the width gives it and the controls follow; the whole
                    // block scrolls on a short screen, and Swim stays below it, always in view.
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Board(draft, onDraft, tall = false, modifier = Modifier.fillMaxWidth())
                        Column(Modifier.padding(horizontal = 12.dp)) { controls() }
                    }
                    Column(Modifier.padding(horizontal = 12.dp)) { swimBar() }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(AquariumCopy.CLEAR_QUESTION) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onDraft { it.copy(sketch = it.sketch.clear()) }
                }) { Text(AquariumCopy.CLEAR) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(AquariumCopy.CLEAR_KEEP) } },
        )
    }
}

@Composable
private fun DrawControls(draft: DrawDraft, onDraft: ((DrawDraft) -> DrawDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(AquariumCopy.COLOUR)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SketchPalette.swatches.forEachIndexed { i, swatch ->
                SwatchButton(swatch, selected = i == draft.colorIndex, onClick = { onDraft { it.copy(colorIndex = i) } })
            }
        }
        Label(AquariumCopy.BRUSH)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushSize.entries.forEach { brush ->
                AquariumChip(brush.label, selected = brush == draft.brush, onClick = { onDraft { it.copy(brush = brush) } })
            }
            AquariumChip(AquariumCopy.HEAD_LEFT, selected = draft.headLeft, onClick = { onDraft { it.copy(headLeft = !it.headLeft) } })
        }
        Label(AquariumCopy.SHAPES)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadyShape.entries.forEach { shape ->
                AquariumChip(shape.label, selected = false, onClick = {
                    onDraft { it.copy(sketch = ReadyShapes.addTo(it.sketch, shape, it.argb, it.brush.width)) }
                })
            }
        }
    }
}

/** What stands in the way of Swim, said in one sentence, and the button. Always on screen. */
@Composable
private fun SwimBar(draft: DrawDraft, tankFull: Boolean, busy: Boolean, message: String?, onSwim: () -> Unit) {
    val note = swimNote(busy = busy, tankFull = tankFull, strokesFull = draft.sketch.isFull, message = message)
    Column(Modifier.padding(vertical = 6.dp)) {
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        AquariumButton(
            AquariumCopy.SWIM,
            onClick = onSwim,
            enabled = canSwim(draft.sketch.isEmpty, tankFull, busy),
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Swim needs ink on the board, room in the tank, and no save already under way. */
internal fun canSwim(sketchEmpty: Boolean, tankFull: Boolean, busy: Boolean): Boolean = !sketchEmpty && !tankFull && !busy

/** The one line under the board, most pressing first, or null for none. */
internal fun swimNote(busy: Boolean, tankFull: Boolean, strokesFull: Boolean, message: String?): String? = when {
    busy -> AquariumCopy.WORKING
    tankFull -> AquariumCopy.TANK_FULL
    strokesFull -> AquariumCopy.STROKES_FULL
    else -> message
}

@Composable
private fun Label(text: String) {
    Text(text, style = RoomStyle.Eyebrow, color = RoomStyle.InkFaint, modifier = Modifier.padding(top = 8.dp))
}

/**
 * The paper and its drag area. The paper is a 4:3 box centred in the part of the screen that is
 * clear of the shell's edge zones, so every stroke starts inside it.
 */
@Composable
private fun Board(draft: DrawDraft, onDraft: ((DrawDraft) -> DrawDraft) -> Unit, tall: Boolean, modifier: Modifier) {
    val latest by rememberUpdatedState(draft)
    val latestEdit by rememberUpdatedState(onDraft)
    val live = remember { mutableStateListOf<Offset>() }
    val strokes = draft.sketch.strokes
    val previews = remember(strokes) { strokes.map(::previewOf) }
    val minSpacing = with(androidx.compose.ui.platform.LocalDensity.current) { MIN_POINT_SPACING_DP.dp.toPx() }
    Box(modifier.clearOfShellEdges(), contentAlignment = Alignment.Center) {
        Canvas(
            (if (tall) Modifier else Modifier.fillMaxWidth())
                .aspectRatio(Sketch.BOARD_ASPECT)
                .background(PAPER)
                .semantics { contentDescription = AquariumCopy.board(strokes.size) }
                .pointerInput(minSpacing) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        fun norm(p: Offset) = NormPoint((p.x / w).coerceIn(0f, 1f), (p.y / h).coerceIn(0f, 1f))
                        val points = ArrayList<NormPoint>()
                        points.add(norm(down.position))
                        live.clear()
                        live.add(down.position)
                        var last = down.position
                        var finished = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                val p = change.position
                                if ((p - last).getDistance() >= minSpacing && points.size < Sketch.MAX_POINTS * 2) {
                                    points.add(norm(p))
                                    live.add(p)
                                    last = p
                                }
                                if (!change.pressed) {
                                    finished = true
                                    break
                                }
                            }
                        } finally {
                            live.clear()
                        }
                        if (finished) {
                            val d = latest
                            latestEdit { cur -> cur.copy(sketch = cur.sketch.add(points, d.argb, d.brush.width)) }
                        }
                    }
                },
        ) {
            val unit = size.width
            scale(scale = unit, pivot = Offset.Zero) {
                for (p in previews) drawPreview(p)
            }
            if (live.isNotEmpty()) {
                val d = latest
                val width = d.brush.width * unit
                if (live.size == 1) {
                    drawCircle(Color(d.argb), radius = width / 2f, center = live[0])
                } else {
                    val path = Path().apply {
                        moveTo(live[0].x, live[0].y)
                        for (i in 1 until live.size) lineTo(live[i].x, live[i].y)
                    }
                    drawPath(path, Color(d.argb), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (strokes.isEmpty() && live.isEmpty()) {
            Text(
                AquariumCopy.BOARD_HINT,
                color = PAPER_INK,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

/** A stroke ready to draw: a path in BOARD-WIDTH units (x, and y over the board aspect), so one uniform scale places it. */
internal class Preview(val path: Path, val argb: Int, val width: Float, val filled: Boolean, val dot: Offset?)

internal fun previewOf(s: DrawStroke): Preview {
    val pts = if (s.filled) OutlineFill.closedOutline(s.points, s.width) else s.points
    val path = Path()
    pts.forEachIndexed { i, p ->
        val x = p.x
        val y = p.y / Sketch.BOARD_ASPECT
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val dot = if (s.points.size == 1) Offset(s.points[0].x, s.points[0].y / Sketch.BOARD_ASPECT) else null
    return Preview(path, s.argb, s.width, s.filled, dot)
}

private fun DrawScope.drawPreview(p: Preview) {
    val color = Color(p.argb)
    if (p.dot != null) {
        drawCircle(color, radius = p.width / 2f, center = p.dot)
        return
    }
    if (p.filled) drawPath(p.path, color, style = Fill)
    drawPath(p.path, color, style = Stroke(p.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
