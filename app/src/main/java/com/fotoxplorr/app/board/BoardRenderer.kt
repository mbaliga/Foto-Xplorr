package com.fotoxplorr.app.board

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.ExportOptions
import com.fotoxplorr.app.editor.renderDownTheLadder
import com.fotoxplorr.app.lift.LiftRenderer
import com.fotoxplorr.app.lift.SelectionOps
import com.fotoxplorr.app.media.DecodeLimits
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a render came to. */
sealed interface BoardRender {
    /**
     * The finished picture. [edge] is its long side in pixels, [steppedDown] whether memory forced a
     * smaller size than first tried, and [missing] the layers whose photo could not be read and were
     * drawn as placeholders (always empty from an export, which refuses instead). The caller owns [bitmap].
     */
    class Done(val bitmap: Bitmap, val edge: Int, val steppedDown: Boolean, val missing: List<Long>) : BoardRender

    /** An export that was not started or was stopped because these layers' photos cannot be read now. */
    class Refused(val missing: List<Long>) : BoardRender

    /** Every size tried ran out of memory. */
    data object OutOfMemory : BoardRender
}

/** What to do with a photo that cannot be read. */
enum class MissingPhotos { PLACEHOLDER, REFUSE }

/**
 * Draws a [Board] into a [Bitmap]: the Android half, next to the pure model. Per layer, from the back:
 * the content (a photo cropped to its box, a colour, text, or a shape), the mask, then onto the
 * canvas with the layer's turn, opacity and blend. Everything that decides a number lives in the
 * pure files ([BoardRenderPlan], [MaskGeometry], [BoardGeometry]); this class moves pixels.
 *
 * Photos are decoded once each, as one layer at a time: a layer is decoded at the size it needs
 * ([BoardRenderPlan.decodeLimits]), drawn, and recycled. A photo used by several layers is decoded
 * once at the largest size any of them needs and kept until its last layer is drawn. Layers that are
 * off the canvas, fully transparent or smaller than half a pixel are not decoded at all.
 *
 * A layer with no mask and a photo or a colour is drawn straight to the canvas. Anything else (a
 * mask, text, a shape, a missing photo) is drawn into a bitmap of its own size first, so its opacity
 * applies to the layer as one thing and its mask is multiplied in before it meets the canvas.
 *
 * Work is CPU-heavy: call from a background dispatcher. It checks for cancellation between layers
 * and while solving a subject mask. Bitmaps it makes along the way are recycled on every path out,
 * including a thrown [OutOfMemoryError]. None of this has run on a device; the tests run it under
 * Robolectric's native graphics on tiny boards.
 */
class BoardRenderer(private val photos: BoardPhotoSource) {

    /** A small picture to look at while editing, long side [longEdge] px, placeholders for missing photos. */
    suspend fun preview(board: Board, longEdge: Int): BoardRender = render(board, BoardRenderPlan.sizeForEdge(board.canvas, longEdge), MissingPhotos.PLACEHOLDER)

    /**
     * The picture to save. Refused (nothing drawn) if any photo on the canvas cannot be read now;
     * otherwise drawn at the editor's ceiling for [options] and, if memory runs out, one rung down at
     * a time ([renderDownTheLadder]). The caller re-checks protection again before it publishes.
     * [onLayer] hears (layers drawn, layers to draw) after each layer, and starts over at 0 on a smaller rung.
     */
    suspend fun export(board: Board, options: ExportOptions = ExportOptions(), onLayer: (done: Int, total: Int) -> Unit = { _, _ -> }): BoardRender {
        val gone = missingNow(board)
        if (gone.isNotEmpty()) return BoardRender.Refused(gone)
        val outcome = renderDownTheLadder(BoardRenderPlan.exportLadder(board, options)) { edge ->
            renderOnce(board, BoardRenderPlan.sizeForEdge(board.canvas, edge), MissingPhotos.REFUSE, onLayer)
        } ?: return BoardRender.OutOfMemory
        val result = outcome.value
        return if (result is BoardRender.Done) BoardRender.Done(result.bitmap, result.edge, outcome.steppedDown, result.missing) else result
    }

    /** One attempt at exactly [size]. */
    suspend fun render(board: Board, size: RenderSize, policy: MissingPhotos): BoardRender =
        try {
            renderOnce(board, size, policy)
        } catch (_: OutOfMemoryError) {
            BoardRender.OutOfMemory
        }

