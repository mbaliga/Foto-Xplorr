package com.fotoxplorr.app.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

/**
 * The perforated postage-stamp silhouette, as a Compose [Shape].
 *
 * The motif recurs across the app -- the calendar's day tiles, the map's photo pins, the share
 * frame -- so it exists once, here, rather than three times with slightly different notch sizes.
 * (The share frame draws its own version in `android.graphics` because it renders to a Bitmap for
 * export rather than clipping a composable; the two agree on proportions, which is the part that
 * has to match visually.)
 *
 * Being a [Shape] rather than a decoration means it clips real content: a photo in a stamp tile is
 * genuinely stamp-shaped, including where it meets whatever is behind it, instead of having white
 * dots painted over its edges. That is what lets it sit on the calendar's light ground and the
 * map's coloured field without either needing to know the other's background colour.
 *
 * @param notchRadius radius of each perforation. Also sets their spacing, so one value controls
 *   the whole edge rhythm and the notches cannot end up dense and tiny or sparse and huge.
 */
class StampShape(private val notchRadius: androidx.compose.ui.unit.Dp = 3.dp) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { notchRadius.toPx() }.coerceAtMost(min(size.width, size.height) / 4f)
        val body = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
        if (r <= 0f) return Outline.Generic(body)

        // Solved per edge rather than at a fixed pitch: a fixed pitch leaves a half-eaten notch in
        // the corner whenever the edge is not an exact multiple of it, which reads as a mistake
        // rather than as perforation.
        val notches = Path()
        val pitch = r * 2.6f
        val across = max(2, ceil(size.width / pitch).toInt())
        val down = max(2, ceil(size.height / pitch).toInt())
        val stepX = size.width / across
        val stepY = size.height / down

        for (i in 0..across) {
            val x = i * stepX
            notches.addOval(androidx.compose.ui.geometry.Rect(x - r, -r, x + r, r))
            notches.addOval(androidx.compose.ui.geometry.Rect(x - r, size.height - r, x + r, size.height + r))
        }
        for (i in 0..down) {
            val y = i * stepY
            notches.addOval(androidx.compose.ui.geometry.Rect(-r, y - r, r, y + r))
            notches.addOval(androidx.compose.ui.geometry.Rect(size.width - r, y - r, size.width + r, y + r))
        }

        return Outline.Generic(Path().apply { op(body, notches, PathOperation.Difference) })
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b
}

/** The default stamp, shared so every surface using the motif matches without coordinating. */
val FotoStamp = StampShape()
