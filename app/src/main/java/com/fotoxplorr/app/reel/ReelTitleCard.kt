package com.fotoxplorr.app.reel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import kotlin.math.max

/**
 * Draws a title card ([TitleItem]) as a picture the size of the video frame: the words centred on the
 * card's colour. The first line is the large bold one (the date), any further lines are smaller (the
 * place). The words shrink until they fit; they are never cut off. The text colour is white or near-black,
 * whichever reads better on the card's colour ([readableOn]).
 *
 * It uses the platform's text layout, so how it LOOKS (fonts, wrapping of a long place name, right-to-left
 * text) has not been seen on a device; the tests check the size, the colours and that the words fit.
 */
object ReelTitleCard {
    private const val FIRST_LINE_SIZE = 0.075f
    private const val SMALLER_LINES = 0.62f
    private const val MIN_TEXT_PX = 14f
    private const val SIDE_MARGIN = 0.1f
    private const val HEIGHT_SHARE = 0.8f
    private const val SHRINK = 0.92f
    private const val DARK_TEXT = 0xFF111418.toInt()

    /** White or near-black text for [backgroundArgb], by its brightness (the alpha byte is ignored). */
    fun readableOn(backgroundArgb: Int): Int {
        val r = Color.red(backgroundArgb) / 255.0
        val g = Color.green(backgroundArgb) / 255.0
        val b = Color.blue(backgroundArgb) / 255.0
        // The usual weights for how bright a colour looks; above the middle the card is light.
        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luma > 0.55) DARK_TEXT else Color.WHITE
    }

    /** The card as a new bitmap of [width] x [height]; the caller recycles it. */
    fun render(text: String, backgroundArgb: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundArgb or OPAQUE)
        val words = text.trim().take(ReelLimits.TEXT_MAX_CHARS)
        if (words.isEmpty()) return bitmap
        val boxWidth = max(1, (width * (1f - 2f * SIDE_MARGIN)).toInt())
        val boxHeight = height * HEIGHT_SHARE
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = readableOn(backgroundArgb)
            typeface = Typeface.DEFAULT
        }
        var size = minOf(width, height) * FIRST_LINE_SIZE * 1.6f
        var layout = layoutOf(words, paint, size, boxWidth)
        while (layout.height > boxHeight && size > MIN_TEXT_PX) {
            size *= SHRINK
            layout = layoutOf(words, paint, size, boxWidth)
        }
        canvas.save()
        canvas.translate((width - boxWidth) / 2f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun layoutOf(words: String, paint: TextPaint, size: Float, boxWidth: Int): StaticLayout {
        paint.textSize = size
        val lines = words.split('\n')
        val styled = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) styled.append('\n')
            val from = styled.length
            styled.append(line)
            if (i == 0) {
                styled.setSpan(StyleSpan(Typeface.BOLD), from, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                styled.setSpan(RelativeSizeSpan(SMALLER_LINES), from, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return StaticLayout.Builder.obtain(styled, 0, styled.length, paint, boxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
