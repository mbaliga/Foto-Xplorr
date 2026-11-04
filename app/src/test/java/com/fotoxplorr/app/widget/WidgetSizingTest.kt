package com.fotoxplorr.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which layout each size gets, on both paths (API 31+ keys the layouts by [SizeThresholds.minimumFor];
 * older launchers report options and [sizeFromOptions] picks), and how much bitmap an update carries.
 * Cell sizes are the platform guidance's portrait/landscape dp for common grids. That each layout
 * really fits at its minimum is `WidgetRenderTest`'s job.
 */
class WidgetSizingTest {

    @Test
    fun `picture widgets get one picture at 2x2, a strip at 4x2 and the large layout at 4x3`() {
        for (kind in listOf(WidgetKind.FAVOURITE, WidgetKind.ON_THIS_DAY, WidgetKind.RECENT)) {
            val t = kind.thresholds
            assertEquals("$kind", WidgetSize.SMALL, t.sizeFor(130f, 220f)) // 2x2 portrait
            assertEquals("$kind", WidgetSize.MEDIUM, t.sizeFor(276f, 220f)) // 4x2 portrait
            assertEquals("$kind", WidgetSize.LARGE, t.sizeFor(276f, 337f)) // 4x3 portrait
            assertEquals("$kind", WidgetSize.SMALL, t.sizeFor(130f, 452f)) // 2x4: tall and narrow stays one picture
            assertEquals("$kind", WidgetSize.SMALL, t.sizeFor(40f, 40f)) // below every minimum: the single tap target
        }
    }

    @Test
    fun `library fits a 2x1, and a 4x1 portrait shows its row of counts`() {
        val t = WidgetKind.LIBRARY.thresholds
        assertEquals(WidgetSize.SMALL, t.sizeFor(130f, 102f)) // 2x1 portrait
        assertEquals(WidgetSize.SMALL, t.sizeFor(554f, 51f)) // 4x1 landscape: one value
        assertEquals(WidgetSize.MEDIUM, t.sizeFor(276f, 102f)) // 4x1 portrait
        assertEquals(WidgetSize.LARGE, t.sizeFor(276f, 220f)) // 4x2 portrait
    }

    @Test
    fun `indexing needs more height than library before it shows two sections`() {
        val t = WidgetKind.INDEXING.thresholds
        // A 4x1 portrait used to get the two-section layout and clip it; it keeps the percentage.
        assertEquals(WidgetSize.SMALL, t.sizeFor(276f, 102f))
        assertEquals(WidgetSize.MEDIUM, t.sizeFor(554f, 184f)) // 4x3 landscape
        assertEquals(WidgetSize.LARGE, t.sizeFor(276f, 337f)) // 4x3 portrait
        assertTrue(t.minimumFor(WidgetSize.MEDIUM).second > WidgetKind.LIBRARY.thresholds.minimumFor(WidgetSize.MEDIUM).second)
    }

    @Test
    fun `collections show one card, then three rows, then five`() {
        val t = WidgetKind.COLLECTIONS.thresholds
        assertEquals(WidgetSize.SMALL, t.sizeFor(130f, 220f))
        assertEquals(WidgetSize.SMALL, t.sizeFor(554f, 117f))
        assertEquals(WidgetSize.MEDIUM, t.sizeFor(276f, 220f))
        assertEquals(WidgetSize.LARGE, t.sizeFor(276f, 337f))
    }

    @Test
    fun `a larger font needs more height for the same layout, and never less`() {
        for (kind in WidgetKind.entries) {
            for (size in WidgetSize.entries) {
                val (w1, h1) = kind.thresholds.minimumFor(size, 1f)
                val (w2, h2) = kind.thresholds.minimumFor(size, 2f)
                assertEquals(w1, w2)
                assertTrue("$kind $size", h2 >= h1)
                assertEquals("a small font keeps the 1x minimum", h1, kind.thresholds.minimumFor(size, 0.85f).second)
            }
            // The three layouts stay ordered at every font scale, so "largest that fits" is well defined.
            for (fs in listOf(1f, 1.3f, 2f)) {
                val (sw, sh) = kind.thresholds.minimumFor(WidgetSize.SMALL, fs)
                val (mw, mh) = kind.thresholds.minimumFor(WidgetSize.MEDIUM, fs)
                val (lw, lh) = kind.thresholds.minimumFor(WidgetSize.LARGE, fs)
                assertTrue("$kind $fs", sw <= mw && mw <= lw && sh <= mh && mh <= lh)
            }
        }
        // 4x2 portrait: large at the default font, a smaller layout at double size.
        assertEquals(WidgetSize.LARGE, WidgetKind.LIBRARY.thresholds.sizeFor(276f, 220f, 1f))
        assertTrue(WidgetKind.LIBRARY.thresholds.sizeFor(276f, 220f, 2f) != WidgetSize.LARGE)
    }

