package com.fotoxplorr.app.board

import com.fotoxplorr.app.backup.MediaIdentity
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp
import kotlin.math.max
import kotlin.math.min

/*
 * The mood board as plain data. Nothing in this file imports Android: a board is built, edited,
 * undone, saved and hit-tested on the JVM, and only BoardRenderer draws it.
 *
 * Space. A board has a canvas of whole "units" (1080 by 1350 for the 4:5 preset). Every position and
 * size in a layer is in those units, so a board means the same thing at a 270 px preview and at the
 * export size: the renderer picks pixels per unit. The origin is the top left, y grows down, an angle
 * grows clockwise (the same sign as Canvas.rotate).
 */

/** What a board is made of is bounded, so a damaged file cannot ask for the moon. */
object BoardLimits {
    const val MIN_CANVAS_EDGE = 64
    const val MAX_CANVAS_EDGE = 4096
    const val MAX_LAYERS = 100
    const val MAX_TEXT_CHARS = 500
    const val MAX_POLYGON_POINTS = 64
    const val MAX_SELECTION_OPS = 256
    const val MAX_STROKE_POINTS = 2048
    const val MIN_SCALE = 0.02f
    const val MAX_SCALE = 40f

    /** The smallest a layer's box may be, in units, so a layer can always be found and grabbed again. */
    const val MIN_BOX = 4f

    /** The largest a box side may be, in units (several canvases wide; a zoomed-in crop needs room). */
    const val MAX_BOX = 16384f

    /** A layer's centre may sit this many canvas-sizes outside the canvas before it is pulled back. */
    const val OFF_CANVAS_REACH = 4f

    /** The most a feather may take: half the short side meets in the middle. */
    const val MAX_FEATHER = 0.5f
}

/** The canvas size in units. */
data class CanvasSize(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height
    val longEdge: Int get() = max(width, height)

    /** The same shape with both sides forced into [BoardLimits]. */
    fun clamped(): CanvasSize = CanvasSize(
        width.coerceIn(BoardLimits.MIN_CANVAS_EDGE, BoardLimits.MAX_CANVAS_EDGE),
        height.coerceIn(BoardLimits.MIN_CANVAS_EDGE, BoardLimits.MAX_CANVAS_EDGE),
    )
}

/** The shapes the canvas picker offers. A custom size is any [CanvasSize]. The words are in [BoardCopy]. */
enum class CanvasPreset(val size: CanvasSize) {
    SQUARE(CanvasSize(1080, 1080)),
    PORTRAIT_4_5(CanvasSize(1080, 1350)),
    PORTRAIT_2_3(CanvasSize(1080, 1620)),
    STORY_9_16(CanvasSize(1080, 1920)),
    LANDSCAPE_3_2(CanvasSize(1620, 1080)),
    LANDSCAPE_16_9(CanvasSize(1920, 1080)),
}

sealed interface BoardBackground {
    /** [argb] may carry alpha; a half-see-through colour exports half see-through. */
    data class Colour(val argb: Int) : BoardBackground

    /** Nothing behind the layers: the export has real alpha (a PNG, not a JPEG). */
    data object Transparent : BoardBackground
}

/** How a layer meets what is already drawn. Multiply darkens, screen lightens, overlay raises contrast. */
enum class LayerBlend(val code: String) {
    NORMAL("normal"),
    MULTIPLY("multiply"),
    SCREEN("screen"),
    OVERLAY("overlay");

