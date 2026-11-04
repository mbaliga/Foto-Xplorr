package com.fotoxplorr.app.widget

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** The three layouts every widget has. */
internal enum class WidgetSize { SMALL, MEDIUM, LARGE }

/**
 * The smallest size at which one layout is used: a width, and a height that grows with the system
 * font size, because the text inside grows with it and the pictures and padding do not. The height
 * is given at each of Android's font sizes from 1x to 2x ([FONT_STEPS]) and is linear between them.
 * Text does not grow a layout evenly (a list row only grows once its two lines outgrow its 52 dp, a
 * title wraps to a second line at some size), so no single formula fits every layout.
 *
 * The heights are measured from the layouts, plus 2 dp, and never below the design minimum at which
 * a layout is worth using. `WidgetRenderTest` draws every layout at exactly this size at every step
 * and fails on anything clipped, any line of text cut or a picture squeezed below 40 dp.
 */
internal data class LayoutMinimum(val widthDp: Float, val heightsDp: List<Float>) {
    init {
        require(heightsDp.size == FONT_STEPS.size) { "one height per font step" }
        require(heightsDp.zipWithNext().all { (a, b) -> a <= b }) { "a larger font never needs less height" }
    }

    /** Below 1x keeps the 1x height (smaller text only fits more easily); above 2x keeps growing. */
    fun heightAt(fontScale: Float): Float {
        if (fontScale <= FONT_STEPS.first()) return heightsDp.first()
        for (i in 1 until FONT_STEPS.size) {
            if (fontScale <= FONT_STEPS[i]) {
                val t = (fontScale - FONT_STEPS[i - 1]) / (FONT_STEPS[i] - FONT_STEPS[i - 1])
                return heightsDp[i - 1] + (heightsDp[i] - heightsDp[i - 1]) * t
            }
        }
        val last = FONT_STEPS.size - 1
        val slope = (heightsDp[last] - heightsDp[last - 1]) / (FONT_STEPS[last] - FONT_STEPS[last - 1])
        return heightsDp[last] + slope * (fontScale - FONT_STEPS[last])
    }

    companion object {
        /** The font sizes Android's settings offer from the default up (API 34 adds those above 1.3). */
        val FONT_STEPS = listOf(1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)
    }
}

/**
 * Which of the three layouts a size gets. One table drives both the API 31+ sized `RemoteViews` map
 * (each layout keyed by its own minimum; the launcher picks the largest that fits, the smallest when
 * none does) and the older path that reads the widget's options and picks here, so the two never
 * disagree. Each widget has its own table, because its layouts need different room (Indexing's two
 * progress sections need more height than Library's row of counts).
 *
 * Platform cell sizes for reference (portrait / landscape, dp): 2x1 130x102 / 269x51, 4x1 276x102 /
 * 554x51, 2x2 130x220 / 269x117, 4x2 276x220 / 554x117, 4x3 276x337 / 554x184.
 */
internal data class SizeThresholds(val small: LayoutMinimum, val medium: LayoutMinimum, val large: LayoutMinimum) {
    fun minimumFor(size: WidgetSize, fontScale: Float = 1f): Pair<Float, Float> {
        val minimum = when (size) {
            WidgetSize.SMALL -> small
            WidgetSize.MEDIUM -> medium
            WidgetSize.LARGE -> large
        }
        return minimum.widthDp to minimum.heightAt(fontScale)
    }

    /** The largest layout whose minimum fits; the small one when nothing fits (the platform rule too). */
    fun sizeFor(widthDp: Float, heightDp: Float, fontScale: Float = 1f): WidgetSize {
        fun fits(minimum: LayoutMinimum) = widthDp >= minimum.widthDp && heightDp >= minimum.heightAt(fontScale)
        return when {
            fits(large) -> WidgetSize.LARGE
            fits(medium) -> WidgetSize.MEDIUM
            else -> WidgetSize.SMALL
        }
    }
}

/**
 * Below API 31 the launcher reports a range, not a size: in portrait the widget is
 * `minWidth x maxHeight`, in landscape `maxWidth x minHeight` (the platform's own documented rule
 * for these four options).
 */
internal fun sizeFromOptions(
    minWidthDp: Int,
    minHeightDp: Int,
    maxWidthDp: Int,
    maxHeightDp: Int,
    portrait: Boolean,
    thresholds: SizeThresholds,
    fontScale: Float = 1f,
): WidgetSize {
    // A launcher that never reported options leaves all four at zero; use the small layout,
    // which is a single tappable summary and fits anything.
    if (minWidthDp <= 0 && maxWidthDp <= 0) return WidgetSize.SMALL
    val width = if (portrait) minWidthDp else max(minWidthDp, maxWidthDp)
    val height = if (portrait) max(minHeightDp, maxHeightDp) else minHeightDp
    return thresholds.sizeFor(width.toFloat(), height.toFloat(), fontScale)
}

/**
 * How many bytes of bitmaps one widget update may carry. AppWidgetManager refuses an update whose
 * bitmaps exceed 1.5 x the screen's own pixel bytes (`6 * w * h` at four bytes a pixel); this keeps
 * to a third of that, so a widget never sits near the limit on any screen.
 */
internal fun bitmapBudgetBytes(screenWidthPx: Int, screenHeightPx: Int): Long =
    6L * max(screenWidthPx, 1) * max(screenHeightPx, 1) / 3L

/**
 * The long-edge size, in pixels, each requested image is decoded at. Each image is bounded to
 * `edge * edge` pixels (four bytes each); when the wanted sizes together exceed [budgetBytes] every
 * edge shrinks by the same factor, never below [MIN_EDGE_PX], so a large widget on a small screen
 * gets softer pictures rather than a refused update.
 */
internal fun planDecodeEdges(wantedEdges: List<Int>, budgetBytes: Long): List<Int> {
    if (wantedEdges.isEmpty()) return emptyList()
    val wantedBytes = wantedEdges.sumOf { 4L * it * it }
    if (wantedBytes <= budgetBytes) return wantedEdges
    val scale = sqrt(budgetBytes.toDouble() / wantedBytes.toDouble())
    return wantedEdges.map { max(MIN_EDGE_PX, floor(it * scale).toInt()) }
}

internal const val MIN_EDGE_PX = 96

/** A photo that fills a 2x2 or 4x3 widget. */
internal const val HERO_EDGE_PX = 720

/** One tile of a strip or grid. */
internal const val TILE_EDGE_PX = 300

/** A list row's thumbnail. */
internal const val ROW_EDGE_PX = 160
