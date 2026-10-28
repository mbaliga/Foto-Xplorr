package com.fotoxplorr.app.board

import com.fotoxplorr.app.lift.BrushStroke
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp
import kotlin.math.roundToInt

/**
 * What the editor holds that is more than a view setting: the board with its undo history, and which
 * layer is picked. Pure data; every change is a [BoardEditing] function that returns the next one, so
 * the screen is a thin shell and the rules are tested on the JVM.
 *
 * Not saved with the process: the history. A board that comes back after a kill ([BoardJson], the
 * store) starts with an empty one.
 */
data class BoardEditor(val history: BoardHistory, val selectedId: Long? = null) {
    val board: Board get() = history.current
    val selected: Layer? get() = selectedId?.let { board.layer(it) }
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    companion object {
        fun of(board: Board, selectedId: Long? = null): BoardEditor =
            BoardEditor(BoardHistory.of(board), selectedId?.takeIf { board.layer(it) != null })
    }
}

/**
 * Every button of the editor as a function from one [BoardEditor] to the next. The rules:
 *  - An edit that changes nothing (a locked layer, nothing picked, a limit already reached) returns
 *    the SAME editor, so a screen can tell and a test can say "nothing happened".
 *  - Taps on a stepper that repeat on one layer are one undo step ([BoardHistory] merge keys, keyed
 *    by the layer), and any other edit ends the run. A finger gesture passes its own key.
 *  - The picked layer follows the edit: an add picks the new layer, a duplicate picks the copy, a
 *    delete or an undo that removes the picked layer leaves nothing picked.
 */
object BoardEditing {

    /** How far each press of a button changes things. One place, so the words and the tests agree. */
    object Steps {
        const val NUDGE_UNITS = 10f
        const val SCALE_FACTOR = 1.1f
        const val TURN_COARSE = 15f
        const val TURN_FINE = 1f
        const val OPACITY = 0.05f
        const val FEATHER = 0.05f
        const val RADIUS = 0.05f
        const val TEXT_SIZE_FACTOR = 1.1f
        const val SUBJECT_MARK_RADIUS = 0.04f
        const val DEFAULT_ROUNDING = 0.15f
    }

    // ---- picking ----

    fun select(e: BoardEditor, id: Long?): BoardEditor {
        val target = id?.takeIf { e.board.layer(it) != null }
        return if (target == e.selectedId) e else e.copy(selectedId = target)
    }

    // ---- the history ----

    /** [board] as the next state (one undo step, or part of the step [key] began) with the pick kept if the layer is still there. */
    fun commit(e: BoardEditor, board: Board, key: String? = null): BoardEditor {
        val history = e.history.commit(board, key)
        if (history === e.history) return e
        return e.copy(history = history, selectedId = e.selectedId?.takeIf { board.layer(it) != null })
    }

    fun undo(e: BoardEditor): BoardEditor = moved(e, e.history.undo())

    fun redo(e: BoardEditor): BoardEditor = moved(e, e.history.redo())

    private fun moved(e: BoardEditor, history: BoardHistory): BoardEditor =
        if (history === e.history) e else e.copy(history = history, selectedId = e.selectedId?.takeIf { history.current.layer(it) != null })

    // ---- adding ----

    fun add(e: BoardEditor, added: Added): BoardEditor {
        val id = added.layerId ?: return e
        return commit(e, added.board).copy(selectedId = id)
    }

    /** The result of adding several photos at once: how many fitted before the board was full. */
    class AddedPhotos(val editor: BoardEditor, val added: Int)

    fun addPhotos(e: BoardEditor, refs: List<PhotoRef>): AddedPhotos {
        var board = e.board
        var last: Long? = null
        var count = 0
        for (ref in refs) {
            val a = BoardOps.addPhoto(board, ref)
            val id = a.layerId ?: break
            board = a.board
            last = id
            count++
        }
        if (count == 0) return AddedPhotos(e, 0)
        return AddedPhotos(commit(e, board).copy(selectedId = last), count)
    }

    fun addColour(e: BoardEditor, argb: Int): BoardEditor = add(e, BoardOps.addColour(e.board, argb))

    fun addShape(e: BoardEditor, kind: ShapeKind, argb: Int): BoardEditor = add(e, BoardOps.addShape(e.board, kind, argb))

    fun addText(e: BoardEditor, text: String, argb: Int): BoardEditor =
        if (text.isBlank()) e else add(e, BoardOps.addText(e.board, text, argb))

    // ---- the picked layer ----

    private inline fun onSelected(e: BoardEditor, key: String?, change: (Board, Layer) -> Board): BoardEditor {
        val layer = e.selected ?: return e
        return commit(e, change(e.board, layer), key?.let { "$it:${layer.id}" })
    }

    fun nudge(e: BoardEditor, dx: Float, dy: Float): BoardEditor =
        onSelected(e, "nudge") { b, l -> BoardOps.moveBy(b, l.id, dx, dy) }