    companion object {
        fun of(code: String?): LayerBlend = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * Which photo a layer shows, said the way the backup says it for pins and recipes: the MediaStore id
 * (only a row number on this phone) plus the [identity] (name, size, date taken, folder) that lets
 * the same photo be found again after a restore or a move ([PhotoResolver]). [widthPx] and
 * [heightPx] are the upright size at the time the photo was added; they let the board be sized and
 * the decode be planned without opening the file, and are advice only: the renderer draws the crop
 * as a share of whatever size it actually gets back.
 */
data class PhotoRef(
    val mediaId: Long,
    val identity: MediaIdentity? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

enum class ShapeKind(val code: String) {
    RECTANGLE("rect"),
    ELLIPSE("ellipse"),
    TRIANGLE("triangle");

    companion object {
        fun of(code: String?): ShapeKind = entries.firstOrNull { it.code == code } ?: RECTANGLE
    }
}

enum class TextAlign(val code: String) {
    START("start"),
    CENTRE("centre"),
    END("end");

    companion object {
        fun of(code: String?): TextAlign = entries.firstOrNull { it.code == code } ?: START
    }
}

/** What a layer is. The box and everything else a layer has lives on [Layer]. */
sealed interface LayerContent {
    /** A photo of the library. A photo that is gone is not an error: it draws as a placeholder. */
    data class Photo(val ref: PhotoRef) : LayerContent

    /** A flat colour filling the box ([argb] may carry alpha). */
    data class Colour(val argb: Int) : LayerContent

    /** Words in the box, wrapped to its width. [sizeUnits] is the font size in canvas units at scale 1. */
    data class Text(
        val text: String,
        val argb: Int = 0xFF000000.toInt(),
        val sizeUnits: Float = 64f,
        val bold: Boolean = false,
        val align: TextAlign = TextAlign.START,
    ) : LayerContent

    /** A shape filling the box, with an optional outline ([strokeUnits] 0 means none). */
    data class Shape(
        val kind: ShapeKind,
        val fillArgb: Int = 0xFF000000.toInt(),
        val strokeArgb: Int = 0xFF000000.toInt(),
        val strokeUnits: Float = 0f,
    ) : LayerContent
}

/** Where a layer's centre is, how much it is enlarged ([scale], 1 is the box's own size), and its turn in degrees. */
data class Transform(
    val cx: Float = 0f,
    val cy: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
) {
    /** Finite, with scale in range and the turn in (-180, 180]. */
    fun clamped(canvas: CanvasSize): Transform {
        val reachX = canvas.width * BoardLimits.OFF_CANVAS_REACH
        val reachY = canvas.height * BoardLimits.OFF_CANVAS_REACH
        return Transform(
            cx = finite(cx, canvas.width / 2f).coerceIn(-reachX, canvas.width + reachX),
            cy = finite(cy, canvas.height / 2f).coerceIn(-reachY, canvas.height + reachY),
            scale = finite(scale, 1f).coerceIn(BoardLimits.MIN_SCALE, BoardLimits.MAX_SCALE),
            rotationDeg = normaliseDegrees(finite(rotationDeg, 0f)),
        )
    }
}

internal fun finite(value: Float, fallback: Float): Float = if (value.isNaN() || value.isInfinite()) fallback else value

/** [deg] folded into (-180, 180]. */
fun normaliseDegrees(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** What part of a layer shows. See [MaskShape] for the shapes; [feather] softens whichever edge it has. */
data class LayerMask(val shape: MaskShape = MaskShape.Full, val feather: Float = 0f) {
    /** True when the mask changes nothing: the whole box shows, with a hard edge. */
    val isNone: Boolean get() = shape == MaskShape.Full && feather <= 0f

    /** The feather held to what the geometry understands (0 to half the short side). */
    val featherClamped: Float get() = finite(feather, 0f).coerceIn(0f, BoardLimits.MAX_FEATHER)

    companion object {
        val NONE = LayerMask()
    }
}

/**
 * The shapes a mask can have. "No mask" is [Full] with no feather and "feathered edge" is [Full] with
 * a feather: the soft frame is the same idea as the other shapes, so a feather works on all of them.
 */
sealed interface MaskShape {
    /** The whole box. */
    data object Full : MaskShape

    /** A rounded rectangle; [radius] is a share of the short side, 0 (square corners) to 0.5 (a pill or circle). */
    data class RoundedRect(val radius: Float) : MaskShape

    /** The ellipse that touches the middle of the four sides. */
    data object Ellipse : MaskShape

    /**
     * A closed outline. Points are shares of the box (0..1 across and down). Inside is decided
     * even-odd, so an outline that crosses itself leaves a hole. Fewer than three points shows the
     * whole box rather than nothing.
     */
    data class Polygon(val points: List<NormPoint>) : MaskShape

    /**
     * The subject of a photo, found again from the marks the person made in the Lift tool. The lift
     * package keeps no mask: it keeps the list of marks ([SelectionOp], in shares of the photo) and
     * runs its segmenter over them. So the board stores those marks and the renderer re-runs the
     * segmenter on the layer's own pixels. The result is a fresh solve at a working size of about
     * 512 px, deterministic for the same pixels, and not byte-identical to the mask the Lift screen
     * showed (the working pixels come from a different decode). It applies to photo layers only; on
     * anything else, and with no foreground mark, it shows the whole box. Its edge is the segmenter's own, so a
     * feather does not apply to it.
     */
    data class Subject(val ops: List<SelectionOp>) : MaskShape {
        /** True once there is a foreground mark to solve from; before that there is nothing to cut. */
        val isSolvable: Boolean get() = ops.any { it is SelectionOp.Hint && it.foreground }
    }
}

/**
 * One thing on the board.
 *
 * [width] and [height] are the box in canvas units at scale 1; [transform] places it. The box is the
 * shape of what shows: for a photo it follows the crop (see [BoardOps.setCrop]), so the picture is
 * never stretched. [crop] is the part of the photo that fills the box, in shares of the photo.
 * Stacking is the order of [Board.layers], first is at the back, so there is no z field that could
 * disagree with the list.
 */
data class Layer(
    val id: Long,
    val content: LayerContent,
    val width: Float,
    val height: Float,
    val transform: Transform = Transform(),
    val opacity: Float = 1f,
    val blend: LayerBlend = LayerBlend.NORMAL,
    val locked: Boolean = false,
    val mask: LayerMask = LayerMask.NONE,
    val crop: CropRect = CropRect.FULL,
) {
    val isPhoto: Boolean get() = content is LayerContent.Photo

    /** The size on the canvas, units. */
    val drawnWidth: Float get() = width * transform.scale
    val drawnHeight: Float get() = height * transform.scale
}

/** A whole board. [nextLayerId] only grows, so an id is never given to two layers, even after a delete and an undo. */
data class Board(
    val canvas: CanvasSize = CanvasPreset.SQUARE.size,
    val background: BoardBackground = BoardBackground.Colour(0xFFFFFFFF.toInt()),
    val layers: List<Layer> = emptyList(),
    val nextLayerId: Long = 1L,
) {
    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }
    fun indexOf(id: Long): Int = layers.indexOfFirst { it.id == id }

    /** The photos the board shows, once each, in stacking order. */
    val photoRefs: List<PhotoRef>
        get() = layers.mapNotNull { (it.content as? LayerContent.Photo)?.ref }.distinctBy { it.mediaId }

    /** True when the export needs an alpha channel: a see-through background. */
    val needsAlpha: Boolean
        get() = background is BoardBackground.Transparent ||
            (background is BoardBackground.Colour && (background.argb ushr 24) < 255)
}

internal fun Float.clampUnit(): Float = min(1f, max(0f, finite(this, 0f)))