    /**
     * Layers that would be drawn whose photo [photos] says it cannot read now. "Drawn" is the renderer's own
     * test ([isDrawn]) at one pixel to the unit, so a layer that is never drawn (see-through, off the canvas,
     * smaller than half a pixel) does not hold an export back. A layer that is only just big enough at a
     * larger size is caught by the export itself, which refuses when it cannot read a photo it draws.
     */
    fun missingNow(board: Board): List<Long> = board.layers.filter { layer ->
        val ref = (layer.content as? LayerContent.Photo)?.ref
        ref != null && isDrawn(layer, board.canvas, 1f) && !photos.isAvailable(ref)
    }.map { it.id }

    /** Whether [layer] puts anything on the canvas at [s] pixels to the unit; one that does not is neither decoded nor drawn. */
    private fun isDrawn(layer: Layer, canvas: CanvasSize, s: Float): Boolean =
        layer.opacity > 0f && layer.drawnWidth * s >= MIN_DRAWN_PX && layer.drawnHeight * s >= MIN_DRAWN_PX &&
            BoardGeometry.isOnCanvas(layer, canvas)

    private suspend fun renderOnce(
        board: Board,
        size: RenderSize,
        policy: MissingPhotos,
        onLayer: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BoardRender {
        val s = size.pxPerUnit
        val drawn = board.layers.filter { isDrawn(it, board.canvas, s) }

        // One decode per photo, at the most any layer using it needs.
        val uses = HashMap<Long, Int>()
        val limits = HashMap<Long, DecodeLimits>()
        for (layer in drawn) {
            val ref = (layer.content as? LayerContent.Photo)?.ref ?: continue
            uses.merge(ref.mediaId, 1, Int::plus)
            val need = BoardRenderPlan.decodeLimits(layer, s) ?: continue
            limits.merge(ref.mediaId, need) { a, b -> DecodeLimits(max(a.maxLongEdge, b.maxLongEdge), max(a.maxPixels, b.maxPixels)) }
        }

        // Decided now: the count falls as layers are drawn, and a photo shared at the start is shared throughout.
        val shared = uses.filterValues { it > 1 }.keys
        val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        var finished = false
        val kept = HashMap<Long, Bitmap>()
        val missing = ArrayList<Long>()
        try {
            when (val bg = board.background) {
                is BoardBackground.Colour -> out.eraseColor(bg.argb)
                BoardBackground.Transparent -> out.eraseColor(Color.TRANSPARENT)
            }
            val canvas = Canvas(out)
            val job = currentCoroutineContext()[Job]
            val shouldStop = { job?.isActive == false }

            onLayer(0, drawn.size)
            for ((index, layer) in drawn.withIndex()) {
                currentCoroutineContext().ensureActive()
                var bitmap: Bitmap? = null
                var own = false
                val ref = (layer.content as? LayerContent.Photo)?.ref
                if (ref != null) {
                    val isShared = ref.mediaId in shared
                    bitmap = if (isShared) kept[ref.mediaId] else null
                    if (bitmap == null) {
                        bitmap = photos.decode(ref, limits.getValue(ref.mediaId))
                        if (bitmap != null) {
                            if (isShared) kept[ref.mediaId] = bitmap else own = true
                        }
                    }
                    if (bitmap == null) {
                        missing += layer.id
                        if (policy == MissingPhotos.REFUSE) return BoardRender.Refused(missing)
                    }
                }
                try {
                    drawLayer(canvas, layer, bitmap, s, board.needsAlpha, shouldStop)
                } finally {
                    if (own) bitmap?.recycle()
                    if (ref != null) {
                        val left = (uses[ref.mediaId] ?: 1) - 1
                        uses[ref.mediaId] = left
                        if (left <= 0) kept.remove(ref.mediaId)?.recycle()
                    }
                }
                onLayer(index + 1, drawn.size)
            }
            finished = true
            return BoardRender.Done(out, max(out.width, out.height), false, missing)
        } finally {
            kept.values.forEach { it.recycle() }
            if (!finished) out.recycle()
        }
    }

    private fun drawLayer(canvas: Canvas, placed: Layer, photo: Bitmap?, s: Float, seeThrough: Boolean, shouldStop: () -> Boolean) {
        val layer = roomyText(placed, s)
        val t = layer.transform
        val pw = layer.drawnWidth * s
        val ph = layer.drawnHeight * s
        val content = layer.content
        val subject = (layer.mask.shape as? MaskShape.Subject)?.takeIf { content is LayerContent.Photo && photo != null && it.isSolvable }
        val geometric = layer.mask.shape !is MaskShape.Subject && !layer.mask.isNone
        val modulates = layer.blend == LayerBlend.MULTIPLY && !HAS_TRUE_MULTIPLY
        val grouped = subject != null || geometric || modulates || content is LayerContent.Text || content is LayerContent.Shape ||
            (content is LayerContent.Photo && photo == null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        applyBlend(paint, layer.blend)
        val alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(t.cx * s, t.cy * s)
        canvas.rotate(t.rotationDeg)
        val dst = RectF(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f)
        try {
            if (!grouped) {
                when (content) {
                    is LayerContent.Photo -> {
                        paint.alpha = alpha
                        drawRegion(canvas, photo!!, cropRect(shownCrop(layer.crop, photo, pw, ph), photo), dst, paint)
                    }
                    is LayerContent.Colour -> {
                        paint.color = content.argb
                        paint.alpha = (Color.alpha(content.argb) * layer.opacity).roundToInt().coerceIn(0, 255)
                        canvas.drawRect(dst, paint)
                    }
                    else -> Unit
                }
                return
            }
            val size = BoardRenderPlan.groupSize(pw, ph)
            val group = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val g = Canvas(group)
                val perUnit = size.width / layer.width
                drawContent(g, layer, photo, size, perUnit)
                if (subject != null && photo != null) {
                    subjectPlane(photo, subject, shouldStop)?.let { applyPlane(group, it, subjectRect(shownCrop(layer.crop, photo, size.width.toFloat(), size.height.toFloat()), size)) }
                } else if (geometric) {
                    applyPlane(group, MaskGeometry.plane(layer.mask, layer.width, layer.height, size.width, size.height), RectF(0f, 0f, size.width.toFloat(), size.height.toFloat()))
                }
                // Where the canvas is see-through, a product with nothing is nothing: the layer is also kept as it is
                // (a copy, only on these platforms and only for such a board) to lie behind what the multiply left.
                val plain = if (modulates && seeThrough) group.copy(Bitmap.Config.ARGB_8888, false) else null
                if (modulates) {
                    backWithWhite(group, layer.opacity)
                    paint.alpha = 255
                } else {
                    paint.alpha = alpha
                }
                val whole = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
                drawRegion(canvas, group, whole, dst, paint)
                if (plain != null) {
                    try {
                        val behind = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        behind.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
                        behind.alpha = alpha
                        drawRegion(canvas, plain, whole, dst, behind)
                    } finally {
                        plain.recycle()
                    }
                }
            } finally {
                group.recycle()
            }
        } finally {
            canvas.restore()
        }
    }

    private fun drawContent(g: Canvas, layer: Layer, photo: Bitmap?, size: GroupSize, perUnit: Float) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (val c = layer.content) {
            is LayerContent.Photo -> {
                if (photo != null) drawRegion(g, photo, cropRect(shownCrop(layer.crop, photo, w, h), photo), RectF(0f, 0f, w, h), paint) else drawPlaceholder(g, w, h)
            }
            is LayerContent.Colour -> {
                paint.color = c.argb
                g.drawRect(0f, 0f, w, h, paint)
            }
            is LayerContent.Shape -> drawShape(g, c, w, h, perUnit, paint)
            is LayerContent.Text -> drawText(g, c, w, h, perUnit)
        }
    }