    fun centre(e: BoardEditor): BoardEditor = onSelected(e, null) { b, l ->
        BoardOps.setTransform(b, l.id, l.transform.copy(cx = b.canvas.width / 2f, cy = b.canvas.height / 2f))
    }

    fun scaleBy(e: BoardEditor, factor: Float): BoardEditor = onSelected(e, "scale") { b, l ->
        BoardOps.setTransform(b, l.id, l.transform.copy(scale = l.transform.scale * factor))
    }

    fun turnBy(e: BoardEditor, degrees: Float): BoardEditor = onSelected(e, "turn") { b, l ->
        BoardOps.setTransform(b, l.id, l.transform.copy(rotationDeg = l.transform.rotationDeg + degrees))
    }

    /** A finger gesture's result; [key] is the gesture's own, so the whole gesture is one undo step. */
    fun setTransform(e: BoardEditor, id: Long, transform: Transform, key: String): BoardEditor =
        commit(e, BoardOps.setTransform(e.board, id, transform), key)

    fun opacityBy(e: BoardEditor, delta: Float): BoardEditor = onSelected(e, "opacity") { b, l ->
        BoardOps.setOpacity(b, l.id, roundedHundredths(l.opacity + delta))
    }

    fun setBlend(e: BoardEditor, blend: LayerBlend): BoardEditor = onSelected(e, null) { b, l -> BoardOps.setBlend(b, l.id, blend) }

    fun restack(e: BoardEditor, move: StackMove): BoardEditor = onSelected(e, null) { b, l -> BoardOps.restack(b, l.id, move) }

    /** Locking works on a layer that is locked (that is the way out), so it names the layer. */
    fun setLocked(e: BoardEditor, id: Long, locked: Boolean): BoardEditor = commit(e, BoardOps.setLocked(e.board, id, locked))

    fun duplicate(e: BoardEditor): BoardEditor {
        val layer = e.selected ?: return e
        return add(e, BoardOps.duplicate(e.board, layer.id))
    }

    fun delete(e: BoardEditor): BoardEditor {
        val layer = e.selected ?: return e
        val board = BoardOps.remove(e.board, layer.id)
        return if (board === e.board) e else commit(e, board).copy(selectedId = null)
    }

    // ---- masks ----

    /** Changes the shape and keeps the feather (a subject mask has none). */
    fun setMaskShape(e: BoardEditor, shape: MaskShape): BoardEditor = onSelected(e, null) { b, l ->
        BoardOps.setMask(b, l.id, LayerMask(shape, l.mask.feather))
    }

    fun setFeather(e: BoardEditor, feather: Float): BoardEditor = onSelected(e, "feather") { b, l ->
        BoardOps.setMask(b, l.id, l.mask.copy(feather = roundedHundredths(feather)))
    }

    fun featherBy(e: BoardEditor, delta: Float): BoardEditor =
        e.selected?.let { setFeather(e, it.mask.featherClamped + delta) } ?: e

    fun radiusBy(e: BoardEditor, delta: Float): BoardEditor {
        val shape = e.selected?.mask?.shape as? MaskShape.RoundedRect ?: return e
        return onSelected(e, "radius") { b, l ->
            BoardOps.setMask(b, l.id, l.mask.copy(shape = MaskShape.RoundedRect(roundedHundredths(shape.radius + delta))))
        }
    }

    /** Adds one mark to the picked layer's subject mask, making it a subject mask if it was not. */
    fun addSubjectMark(e: BoardEditor, mark: SelectionOp.Hint): BoardEditor {
        val layer = e.selected ?: return e
        if (!layer.isPhoto) return e
        val ops = (layer.mask.shape as? MaskShape.Subject)?.ops.orEmpty() + mark
        return setMaskShape(e, MaskShape.Subject(ops))
    }

    fun undoSubjectMark(e: BoardEditor): BoardEditor {
        val ops = (e.selected?.mask?.shape as? MaskShape.Subject)?.ops ?: return e
        if (ops.isEmpty()) return e
        return setMaskShape(e, MaskShape.Subject(ops.dropLast(1)))
    }

    // ---- text and colour of the picked layer ----

    fun setText(e: BoardEditor, text: String): BoardEditor = restyleText(e, "text") { it.copy(text = text.take(BoardLimits.MAX_TEXT_CHARS)) }

    fun setTextBold(e: BoardEditor, bold: Boolean): BoardEditor = restyleText(e, null) { it.copy(bold = bold) }

    fun setTextAlign(e: BoardEditor, align: TextAlign): BoardEditor = restyleText(e, null) { it.copy(align = align) }

    fun textSizeBy(e: BoardEditor, factor: Float): BoardEditor = restyleText(e, "textsize") {
        it.copy(sizeUnits = (it.sizeUnits * factor).coerceIn(MIN_TEXT_UNITS, MAX_TEXT_UNITS))
    }