    @Test
    fun `the API 31 keys and the options path agree on every layout`() {
        for (kind in WidgetKind.entries) {
            for (size in WidgetSize.entries) {
                for (fs in listOf(1f, 1.3f, 2f)) {
                    val (w, h) = kind.thresholds.minimumFor(size, fs)
                    val wi = kotlin.math.ceil(w).toInt()
                    val hi = kotlin.math.ceil(h).toInt()
                    assertEquals("$kind $size $fs", size, kind.thresholds.sizeFor(w, h, fs))
                    assertEquals("$kind $size $fs", size, sizeFromOptions(wi, hi, wi, hi, portrait = true, kind.thresholds, fs))
                }
            }
        }
    }

    @Test
    fun `options are read the platform's way round in each orientation`() {
        val t = WidgetKind.RECENT.thresholds
        // A 4x2 widget: narrow and tall in portrait, wide and short in landscape.
        assertEquals(WidgetSize.MEDIUM, sizeFromOptions(276, 117, 554, 220, portrait = true, thresholds = t))
        assertEquals(WidgetSize.MEDIUM, sizeFromOptions(276, 117, 554, 220, portrait = false, thresholds = t))
        // A 4x3: large only where its height allows.
        assertEquals(WidgetSize.LARGE, sizeFromOptions(276, 184, 554, 337, portrait = true, thresholds = t))
        assertEquals(WidgetSize.MEDIUM, sizeFromOptions(276, 184, 554, 337, portrait = false, thresholds = t))
        // A launcher that never reported options gets the layout that fits anything.
        assertEquals(WidgetSize.SMALL, sizeFromOptions(0, 0, 0, 0, portrait = true, thresholds = t))
    }

    @Test
    fun `the bitmap budget is a third of the platform's per-update limit`() {
        // AppWidgetManager allows 1.5 x the screen's own bytes: 6 * w * h.
        assertEquals(6L * 1080 * 2400 / 3, bitmapBudgetBytes(1080, 2400))
        assertTrue(bitmapBudgetBytes(0, 0) > 0)
    }

    @Test
    fun `decode sizes fit the budget, shrinking evenly and never below the floor`() {
        val wanted = listOf(HERO_EDGE_PX) + List(4) { TILE_EDGE_PX }
        val roomy = bitmapBudgetBytes(1080, 2400)
        assertEquals(wanted, planDecodeEdges(wanted, roomy))

        val small = bitmapBudgetBytes(720, 1280) // about 1.8 MB
        val planned = planDecodeEdges(wanted, small)
        assertTrue(planned.sumOf { 4L * it * it } <= small)
        assertTrue(planned[0] > planned[1])
        assertTrue(planned.all { it >= MIN_EDGE_PX })

        val tiny = planDecodeEdges(List(8) { TILE_EDGE_PX }, 1_000L)
        assertEquals(List(8) { MIN_EDGE_PX }, tiny)
        assertEquals(emptyList<Int>(), planDecodeEdges(emptyList(), roomy))
    }

    @Test
    fun `the largest widget stays within budget on a 720p screen`() {
        // Recent, large: one hero-sized and seven tile-sized pictures.
        val planned = planDecodeEdges(listOf(HERO_EDGE_PX) + List(7) { TILE_EDGE_PX }, bitmapBudgetBytes(720, 1280))
        assertTrue(planned.sumOf { 4L * it * it } <= 6L * 720 * 1280)
    }

    @Test
    fun `request codes are unique per widget and tap target`() {
        val codes = (1..50).flatMap { id -> (0 until SLOTS_PER_WIDGET).map { slot -> widgetRequestCode(id, slot) } }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a slot outside the widget's range is refused rather than colliding`() {
        widgetRequestCode(3, SLOTS_PER_WIDGET)
    }
}