    private fun drawShape(g: Canvas, c: LayerContent.Shape, w: Float, h: Float, perUnit: Float, paint: Paint) {
        val stroke = c.strokeUnits * perUnit
        val inset = stroke / 2f
        val box = RectF(inset, inset, w - inset, h - inset)
        if (box.width() <= 0f || box.height() <= 0f) return
        fun trace(p: Paint) = when (c.kind) {
            ShapeKind.RECTANGLE -> g.drawRect(box, p)
            ShapeKind.ELLIPSE -> g.drawOval(box, p)
            ShapeKind.TRIANGLE -> g.drawPath(
                Path().apply {
                    moveTo(box.centerX(), box.top)
                    lineTo(box.right, box.bottom)
                    lineTo(box.left, box.bottom)
                    close()
                },
                p,
            )
        }
        paint.style = Paint.Style.FILL
        paint.color = c.fillArgb
        trace(paint)
        if (stroke > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.strokeJoin = Paint.Join.MITER
            paint.color = c.strokeArgb
            trace(paint)
        }
    }

    private fun textLayout(c: LayerContent.Text, widthPx: Int, perUnit: Float): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.argb
            textSize = max(1f, c.sizeUnits * perUnit)
            typeface = if (c.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val align = when (c.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTRE -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(c.text, 0, c.text.length, tp, max(1, widthPx)).setAlignment(align).build()
    }

    /**
     * A text layer's box height is only an estimate ([TextBox]); the real lines depend on the phone's font.
     * Here the words are laid out for real, and a box too short for them is made as tall as they need (about
     * its centre), so no line is cut off in the picture or the saved file. A box that is tall enough is left alone.
     */
    private fun roomyText(layer: Layer, s: Float): Layer {
        val c = layer.content as? LayerContent.Text ?: return layer
        val perUnit = layer.transform.scale * s
        if (c.text.isEmpty() || perUnit < MIN_TEXT_SCALE) return layer
        val needed = textLayout(c, (layer.width * perUnit).toInt(), perUnit).height / perUnit
        return if (needed > layer.height) layer.copy(height = needed.coerceAtMost(BoardLimits.MAX_BOX)) else layer
    }

    private fun drawText(g: Canvas, c: LayerContent.Text, w: Float, h: Float, perUnit: Float) {
        if (c.text.isEmpty()) return
        val layout = textLayout(c, w.toInt(), perUnit)
        g.save()
        g.clipRect(0f, 0f, w, h)
        g.translate(0f, max(0f, (h - layout.height) / 2f))
        layout.draw(g)
        g.restore()
    }

    /** Grey with a cross: unmistakably not a photo, and it keeps the box so the board still reads. */
    private fun drawPlaceholder(g: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = PLACEHOLDER_FILL
        g.drawRect(0f, 0f, w, h, p)
        p.color = PLACEHOLDER_LINE
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, min(w, h) / 40f)
        g.drawLine(0f, 0f, w, h, p)
        g.drawLine(w, 0f, 0f, h, p)
        g.drawRect(p.strokeWidth / 2f, p.strokeWidth / 2f, w - p.strokeWidth / 2f, h - p.strokeWidth / 2f, p)
    }

