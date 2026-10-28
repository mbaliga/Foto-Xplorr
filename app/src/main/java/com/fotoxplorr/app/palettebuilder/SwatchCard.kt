package com.fotoxplorr.app.palettebuilder

/** One colour block of a [SwatchCardSpec], in pixels from the card's top-left; `right`/`bottom` are exclusive. */
data class SwatchCell(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val argb: Int,
    /** The hex printed inside the block. */
    val label: String,
    /** Black or white, whichever reads better on [argb]. */
    val labelArgb: Int,
    val labelSizePx: Float,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Everything needed to paint a swatch card, as numbers: the size, the colours, the text and where it goes.
 * The Android seam that fills a `Bitmap` from this holds no layout decisions, so the layout is testable
 * on a JVM. Nothing here has been drawn on a device, and font metrics are the seam's to measure.
 */
data class SwatchCardSpec(
    val widthPx: Int,
    val heightPx: Int,
    val backgroundArgb: Int,
    val title: String,
    val titleArgb: Int,
    val titleSizePx: Float,
    /** Left edge and baseline of the title. */
    val titleX: Int,
    val titleBaselineY: Int,
    val cells: List<SwatchCell>,
)

/**
 * Lays a palette out as a card: a title line, then the colours as equal blocks in rows of up to six, each
 * labelled with its hex. Deterministic and integer-exact: blocks never overlap, never leave the card, and
 * every colour of the palette is one block, in palette order.
 */
object SwatchCard {
    const val MIN_WIDTH_PX = 240
    const val MAX_WIDTH_PX = 4096
    const val DEFAULT_WIDTH_PX = 1080
    const val MAX_COLUMNS = 6

    private const val CARD_BACKGROUND = 0xFFF8F8F8.toInt()
    private const val CARD_TITLE = 0xFF202020.toInt()

    fun layout(palette: Palette, widthPx: Int = DEFAULT_WIDTH_PX): SwatchCardSpec {
        val w = widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
        val margin = w / 24
        val gap = margin / 2
        val titleBand = w / 9
        val columns = minOf(palette.size, MAX_COLUMNS)
        val rows = (palette.size + columns - 1) / columns
        val cellW = (w - 2 * margin - gap * (columns - 1)) / columns
        val cellH = cellW * 6 / 5
        // Whole-pixel columns leave a few spare pixels on the right; the card is as wide as asked, the blocks sit from the margin.
        val h = margin + titleBand + rows * cellH + (rows - 1) * gap + margin
        val labelSize = (cellW / 7f).coerceAtLeast(10f)
        val cells = palette.colors.mapIndexed { i, c ->
            val col = i % columns
            val row = i / columns
            val left = margin + col * (cellW + gap)
            val top = margin + titleBand + row * (cellH + gap)
            SwatchCell(left, top, left + cellW, top + cellH, c.argb, c.hex, Contrast.plainTextOn(c.argb), labelSize)
        }
        return SwatchCardSpec(
            widthPx = w,
            heightPx = h,
            backgroundArgb = CARD_BACKGROUND,
            title = PaletteBuilderCopy.cardTitle(palette.name),
            titleArgb = CARD_TITLE,
            titleSizePx = titleBand * 0.5f,
            titleX = margin,
            titleBaselineY = margin + titleBand * 3 / 4,
            cells = cells,
        )
    }
}