    private fun restyleText(e: BoardEditor, key: String?, change: (LayerContent.Text) -> LayerContent.Text): BoardEditor =
        onSelected(e, key) { b, l ->
            val text = l.content as? LayerContent.Text ?: return@onSelected b
            BoardOps.setContent(b, l.id, change(text))
        }

    /** The colour of a colour block, a shape's fill or a text; a photo has none, so nothing happens. */
    fun setColour(e: BoardEditor, argb: Int): BoardEditor = onSelected(e, null) { b, l ->
        when (val c = l.content) {
            is LayerContent.Colour -> BoardOps.setContent(b, l.id, c.copy(argb = argb))
            is LayerContent.Shape -> BoardOps.setContent(b, l.id, c.copy(fillArgb = argb))
            is LayerContent.Text -> BoardOps.setContent(b, l.id, c.copy(argb = argb))
            is LayerContent.Photo -> b
        }
    }

    // ---- the board ----

    fun setBackground(e: BoardEditor, background: BoardBackground): BoardEditor =
        commit(e, BoardOps.setBackground(e.board, background))

    fun setCanvas(e: BoardEditor, canvas: CanvasSize): BoardEditor = commit(e, BoardOps.setCanvas(e.board, canvas))

    // ---- turning a touch into a mark ----

    /** A tap at canvas point ([x], [y]) as a point of [layer]'s box (0..1 across and down), or null outside the box. */
    fun boxPoint(layer: Layer, x: Float, y: Float): NormPoint? {
        val s = BoardGeometry.localShare(layer, x, y) ?: return null
        if (s.x < 0f || s.x > 1f || s.y < 0f || s.y > 1f) return null
        return NormPoint(s.x, s.y)
    }

    /**
     * A subject mark for a tap at canvas point ([x], [y]) on a photo layer: the point as a share of the
     * PHOTO (the lift tool's marks are in photo shares, and the layer may show only a crop of it), or
     * null outside the box.
     */
    fun subjectMark(layer: Layer, x: Float, y: Float, foreground: Boolean): SelectionOp.Hint? {
        if (!layer.isPhoto) return null
        val p = boxPoint(layer, x, y) ?: return null
        val px = layer.crop.left + p.x * layer.crop.width
        val py = layer.crop.top + p.y * layer.crop.height
        return SelectionOp.Hint(foreground, BrushStroke(listOf(NormPoint(px, py)), Steps.SUBJECT_MARK_RADIUS))
    }

    /** A subject mark as a point of the layer's box, for drawing it where the person tapped. */
    fun markInBox(layer: Layer, mark: SelectionOp): NormPoint? {
        val p = (mark as? SelectionOp.Hint)?.stroke?.points?.firstOrNull() ?: return null
        return NormPoint((p.x - layer.crop.left) / layer.crop.width, (p.y - layer.crop.top) / layer.crop.height)
    }

    private const val MIN_TEXT_UNITS = 8f
    private const val MAX_TEXT_UNITS = 600f

    private fun roundedHundredths(v: Float): Float = (v * 100f).roundToInt() / 100f
}

/** The mask choices the picker offers; a feather is separate and goes with any of them but [SUBJECT]. */
enum class MaskKind {
    NONE, ROUNDED, OVAL, SHAPE, SUBJECT;

    companion object {
        fun of(mask: LayerMask): MaskKind = when (mask.shape) {
            MaskShape.Full -> NONE
            is MaskShape.RoundedRect -> ROUNDED
            MaskShape.Ellipse -> OVAL
            is MaskShape.Polygon -> SHAPE
            is MaskShape.Subject -> SUBJECT
        }
    }
}

/** The corner points of a few ready-made mask outlines: the way to a shape without tapping points. Shares of the box. */
object MaskPresets {
    enum class Preset { DIAMOND, TRIANGLE, HEXAGON, STAR }

    fun points(preset: Preset): List<NormPoint> = when (preset) {
        Preset.DIAMOND -> listOf(NormPoint(0.5f, 0f), NormPoint(1f, 0.5f), NormPoint(0.5f, 1f), NormPoint(0f, 0.5f))
        Preset.TRIANGLE -> listOf(NormPoint(0.5f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f))
        Preset.HEXAGON -> listOf(
            NormPoint(0.25f, 0.03f), NormPoint(0.75f, 0.03f), NormPoint(1f, 0.5f),
            NormPoint(0.75f, 0.97f), NormPoint(0.25f, 0.97f), NormPoint(0f, 0.5f),
        )
        Preset.STAR -> star()
    }

    /** A five-point star inscribed in the box, drawn from the top; the outline does not cross itself. */
    private fun star(): List<NormPoint> = List(10) { i ->
        val radius = if (i % 2 == 0) 0.5f else 0.2f
        val angle = Math.toRadians(-90.0 + i * 36.0)
        NormPoint(0.5f + radius * Math.cos(angle).toFloat(), 0.52f + radius * Math.sin(angle).toFloat())
    }
}
