package com.fotoxplorr.app.lift

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The transparency-grid backdrop every image editor puts behind a cut-out, so the alpha channel
 * is visible as alpha rather than being read as "a white sticker" or "a black one".
 *
 * Fixed greys on purpose (semantic, like the mid-grey card this replaces): the squares have to
 * read as "nothing here" against any sticker colour and on either theme, which neither accent nor
 * surface colours can promise. The pair is deliberately low-contrast so the grid never competes
 * with the subject.
 */
val CheckerLight = Color(0xFFC9C9C9)
val CheckerDark = Color(0xFF9E9E9E)

/**
 * A [Brush] that tiles a two-by-two block of [cell]-sized squares. One small bitmap and a
 * repeating shader rather than a loop of `drawRect` calls: a full-screen grid is thousands of
 * squares, redrawn every frame the editor repaints.
 */
@Composable
fun rememberCheckerBrush(cell: Dp = 12.dp, light: Color = CheckerLight, dark: Color = CheckerDark): Brush {
    val cellPx = with(LocalDensity.current) { cell.toPx() }.roundToInt().coerceAtLeast(2)
    return remember(cellPx, light, dark) { checkerBrush(cellPx, light, dark) }
}

/** [rememberCheckerBrush] as a background: `Modifier.checkerboard()`. */
@Composable
fun Modifier.checkerboard(cell: Dp = 12.dp, light: Color = CheckerLight, dark: Color = CheckerDark): Modifier =
    background(rememberCheckerBrush(cell, light, dark))

private fun checkerBrush(cellPx: Int, light: Color, dark: Color): Brush {
    val tile = cellPx * 2
    val image = ImageBitmap(tile, tile)
    val canvas = Canvas(image)
    val paint = Paint()
    val size = cellPx.toFloat()
    paint.color = light
    canvas.drawRect(0f, 0f, size * 2, size * 2, paint)
    paint.color = dark
    canvas.drawRect(0f, 0f, size, size, paint)
    canvas.drawRect(size, size, size * 2, size * 2, paint)
    return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
}
