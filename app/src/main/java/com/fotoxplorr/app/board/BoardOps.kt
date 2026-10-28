package com.fotoxplorr.app.board

import com.fotoxplorr.app.editor.CropRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A board after an edit that adds a layer; [layerId] is null when the board is full. */
class Added(val board: Board, val layerId: Long?)

/** Which way a layer moves in the stack. */
enum class StackMove { FORWARD, BACKWARD, TO_FRONT, TO_BACK }

/**
 * Every edit the editor makes, as a pure function from a board to the next one. Immutable boards
 * make [BoardHistory] a list of references. Rules the tests pin:
 *
 *  - An edit that changes nothing returns the SAME board instance, so a history can tell.
 *  - A locked layer takes no edit except being unlocked ([setLocked]): not moved, not restyled, not
 *    reordered and not removed. Adding and duplicating are never refused by a lock.
 *  - Numbers are held to [BoardLimits] and to 0..1 for opacity, so no edit can leave a layer that
 *    the codec would later refuse.
 */
object BoardOps {

    /** A photo fits inside this share of the canvas when first added. */
    private const val FIRST_FIT = 0.6f
    private const val STEP_OFFSET = 0.03f
    private const val DUPLICATE_OFFSET = 24f

    fun addPhoto(board: Board, ref: PhotoRef): Added {
        val aspect = if (ref.widthPx > 0 && ref.heightPx > 0) ref.widthPx.toFloat() / ref.heightPx else 1f
        val (w, h) = fitBox(board.canvas, aspect)
        return add(board, LayerContent.Photo(ref), w, h)
    }

    fun addColour(board: Board, argb: Int): Added {
        val s = min(board.canvas.width, board.canvas.height) * 0.4f
        return add(board, LayerContent.Colour(argb), s, s)
    }

    fun addShape(board: Board, kind: ShapeKind, fillArgb: Int): Added {
        val s = min(board.canvas.width, board.canvas.height) * 0.4f
        return add(board, LayerContent.Shape(kind, fillArgb), s, s)
    }

    fun addText(board: Board, text: String, argb: Int = 0xFF000000.toInt()): Added {
        val content = LayerContent.Text(text.take(BoardLimits.MAX_TEXT_CHARS), argb)
        val w = board.canvas.width * 0.7f
        return add(board, content, w, TextBox.height(content, w))
    }

    private fun add(board: Board, content: LayerContent, w: Float, h: Float): Added {
        if (board.layers.size >= BoardLimits.MAX_LAYERS) return Added(board, null)
        // Each new layer sits a little down and right of the one before, so adding three photos
        // does not stack them exactly (a stack hides that there are three).
        val n = board.layers.size % 8
        val offset = n * STEP_OFFSET * min(board.canvas.width, board.canvas.height)
        val layer = Layer(
            id = board.nextLayerId,
            content = content,
            width = w,
            height = h,
            transform = Transform(board.canvas.width / 2f + offset, board.canvas.height / 2f + offset).clamped(board.canvas),
        )
        return Added(board.copy(layers = board.layers + layer, nextLayerId = board.nextLayerId + 1), layer.id)
    }

    /** The largest box of [aspect] (width over height) inside the first-add share of the canvas. */
    fun fitBox(canvas: CanvasSize, aspect: Float): Pair<Float, Float> {
        val a = if (aspect.isNaN() || aspect <= 0f) 1f else aspect.coerceIn(0.05f, 20f)
        val maxW = canvas.width * FIRST_FIT
        val maxH = canvas.height * FIRST_FIT
        var w = maxW
        var h = w / a
        if (h > maxH) {
            h = maxH
            w = h * a
        }
        return w.coerceAtLeast(BoardLimits.MIN_BOX) to h.coerceAtLeast(BoardLimits.MIN_BOX)
    }

    fun remove(board: Board, id: Long): Board {
        val layer = board.layer(id) ?: return board
        if (layer.locked) return board
        return board.copy(layers = board.layers.filter { it.id != id })
    }

    /** A copy just above the original, moved a little so it can be seen; never locked. */
    fun duplicate(board: Board, id: Long): Added {
        val layer = board.layer(id) ?: return Added(board, null)
        if (board.layers.size >= BoardLimits.MAX_LAYERS) return Added(board, null)
        val copy = layer.copy(
            id = board.nextLayerId,
            locked = false,
            transform = layer.transform.copy(
                cx = layer.transform.cx + DUPLICATE_OFFSET,
                cy = layer.transform.cy + DUPLICATE_OFFSET,
            ).clamped(board.canvas),
        )
        val at = board.indexOf(id) + 1
        val layers = board.layers.toMutableList().also { it.add(at, copy) }
        return Added(board.copy(layers = layers, nextLayerId = board.nextLayerId + 1), copy.id)
    }

    fun setTransform(board: Board, id: Long, transform: Transform): Board =
        edit(board, id) { it.copy(transform = transform.clamped(board.canvas)) }

    fun moveBy(board: Board, id: Long, dx: Float, dy: Float): Board =
        edit(board, id) { it.copy(transform = BoardGeometry.movedBy(it.transform, dx, dy).clamped(board.canvas)) }

    fun setOpacity(board: Board, id: Long, opacity: Float): Board =
        edit(board, id) { it.copy(opacity = opacity.clampUnit()) }

