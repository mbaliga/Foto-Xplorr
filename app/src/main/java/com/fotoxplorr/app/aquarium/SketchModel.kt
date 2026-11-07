package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.lift.NormPoint

/**
 * What the person has drawn so far, as pure state: strokes, colours, brush sizes and undo/redo.
 * No Android types, so every rule here runs in a plain JVM test.
 *
 * Coordinates are BOARD-normalised, the same [NormPoint] the lift selection brush uses: x and y
 * both run 0..1 over a board whose width/height is [Sketch.BOARD_ASPECT], and a brush width is a
 * fraction of the board WIDTH. A stroke therefore means the same thing at any screen size, and the
 * rasteriser picks its own pixel size.
 */
data class DrawStroke(
    val points: List<NormPoint>,
    val argb: Int,
    /** Brush width, fraction of the board width. */
    val width: Float,
    /**
     * True when the outline is nearly closed and the person has auto-fill on: the rasteriser then
     * closes the loop and paints its inside in [argb]. Decided once, when the stroke is added.
     */
    val filled: Boolean,
)

/** Three named brush widths, each a fraction of the board width. */
enum class BrushSize(val label: String, val width: Float) {
    THIN(AquariumCopy.BRUSH_THIN, 0.012f),
    MEDIUM(AquariumCopy.BRUSH_MEDIUM, 0.025f),
    THICK(AquariumCopy.BRUSH_THICK, 0.05f),
}

/** A colour with the name TalkBack reads, so the swatch row never relies on colour alone. */
data class Swatch(val name: String, val argb: Int)

object SketchPalette {
    val swatches: List<Swatch> = listOf(
        Swatch(AquariumCopy.COLOR_RED, 0xFFE53935.toInt()),
        Swatch(AquariumCopy.COLOR_ORANGE, 0xFFFB8C00.toInt()),
        Swatch(AquariumCopy.COLOR_YELLOW, 0xFFFDD835.toInt()),
        Swatch(AquariumCopy.COLOR_GREEN, 0xFF43A047.toInt()),
        Swatch(AquariumCopy.COLOR_BLUE, 0xFF1E88E5.toInt()),
        Swatch(AquariumCopy.COLOR_PURPLE, 0xFF8E24AA.toInt()),
        Swatch(AquariumCopy.COLOR_PINK, 0xFFEC407A.toInt()),
        Swatch(AquariumCopy.COLOR_BLACK, 0xFF212121.toInt()),
    )
}

/**
 * Undo/redo over [DrawStroke]s. Immutable: every operation returns the next sketch, so it sits in a
 * Compose `mutableStateOf` and "did it change" is a reference comparison (the same shape as
 * `SelectionSession`). A new stroke clears the redo stack; [clear] is the "start over" button and is
 * not itself undoable.
 *
 * Bounded on purpose: at most [MAX_STROKES] strokes of at most [MAX_POINTS] points, so a long
 * scribble cannot grow memory without limit. Anything beyond is refused (strokes) or thinned
 * (points); the UI reads [isFull] to say so.
 */
class Sketch private constructor(
    val strokes: List<DrawStroke>,
    private val redoStack: List<DrawStroke>,
) {
    constructor() : this(emptyList(), emptyList())

    val isEmpty: Boolean get() = strokes.isEmpty()
    val canUndo: Boolean get() = strokes.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isFull: Boolean get() = strokes.size >= MAX_STROKES

    /**
     * Adds a stroke, or returns this sketch when it is full or [points] holds nothing usable.
     * Points are clamped onto the board, non-finite ones dropped, runs of identical points collapsed
     * and the rest thinned to [MAX_POINTS]; [width] is clamped. One point is a valid dot.
     */
    fun add(points: List<NormPoint>, argb: Int, width: Float, autoFill: Boolean = true): Sketch {
        if (isFull) return this
        val clean = cleanPoints(points)
        if (clean.isEmpty()) return this
        val w = if (width.isNaN()) BrushSize.THIN.width else width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val filled = autoFill && OutlineFill.isNearlyClosed(clean, w)
        return Sketch(strokes + DrawStroke(clean, argb, w, filled), emptyList())
    }

    fun undo(): Sketch =
        if (strokes.isEmpty()) this else Sketch(strokes.dropLast(1), redoStack + strokes.last())

    fun redo(): Sketch =
        if (redoStack.isEmpty()) this else Sketch(strokes + redoStack.last(), redoStack.dropLast(1))

    /** Back to nothing drawn. Not undoable. */
    fun clear(): Sketch = if (strokes.isEmpty() && redoStack.isEmpty()) this else Sketch()

    companion object {
        const val MAX_STROKES = 60
        const val MAX_POINTS = 4000

        /** The board is wider than tall; the drawing screen and the rasteriser share this one number. */
        const val BOARD_ASPECT = 4f / 3f

        const val MIN_WIDTH = 0.004f
        const val MAX_WIDTH = 0.2f

        private fun cleanPoints(points: List<NormPoint>): List<NormPoint> {
            val out = ArrayList<NormPoint>(minOf(points.size, MAX_POINTS))
            for (p in points) {
                if (!p.x.isFinite() || !p.y.isFinite()) continue
                val c = NormPoint(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
                if (out.isNotEmpty() && out.last() == c) continue
                out.add(c)
            }
            if (out.size <= MAX_POINTS) return out
            // Even thinning that keeps the first and the last point exactly.
            val last = out.size - 1
            return List(MAX_POINTS) { out[(it.toLong() * last / (MAX_POINTS - 1)).toInt()] }
        }
    }
}
