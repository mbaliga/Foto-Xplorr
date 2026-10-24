package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything on the drawing screen that is worth keeping when the activity is recreated or the
 * process is killed: the strokes, the chosen colour and brush, and which way the head points.
 * Pure data, so the codec below runs in a plain JVM test.
 *
 * Saved by [DrawDraftCodec] as one short string (a `rememberSaveable` goes through a size-limited
 * Bundle, so the string is bounded). What does NOT come back after a process death: the redo
 * stack, a stroke being drawn at that moment, and the last message. A restored drawing is also
 * slightly smoother than the one drawn, because long strokes are thinned for saving.
 */
data class DrawDraft(
    val sketch: Sketch = Sketch(),
    /** Index into [SketchPalette.swatches]. */
    val colorIndex: Int = DEFAULT_COLOR_INDEX,
    val brush: BrushSize = BrushSize.MEDIUM,
    /** True when the person drew a creature whose head is on the left. */
    val headLeft: Boolean = false,
) {
    val argb: Int get() = SketchPalette.swatches[colorIndex.coerceIn(0, SketchPalette.swatches.lastIndex)].argb

    companion object {
        /** Orange: the usual fish. */
        const val DEFAULT_COLOR_INDEX = 1
    }
}

/**
 * The draft once a Swim that started from [taken] has saved its creature: [now] with an empty
 * sketch, or null when the sketch is no longer the one that was saved (the board is locked while a
 * save runs, but a drawing changed in that window is the person's and is never wiped).
 */
internal fun draftAfterSwim(taken: DrawDraft, now: DrawDraft): DrawDraft? =
    if (now.sketch === taken.sketch) now.copy(sketch = Sketch()) else null

/**
 * [DrawDraft] to and from a string. Format: `1~color~brush~headLeft~stroke|stroke|...`, a stroke
 * being `argbHex,widthIn1e4,points` and a point 6 hex digits (x and y, 12 bits each, so the board
 * is cut into 4096 steps, finer than a fingertip). The reader is tolerant: a damaged stroke is
 * skipped, a damaged header gives an empty draft, it never throws.
 */
object DrawDraftCodec {
    private const val VERSION = "1"
    const val MAX_POINTS_PER_STROKE = 300
    const val MAX_POINTS_TOTAL = 6000
    private const val STEPS = 4095

    fun encode(draft: DrawDraft): String {
        val strokes = draft.sketch.strokes
        val budget = if (strokes.isEmpty()) MAX_POINTS_PER_STROKE else minOf(MAX_POINTS_PER_STROKE, MAX_POINTS_TOTAL / strokes.size)
        val body = strokes.joinToString("|") { s ->
            val pts = thin(s.points, budget)
            buildString {
                append(s.argb.toUInt().toString(16).padStart(8, '0'))
                append(',')
                append((s.width * 10_000f).roundToInt())
                append(',')
                for (p in pts) {
                    append(hex12(p.x))
                    append(hex12(p.y))
                }
            }
        }
        return listOf(VERSION, draft.colorIndex, draft.brush.ordinal, if (draft.headLeft) 1 else 0, body).joinToString("~")
    }

    fun decode(text: String): DrawDraft {
        val parts = text.split('~', limit = 5)
        if (parts.size < 5 || parts[0] != VERSION) return DrawDraft()
        val color = parts[1].toIntOrNull()?.coerceIn(0, SketchPalette.swatches.lastIndex) ?: DrawDraft.DEFAULT_COLOR_INDEX
        val brush = BrushSize.entries.getOrNull(parts[2].toIntOrNull() ?: -1) ?: BrushSize.MEDIUM
        var sketch = Sketch()
        if (parts[4].isNotEmpty()) {
            for (raw in parts[4].split('|')) {
                val s = parseStroke(raw) ?: continue
                sketch = sketch.add(s.points, s.argb, s.width)
            }
        }
        return DrawDraft(sketch, color, brush, parts[3] == "1")
    }

    private fun parseStroke(raw: String): DrawStroke? {
        val f = raw.split(',')
        if (f.size != 3) return null
        val argb = f[0].toUIntOrNull(16)?.toInt() ?: return null
        val width = (f[1].toIntOrNull() ?: return null) / 10_000f
        val hex = f[2]
        if (hex.isEmpty() || hex.length % 6 != 0) return null
        val pts = ArrayList<NormPoint>(hex.length / 6)
        var i = 0
        while (i < hex.length) {
            val x = hex.substring(i, i + 3).toIntOrNull(16) ?: return null
            val y = hex.substring(i + 3, i + 6).toIntOrNull(16) ?: return null
            pts.add(NormPoint(x.toFloat() / STEPS, y.toFloat() / STEPS))
            i += 6
        }
        return DrawStroke(pts, argb, width, filled = false)
    }