    fun setBlend(board: Board, id: Long, blend: LayerBlend): Board = edit(board, id) { it.copy(blend = blend) }

    fun setMask(board: Board, id: Long, mask: LayerMask): Board =
        edit(board, id) { it.copy(mask = sanitise(mask)) }

    /**
     * Replaces the content of a colour, text or shape layer, or swaps a photo for another. The box
     * stays, except a text layer's height which follows its words.
     */
    fun setContent(board: Board, id: Long, content: LayerContent): Board = edit(board, id) { layer ->
        val fixed = if (content is LayerContent.Text) content.copy(text = content.text.take(BoardLimits.MAX_TEXT_CHARS)) else content
        val height = if (fixed is LayerContent.Text) TextBox.height(fixed, layer.width) else layer.height
        layer.copy(content = fixed, height = height)
    }

    /** Sets the box of a layer (units at scale 1), held to the limits. */
    fun setBox(board: Board, id: Long, width: Float, height: Float): Board = edit(board, id) {
        it.copy(width = boxSide(width), height = boxSide(height))
    }

    /**
     * Crops a photo layer to [crop] (shares of the photo). The box keeps its width and takes the
     * crop's shape, so the picture is never stretched; the layer's centre stays where it was.
     */
    fun setCrop(board: Board, id: Long, crop: CropRect): Board = edit(board, id) { layer ->
        val ref = (layer.content as? LayerContent.Photo)?.ref ?: return@edit layer
        val photoAspect = if (ref.widthPx > 0 && ref.heightPx > 0) ref.widthPx.toFloat() / ref.heightPx else layer.width / layer.height
        val aspect = photoAspect * crop.width / crop.height
        layer.copy(crop = crop, height = boxSide(layer.width / aspect.coerceIn(0.05f, 20f)))
    }

    fun setLocked(board: Board, id: Long, locked: Boolean): Board {
        val layer = board.layer(id) ?: return board
        if (layer.locked == locked) return board
        return replace(board, layer.copy(locked = locked))
    }

    fun setBackground(board: Board, background: BoardBackground): Board =
        if (board.background == background) board else board.copy(background = background)

    /**
     * A new canvas shape. Layers keep their units, so a layer stays the same size and keeps its
     * distance from the top left; the person moves what no longer fits.
     */
    fun setCanvas(board: Board, canvas: CanvasSize): Board {
        val c = canvas.clamped()
        return if (c == board.canvas) board else board.copy(canvas = c)
    }

    fun restack(board: Board, id: Long, move: StackMove): Board {
        val from = board.indexOf(id)
        if (from < 0 || board.layers[from].locked) return board
        val last = board.layers.lastIndex
        val to = when (move) {
            StackMove.FORWARD -> min(last, from + 1)
            StackMove.BACKWARD -> max(0, from - 1)
            StackMove.TO_FRONT -> last
            StackMove.TO_BACK -> 0
        }
        if (to == from) return board
        val layers = board.layers.toMutableList()
        layers.add(to, layers.removeAt(from))
        return board.copy(layers = layers)
    }

    private fun edit(board: Board, id: Long, change: (Layer) -> Layer): Board {
        val layer = board.layer(id) ?: return board
        if (layer.locked) return board
        val changed = change(layer)
        return if (changed == layer) board else replace(board, changed)
    }

    private fun replace(board: Board, layer: Layer): Board =
        board.copy(layers = board.layers.map { if (it.id == layer.id) layer else it })

    private fun boxSide(v: Float): Float = finite(v, BoardLimits.MIN_BOX).coerceIn(BoardLimits.MIN_BOX, BoardLimits.MAX_BOX)

    /** The mask with its numbers inside what the geometry understands. */
    internal fun sanitise(mask: LayerMask): LayerMask {
        val shape = when (val s = mask.shape) {
            is MaskShape.RoundedRect -> MaskShape.RoundedRect(finite(s.radius, 0f).coerceIn(0f, 0.5f))
            is MaskShape.Polygon -> MaskShape.Polygon(
                s.points.take(BoardLimits.MAX_POLYGON_POINTS)
                    .map { com.fotoxplorr.app.lift.NormPoint(it.x.clampUnit(), it.y.clampUnit()) },
            )
            is MaskShape.Subject -> MaskShape.Subject(s.ops.takeLast(BoardLimits.MAX_SELECTION_OPS))
            else -> s
        }
        // The subject's edge is the segmenter's own, so a feather does not apply to it.
        return LayerMask(shape, if (shape is MaskShape.Subject) 0f else mask.featherClamped)
    }
}

/**
 * How tall a text layer's box should be. This is an estimate from the length of the words, not a
 * measurement: the real height depends on the font the phone has, and only the renderer can lay the
 * text out. A box too short clips the last line; the editor can offer a taller one by hand.
 */
object TextBox {
    private const val AVERAGE_CHAR_WIDTH = 0.55f
    private const val LINE_HEIGHT = 1.2f

    fun height(content: LayerContent.Text, width: Float): Float {
        val perLine = max(1, (width / (content.sizeUnits * AVERAGE_CHAR_WIDTH)).toInt())
        val lines = content.text.split('\n').sumOf { line -> max(1, ceil(line.length.toFloat() / perLine).toInt()) }
        return (lines * content.sizeUnits * LINE_HEIGHT).coerceIn(BoardLimits.MIN_BOX, BoardLimits.MAX_BOX)
    }
}
