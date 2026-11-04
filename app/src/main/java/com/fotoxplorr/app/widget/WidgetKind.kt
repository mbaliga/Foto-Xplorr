package com.fotoxplorr.app.widget

/**
 * The six home-screen widgets. Each has its own provider (so a launcher lists six entries) and one
 * of the strategy's templates as its layout pattern.
 *
 * @param thresholds which of the three layouts a given size gets.
 * @param dateDependent true when what the widget shows changes at local midnight, so a one-off
 *   JobScheduler job redraws it then (see [WidgetDayJob]).
 */
internal enum class WidgetKind(val thresholds: SizeThresholds, val dateDependent: Boolean) {
    /** `media`: one favourite photo, a different one each day. */
    FAVOURITE(Sizes.FAVOURITE, dateDependent = true),

    /** `media` (strip): photos taken on today's date in earlier years. */
    ON_THIS_DAY(Sizes.ON_THIS_DAY, dateDependent = true),

    /** `media` (strip/grid): the newest photos and videos. */
    RECENT(Sizes.RECENT, dateDependent = false),

    /** `stat`: counts by type. */
    LIBRARY(Sizes.LIBRARY, dateDependent = false),

    /** `list`: collections, each with a cover. */
    COLLECTIONS(Sizes.COLLECTIONS, dateDependent = false),

    /** `progress`: how much of the library is indexed on this phone. */
    INDEXING(Sizes.INDEXING, dateDependent = false),
}

/**
 * Each widget's three layout minimums: a width, then the height at each font step of
 * [LayoutMinimum.FONT_STEPS] (1x, 1.15x, 1.3x, 1.5x, 1.8x, 2x), measured by drawing the layouts
 * (see [LayoutMinimum]). Small layouts start at 110 dp wide, the providers' `minResizeWidth`. Where a
 * height is larger than the layout needs, it is the design choice of when that layout is worth
 * using: a large photo from about three cells high, five list rows from about three.
 */
private object Sizes {
    private fun of(width: Float, vararg heights: Float) = LayoutMinimum(width, heights.toList())

    /** Photo; photo beside three lines; large photo above them. */
    val FAVOURITE = SizeThresholds(
        of(110f, 100f, 100f, 100f, 100f, 100f, 100f),
        of(250f, 103f, 115f, 125f, 140f, 185f, 204f),
        of(250f, 250f, 250f, 250f, 250f, 250f, 250f),
    )

    /** Photo; a header over four captioned tiles; a header, a large photo and four tiles. */
    val ON_THIS_DAY = SizeThresholds(
        of(110f, 100f, 100f, 100f, 100f, 100f, 100f),
        of(250f, 100f, 100f, 100f, 104f, 114f, 122f),
        of(250f, 250f, 250f, 250f, 250f, 250f, 251f),
    )

    /** Photo; a header over four tiles; a header over two rows of four. */
    val RECENT = SizeThresholds(
        of(110f, 100f, 100f, 100f, 100f, 100f, 100f),
        of(250f, 100f, 100f, 100f, 100f, 103f, 108f),
        of(250f, 250f, 250f, 250f, 250f, 250f, 250f),
    )

    /** One count; a header over three counts; a header over six counts and a footer. */
    val LIBRARY = SizeThresholds(
        of(110f, 72f, 80f, 86f, 96f, 109f, 119f),
        of(250f, 98f, 107f, 116f, 129f, 147f, 160f),
        of(250f, 204f, 227f, 248f, 279f, 321f, 353f),
    )

    /** Cover; a header over three rows; a header over five rows. */
    val COLLECTIONS = SizeThresholds(
        of(110f, 100f, 100f, 100f, 100f, 100f, 100f),
        of(200f, 202f, 204f, 208f, 219f, 253f, 281f),
        of(200f, 306f, 309f, 312f, 325f, 378f, 422f),
    )

    /** One percentage and a bar; a header over two progress sections; those plus status and footer. */
    val INDEXING = SizeThresholds(
        of(110f, 83f, 91f, 97f, 106f, 119f, 129f),
        of(250f, 117f, 125f, 132f, 143f, 157f, 169f),
        of(250f, 206f, 223f, 240f, 265f, 297f, 324f),
    )
}