    private fun hex12(v: Float): String =
        (if (v.isFinite()) (v.coerceIn(0f, 1f) * STEPS).roundToInt() else 0).toString(16).padStart(3, '0')

    /** Even thinning to at most [max] points that keeps the first and the last. */
    private fun thin(points: List<NormPoint>, max: Int): List<NormPoint> {
        if (points.size <= max || max < 2) return if (points.size <= max) points else points.take(max.coerceAtLeast(1))
        val last = points.size - 1
        return List(max) { points[(it.toLong() * last / (max - 1)).toInt()] }
    }
}

/**
 * Ready-made starting shapes for a person who cannot or would rather not draw (a way into the
 * feature that needs no finger path at all). Each is a list of strokes on the 4:3 board, head on
 * the right, ready to be added to a [Sketch]; the body takes the chosen colour and the eye is black.
 */
enum class ReadyShape(val label: String) {
    ROUND_FISH(AquariumCopy.SHAPE_ROUND_FISH),
    LONG_FISH(AquariumCopy.SHAPE_LONG_FISH),
    TADPOLE(AquariumCopy.SHAPE_TADPOLE),
}

class ShapeStroke(val points: List<NormPoint>, val argb: Int, val width: Float)

object ReadyShapes {
    private const val EYE = 0xFF212121.toInt()

    fun strokes(shape: ReadyShape, argb: Int, width: Float): List<ShapeStroke> = when (shape) {
        ReadyShape.ROUND_FISH -> listOf(
            ShapeStroke(ellipse(0.55f, 0.5f, 0.26f, 0.3f), argb, width),
            ShapeStroke(polygon(listOf(0.32f to 0.5f, 0.1f to 0.28f, 0.1f to 0.72f)), argb, width),
            ShapeStroke(listOf(NormPoint(0.67f, 0.4f)), EYE, width.coerceAtLeast(BrushSize.MEDIUM.width)),
        )
        ReadyShape.LONG_FISH -> listOf(
            ShapeStroke(ellipse(0.55f, 0.5f, 0.34f, 0.14f), argb, width),
            ShapeStroke(polygon(listOf(0.22f to 0.5f, 0.05f to 0.3f, 0.05f to 0.7f)), argb, width),
            ShapeStroke(listOf(NormPoint(0.78f, 0.45f)), EYE, width.coerceAtLeast(BrushSize.MEDIUM.width)),
        )
        ReadyShape.TADPOLE -> listOf(
            ShapeStroke(ellipse(0.78f, 0.5f, 0.12f, 0.16f), argb, width),
            ShapeStroke(List(25) { i -> NormPoint(0.68f - 0.58f * i / 24f, 0.5f + 0.08f * sin(i * 0.9f)) }, argb, width),
            ShapeStroke(listOf(NormPoint(0.82f, 0.45f)), EYE, width.coerceAtLeast(BrushSize.MEDIUM.width)),
        )
    }

    /** True when every stroke of [shape] fits in [sketch]; a shape is whole or not added. */
    fun fits(sketch: Sketch, shape: ReadyShape): Boolean =
        sketch.strokes.size + strokes(shape, 0, 0f).size <= Sketch.MAX_STROKES

    /**
     * [sketch] with [shape] added on top, mirrored left to right when [headLeft] so that it faces
     * the way the person ticked; [sketch] itself when [shape] does not [fit][fits].
     */
    fun addTo(sketch: Sketch, shape: ReadyShape, argb: Int, width: Float, headLeft: Boolean = false): Sketch {
        if (!fits(sketch, shape)) return sketch
        return strokes(shape, argb, width).fold(sketch) { s, st ->
            val pts = if (headLeft) st.points.map { NormPoint(1f - it.x, it.y) } else st.points
            s.add(pts, st.argb, st.width)
        }
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 48): List<NormPoint> =
        List(n + 1) { i ->
            val a = (2.0 * PI * (i % n) / n).toFloat()
            NormPoint(cx + rx * cos(a), cy + ry * sin(a))
        }

    /** A closed polygon with its edges subdivided so the outline has enough points to be a loop. */
    private fun polygon(vertices: List<Pair<Float, Float>>, perEdge: Int = 6): List<NormPoint> {
        val out = ArrayList<NormPoint>()
        for (i in vertices.indices) {
            val (ax, ay) = vertices[i]
            val (bx, by) = vertices[(i + 1) % vertices.size]
            for (k in 0 until perEdge) {
                val t = k.toFloat() / perEdge
                out.add(NormPoint(ax + (bx - ax) * t, ay + (by - ay) * t))
            }
        }
        out.add(out.first())
        return out
    }
}
