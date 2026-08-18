package com.fotoxplorr.app.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [ShareFrame] around a photo.
 *
 * `android.graphics` only, exactly like the editor's `EditRenderer` and for the same reason: the
 * offline flavour's classpath gate forbids anything that drags in a network stack, and a border
 * with a perforated edge is a `Canvas` job, not a reason to take a dependency.
 *
 * Every metric here is derived from the photo's own **shorter edge** rather than being a fixed
 * pixel count. A 40px border is a bold statement on a 400px thumbnail and invisible on a 6000px
 * camera original; proportional metrics mean the frame looks like the same frame regardless of
 * what it is wrapped around.
 */
object FrameRenderer {

    /**
     * @param source never modified or recycled -- the caller owns it.
     * @return a new bitmap, larger than [source] by whatever the frame adds.
     */
    fun render(source: Bitmap, options: ShareOptions): Bitmap = when (options.frame) {
        ShareFrame.NONE -> if (options.watermark) watermarkOnly(source, options) else source.copy(Bitmap.Config.ARGB_8888, false)
        ShareFrame.POLAROID -> polaroid(source, options)
        ShareFrame.STAMP -> stamp(source, options)
    }

    // ---------------- Polaroid ----------------

    private fun polaroid(source: Bitmap, options: ShareOptions): Bitmap {
        val unit = min(source.width, source.height).toFloat()
        val side = (unit * POLAROID_SIDE).roundToInt()
        val top = side
        // The deep lower lip IS the format. An even border on all four sides is a matte, not a
        // Polaroid, so this is deliberately several times the side border rather than a tweak.
        val bottom = (unit * POLAROID_BOTTOM).roundToInt()

        val output = Bitmap.createBitmap(
            source.width + side * 2,
            source.height + top + bottom,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(POLAROID_PAPER)

        // A hairline inside the aperture: real instant film sits slightly below the paper, and
        // without some edge the photo bleeds into the white on light images.
        val apertureEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = APERTURE_EDGE
            style = Paint.Style.STROKE
            strokeWidth = max(1f, unit * 0.002f)
        }
        canvas.drawBitmap(source, side.toFloat(), top.toFloat(), null)
        canvas.drawRect(
            RectF(
                side.toFloat(),
                top.toFloat(),
                (side + source.width).toFloat(),
                (top + source.height).toFloat(),
            ),
            apertureEdge,
        )

        options.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = POLAROID_INK
                textSize = unit * POLAROID_CAPTION_SIZE
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            // Optically centred in the lip rather than sitting on its baseline: text centred by
            // its own box hangs low, because a font's descent is dead space most captions do not
            // use. Measuring the actual glyphs is what makes it look centred.
            val bounds = Rect()
            paint.getTextBounds(caption, 0, caption.length, bounds)
            val lipCentre = top + source.height + bottom / 2f
            canvas.drawText(
                ellipsise(caption, paint, output.width * 0.86f),
                output.width / 2f,
                lipCentre + bounds.height() / 2f,
                paint,
            )
        }

