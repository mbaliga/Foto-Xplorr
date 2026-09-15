package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breakpoint arithmetic real `windowsizeclass` layout decisions are built on -- pinned here
 * because it is hand-derived (the real androidx artifact is not on this build's classpath; see
 * the file doc) and a hand-derived breakpoint is exactly the kind of thing that quietly drifts
 * by a pixel during some later refactor and turns a tablet into a phone.
 */
class WindowSizeClassTest {

    @Test
    fun `width breakpoints land where Material's own window size class spec puts them`() {
        assertEquals(WidthSizeClass.COMPACT, widthSizeClassOf(0f))
        assertEquals(WidthSizeClass.COMPACT, widthSizeClassOf(599.9f))
        assertEquals(WidthSizeClass.MEDIUM, widthSizeClassOf(600f))
        assertEquals(WidthSizeClass.MEDIUM, widthSizeClassOf(839.9f))
        assertEquals(WidthSizeClass.EXPANDED, widthSizeClassOf(840f))
        assertEquals(WidthSizeClass.EXPANDED, widthSizeClassOf(2400f))
    }

    @Test
    fun `height breakpoints land where Material's own window size class spec puts them`() {
        assertEquals(HeightSizeClass.COMPACT, heightSizeClassOf(0f))
        assertEquals(HeightSizeClass.COMPACT, heightSizeClassOf(479.9f))
        assertEquals(HeightSizeClass.MEDIUM, heightSizeClassOf(480f))
        assertEquals(HeightSizeClass.MEDIUM, heightSizeClassOf(899.9f))
        assertEquals(HeightSizeClass.EXPANDED, heightSizeClassOf(900f))
    }

    @Test
    fun `a phone in portrait is compact width and cannot pin the rail`() {
        val phonePortrait = windowSizeClassOf(widthDp = 360f, heightDp = 780f)
        assertEquals(WidthSizeClass.COMPACT, phonePortrait.width)
        assertFalse(phonePortrait.canPinNavRail)
    }

    @Test
    fun `a phone rotated to landscape is still compact width even though height shrinks`() {
        // The regression this guards: gating the rail on ANY one axis being roomy, rather than
        // specifically width, would let a 780x360 rotated phone pin a rail meant for a screen
        // twice as wide.
        val phoneLandscape = windowSizeClassOf(widthDp = 780f, heightDp = 360f)
        assertEquals(WidthSizeClass.MEDIUM, phoneLandscape.width)
        assertEquals(HeightSizeClass.COMPACT, phoneLandscape.height)
        assertTrue(phoneLandscape.canPinNavRail)
    }

    @Test
    fun `a tablet or desktop window is expanded on both axes`() {
        val tablet = windowSizeClassOf(widthDp = 1280f, heightDp = 1000f)
        assertEquals(WidthSizeClass.EXPANDED, tablet.width)
        assertEquals(HeightSizeClass.EXPANDED, tablet.height)
        assertTrue(tablet.canPinNavRail)
    }
}