    /**
     * The lift segmenter's answer for this photo: the layer's marks replayed over its pixels at a
     * working size of about [SUBJECT_WORKING_EDGE] px. The result is a plane over the WHOLE photo.
     */
    private fun subjectPlane(photo: Bitmap, subject: MaskShape.Subject, shouldStop: () -> Boolean): MaskPlane? {
        val work = LiftRenderer.scaledToLongEdge(photo, SUBJECT_WORKING_EDGE)
        val w = work.width
        val h = work.height
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        if (work !== photo) work.recycle()
        val mask = SelectionOps.replay(pixels, w, h, subject.ops, shouldStop = shouldStop)
        return MaskGeometry.planeOf(mask.width, mask.height, mask.alpha)
    }

    /** Where a whole-photo plane lands on a group that shows only [crop] of the photo. */
    private fun subjectRect(crop: CropRect, size: GroupSize): RectF {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        return RectF(-crop.left / crop.width * w, -crop.top / crop.height * h, (1f - crop.left) / crop.width * w, (1f - crop.top) / crop.height * h)
    }

    /**
     * Multiplies [group]'s alpha by [plane], stretched over [where] (bilinear when the sizes differ),
     * a row at a time. Done by hand because drawing an alpha-only bitmap with a destination-in blend
     * is not something every graphics stack agrees on, and a mask that quietly does nothing would
     * show the whole photo.
     */
    private fun applyPlane(group: Bitmap, plane: MaskPlane, where: RectF) {
        val w = group.width
        val h = group.height
        val row = IntArray(w)
        val sameGrid = plane.width == w && plane.height == h && where.left == 0f && where.top == 0f && where.right == w.toFloat() && where.bottom == h.toFloat()
        val sx = plane.width / where.width()
        val sy = plane.height / where.height()
        for (y in 0 until h) {
            group.getPixels(row, 0, w, 0, y, w, 1)
            val py = (y + 0.5f - where.top) * sy
            for (x in 0 until w) {
                val c = if (sameGrid) plane.at(x, y) else (plane.coverageAt((x + 0.5f - where.left) * sx, py) * 255f + 0.5f).toInt()
                if (c < 255) {
                    val a = (row[x] ushr 24) * c / 255
                    row[x] = (a shl 24) or (row[x] and 0xFFFFFF)
                }
            }
            group.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /**
     * For the platforms whose multiply mode is a plain product of the two pictures (see
     * [applyBlend]): make the layer opaque by laying it on white, with its opacity folded in as a
     * step toward white, so the product with the backdrop is the real multiply (white changes nothing).
     */
    private fun backWithWhite(group: Bitmap, opacity: Float) {
        val c = Canvas(group)
        c.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
        val towardWhite = ((1f - opacity) * 255f).roundToInt().coerceIn(0, 255)
        if (towardWhite > 0) c.drawColor(Color.argb(towardWhite, 255, 255, 255))
    }

    /**
     * Fills [dst] with the [src] part of [bitmap] through a shader, not drawBitmap: a shader-filled
     * rectangle gets anti-aliased edges when the canvas is turned, a drawn bitmap does not.
     */
    private fun drawRegion(canvas: Canvas, bitmap: Bitmap, src: RectF, dst: RectF, paint: Paint) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(m)
        paint.shader = shader
        canvas.drawRect(dst, paint)
        paint.shader = null
    }

    /**
     * The part of [photo] to put in a [boxW] x [boxH] box: the layer's [crop], unless that part is not the
     * box's shape (a photo that came back the other way round from the size the board was told, as
     * boards made before the sizes were read upright have), in which case it is shrunk, centred, to the
     * box's shape. The picture is cut a little then, never squeezed. A difference of rounding is left to the fill.
     */
    private fun shownCrop(crop: CropRect, photo: Bitmap, boxW: Float, boxH: Float): CropRect {
        val partW = crop.width * photo.width
        val partH = crop.height * photo.height
        if (partW <= 0f || partH <= 0f || boxW <= 0f || boxH <= 0f) return crop
        val boxAspect = boxW / boxH
        val slack = ASPECT_SLACK + 1f / min(partW, partH).coerceAtLeast(1f)
        if (abs(partW / partH / boxAspect - 1f) <= slack) return crop
        return runCatching { crop.fitTo(boxAspect, photo.width.toFloat() / photo.height) }.getOrDefault(crop)
    }

    private fun cropRect(crop: CropRect, bitmap: Bitmap): RectF =
        RectF(crop.left * bitmap.width, crop.top * bitmap.height, crop.right * bitmap.width, crop.bottom * bitmap.height)

    /**
     * The blend on [paint]. Screen and overlay are the same on every API. Multiply is not:
     * [PorterDuff.Mode.MULTIPLY] is the product of the two pictures including their alpha (it makes
     * a half-see-through layer cut a half-see-through hole in what is behind it), and the real
     * multiply is [BlendMode.MULTIPLY], which needs API 29. Below that the layer is made opaque
     * first ([backWithWhite]) and the product is then the real thing. Over a see-through canvas that still
     * leaves nothing where nothing is behind the layer (alpha times alpha), so the layer is also laid behind.
     */
    private fun applyBlend(paint: Paint, blend: LayerBlend) {
        when (blend) {
            LayerBlend.NORMAL -> Unit
            LayerBlend.MULTIPLY -> if (HAS_TRUE_MULTIPLY) {
                paint.blendMode = BlendMode.MULTIPLY
            } else {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            LayerBlend.SCREEN -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            LayerBlend.OVERLAY -> paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }
    }

    private companion object {
        val HAS_TRUE_MULTIPLY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        const val MIN_DRAWN_PX = 0.5f
        const val ASPECT_SLACK = 0.02f
        const val MIN_TEXT_SCALE = 0.05f
        const val SUBJECT_WORKING_EDGE = 512
        const val PLACEHOLDER_FILL = 0xFFD0D0D0.toInt()
        const val PLACEHOLDER_LINE = 0xFF909090.toInt()
    }
}