        if (options.watermark) drawWatermark(canvas, output.width, output.height, unit, POLAROID_INK)
        return output
    }

    // ---------------- Postage stamp ----------------

    private fun stamp(source: Bitmap, options: ShareOptions): Bitmap {
        val unit = min(source.width, source.height).toFloat()
        val border = (unit * STAMP_BORDER).roundToInt()
        val perfRadius = unit * STAMP_PERF_RADIUS

        val output = Bitmap.createBitmap(
            source.width + border * 2,
            source.height + border * 2,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(STAMP_PAPER)
        canvas.drawBitmap(source, border.toFloat(), border.toFloat(), null)

        // The thin rule between paper and image, which is what makes the border read as a stamp's
        // margin rather than as an accidental gap.
        canvas.drawRect(
            RectF(
                border - unit * 0.006f,
                border - unit * 0.006f,
                border + source.width + unit * 0.006f,
                border + source.height + unit * 0.006f,
            ),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = STAMP_RULE
                style = Paint.Style.STROKE
                strokeWidth = max(1f, unit * 0.003f)
            },
        )

        options.seal?.takeIf { it.isNotBlank() }?.let { seal ->
            drawSeal(canvas, seal, output.width, output.height, unit, border)
        }

        if (options.watermark) drawWatermark(canvas, output.width, output.height, unit, STAMP_INK)

        // Perforations LAST, and punched out of the finished image rather than drawn on top as
        // white dots. CLEAR leaves real transparency, so the stamp keeps its scalloped silhouette
        // against whatever background it is later placed on -- painting them white would only look
        // right on a white page, which is exactly the thing a shared image cannot assume.
        punchPerforations(canvas, output.width, output.height, perfRadius)
        return output
    }

    /**
     * Punch a scalloped edge out of the bitmap.
     *
     * Spacing is solved rather than assumed: a fixed pitch leaves a half-eaten notch in the
     * corner whenever the edge length is not an exact multiple of it. Rounding to a whole number
     * of perforations per edge and distributing the remainder makes both ends land symmetrically
     * on every aspect ratio.
     */
    private fun punchPerforations(canvas: Canvas, width: Int, height: Int, radius: Float) {
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val target = radius * 2.6f
        val horizontalCount = max(2, ceil(width / target).toInt())
        val verticalCount = max(2, ceil(height / target).toInt())
        val horizontalPitch = width / horizontalCount.toFloat()
        val verticalPitch = height / verticalCount.toFloat()

        for (i in 0..horizontalCount) {
            val x = i * horizontalPitch
            canvas.drawCircle(x, 0f, radius, clear)
            canvas.drawCircle(x, height.toFloat(), radius, clear)
        }
        for (i in 0..verticalCount) {
            val y = i * verticalPitch
            canvas.drawCircle(0f, y, radius, clear)
            canvas.drawCircle(width.toFloat(), y, radius, clear)
        }
    }

    /**
     * The user's own mark, drawn as a rotated ring in the stamp's corner -- a postmark, not a
     * caption. Rotated because a perfectly level cancellation stamp looks printed; a real one is
     * struck by hand.
     */
    private fun drawSeal(
        canvas: Canvas,
        seal: String,
        width: Int,
        height: Int,
        unit: Float,
        border: Int,
    ) {
        val radius = unit * SEAL_RADIUS
        val centreX = width - border - radius * 0.9f
        val centreY = border + radius * 0.9f

        canvas.save()
        canvas.rotate(SEAL_ROTATION, centreX, centreY)

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SEAL_INK
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, unit * 0.004f)
            alpha = SEAL_ALPHA
        }
        canvas.drawCircle(centreX, centreY, radius, ink)
        canvas.drawCircle(centreX, centreY, radius * 0.86f, ink)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SEAL_INK
            alpha = SEAL_ALPHA
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = radius * 0.42f
        }
        val trimmed = seal.trim().take(SEAL_MAX_CHARS).uppercase()
        val bounds = Rect()
        text.getTextBounds(trimmed, 0, trimmed.length, bounds)
        canvas.drawText(
            ellipsise(trimmed, text, radius * 1.5f),
            centreX,
            centreY + bounds.height() / 2f,
            text,
        )
        canvas.restore()
    }

    // ---------------- watermark ----------------

    private fun watermarkOnly(source: Bitmap, options: ShareOptions): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val unit = min(output.width, output.height).toFloat()
        drawWatermark(Canvas(output), output.width, output.height, unit, Color.WHITE)
        return output
    }

    /**
     * The app's mark, bottom-right, quiet.
     *
     * Drawn with a soft shadow rather than a solid slab behind it: a watermark has to stay legible
     * over an unknown photo, and a shadow does that without putting an opaque box on someone's
     * picture.
     */
    private fun drawWatermark(canvas: Canvas, width: Int, height: Int, unit: Float, tint: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            textSize = unit * WATERMARK_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            alpha = WATERMARK_ALPHA
            setShadowLayer(unit * 0.006f, 0f, 0f, if (tint == Color.WHITE) Color.BLACK else Color.WHITE)
        }
        val inset = unit * WATERMARK_INSET
        canvas.drawText(WATERMARK_TEXT, width - inset, height - inset, paint)
    }

    // ---------------- shared helpers ----------------

    /**
     * Trim text to fit [maxWidth], appending an ellipsis when it does not.
     *
     * `Paint.breakText` measures against the real font, so this is correct for any typeface and
     * size -- a character-count guess is not, and a caption that overruns a Polaroid's lip and
     * gets clipped mid-word is worse than one that is visibly shortened.
     */
    private fun ellipsise(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val room = maxWidth - paint.measureText(ellipsis)
        if (room <= 0f) return ellipsis
        val fits = paint.breakText(text, true, room, null)
        return text.take(fits).trimEnd() + ellipsis
    }

    // Proportions, all as a fraction of the photo's shorter edge.
    private const val POLAROID_SIDE = 0.055f
    private const val POLAROID_BOTTOM = 0.20f
    private const val POLAROID_CAPTION_SIZE = 0.045f
    private const val STAMP_BORDER = 0.06f
    private const val STAMP_PERF_RADIUS = 0.018f
    private const val SEAL_RADIUS = 0.075f
    private const val SEAL_ROTATION = -12f
    private const val SEAL_ALPHA = 190
    private const val SEAL_MAX_CHARS = 12
    private const val WATERMARK_SIZE = 0.032f
    private const val WATERMARK_INSET = 0.035f
    private const val WATERMARK_ALPHA = 200
    private const val WATERMARK_TEXT = "Foto Xplorr"

    private val POLAROID_PAPER = Color.rgb(250, 249, 245)
    private val POLAROID_INK = Color.rgb(40, 38, 35)
    private val APERTURE_EDGE = Color.argb(40, 0, 0, 0)
    private val STAMP_PAPER = Color.rgb(247, 244, 236)
    private val STAMP_RULE = Color.argb(60, 0, 0, 0)
    private val STAMP_INK = Color.rgb(40, 38, 35)
    private val SEAL_INK = Color.rgb(120, 40, 40)
}
