package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.fotoxplorr.app.lift.NormPoint

/**
 * Turns a [Sketch] into the picture a creature swims as: a transparent ARGB bitmap, tight round
 * the ink, with [SpritePixels.BORDER] clear pixels on every side, at most [SpritePixels.MAX_SIDE]
 * on its long edge.
 *
 * Strokes are drawn in order. A stroke the sketch marked `filled` first has its inside painted
 * (the pure [OutlineFill.filledRegion] on the same pixel grid), then its antialiased ink goes on
 * top, so the half-covered edge pixels of the ink blend with the fill and not with nothing. The
 * window is the part of the board the ink uses ([SketchWindow]), drawn at full resolution: a small
 * doodle is not a blurry enlargement.
 *
 * Not thread-affine: it creates its own bitmap and touches nothing shared, so the screen runs it on
 * a background dispatcher. Costs a flood fill of at most 284 x 284 cells per filled stroke.
 */
object SketchRasterizer {

    /** The sprite for [strokes], or null when there is no ink to draw. */
    fun rasterize(strokes: List<DrawStroke>): Bitmap? {
        val window = SketchWindow.of(strokes) ?: return null
        val (w, h) = window.pixelSize(SpritePixels.CONTENT_LONG_EDGE)
        val board = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        for (stroke in strokes) {
            val pts = stroke.points.map(window::toWindow)
            if (pts.isEmpty()) continue
            val widthN = window.widthInWindow(stroke.width)
            // The sketch decided "filled" in board units; do not ask again in window units (they differ).
            val closed: List<NormPoint> = if (stroke.filled && pts.size > 1) pts + pts.first() else pts
            if (stroke.filled) {
                val mask = OutlineFill.filledRegionClosed(closed, widthN, w, h)
                if (mask.size == w * h) {
                    val px = IntArray(w * h)
                    board.getPixels(px, 0, w, 0, 0, w, h)
                    SpritePixels.paintMask(px, mask, stroke.argb)
                    board.setPixels(px, 0, w, 0, 0, w, h)
                }
            }
            ink.color = stroke.argb
            val strokeWidthPx = widthN * w
            if (pts.size == 1) {
                ink.style = Paint.Style.FILL
                canvas.drawCircle(pts[0].x * w, pts[0].y * h, strokeWidthPx / 2f, ink)
            } else {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = strokeWidthPx
                ink.strokeCap = Paint.Cap.ROUND
                ink.strokeJoin = Paint.Join.ROUND
                path.rewind()
                // A filled outline is drawn closed too, so the ink never shows the gap the fill closed.
                val drawn = closed
                path.moveTo(drawn[0].x * w, drawn[0].y * h)
                for (i in 1 until drawn.size) path.lineTo(drawn[i].x * w, drawn[i].y * h)
                canvas.drawPath(path, ink)
            }
        }
        val all = IntArray(w * h)
        board.getPixels(all, 0, w, 0, 0, w, h)
        board.recycle()
        val bounds = OutlineFill.opaqueBounds(all, w, h) ?: return null
        val cropped = SpritePixels.cropAndPad(all, w, h, bounds) ?: return null
        return Bitmap.createBitmap(cropped.pixels, cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    }
}
