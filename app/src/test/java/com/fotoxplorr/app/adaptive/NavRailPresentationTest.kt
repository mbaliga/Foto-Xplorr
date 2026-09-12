package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The nav rail's presentation, and the peek/pin motion split it drives -- the owner's own words
 * for it (2026-08-20): "peek does the shrink and pivot; if nav is turned on, the central pane
 * just shrinks, doesn't pivot".
 */
class NavRailPresentationTest {

    private val compact = WindowSizeClass(WidthSizeClass.COMPACT, HeightSizeClass.MEDIUM)
    private val expanded = WindowSizeClass(WidthSizeClass.EXPANDED, HeightSizeClass.EXPANDED)

    @Test
    fun `a compact window always gets the pull-out room, collapse preference or not`() {
        assertEquals(NavRailPresentation.PULL_OUT, navRailPresentation(compact, userCollapsed = false))
        // The regression this guards: a collapse flag set on a wider screen (or a previous
        // session on one) must not be honoured here -- see the function's own doc.
        assertEquals(NavRailPresentation.PULL_OUT, navRailPresentation(compact, userCollapsed = true))
    }

    @Test
    fun `a wide window pins the rail by default`() {
        assertEquals(NavRailPresentation.PINNED, navRailPresentation(expanded, userCollapsed = false))
    }

    @Test
    fun `a wide window collapses to the narrow strip, not back to the pull-out room`() {
        assertEquals(NavRailPresentation.COLLAPSED, navRailPresentation(expanded, userCollapsed = true))
    }

    @Test
    fun `only the pull-out room swivels -- pinned and collapsed both merely shrink the pane`() {
        assertEquals(ChromeMotion.SHRINK_AND_PIVOT, chromeMotionFor(NavRailPresentation.PULL_OUT))
        assertEquals(ChromeMotion.SHRINK_ONLY, chromeMotionFor(NavRailPresentation.PINNED))
        assertEquals(ChromeMotion.SHRINK_ONLY, chromeMotionFor(NavRailPresentation.COLLAPSED))
    }
}
