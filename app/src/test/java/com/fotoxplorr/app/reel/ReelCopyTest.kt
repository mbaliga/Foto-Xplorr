package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ReelCopyTest {
    private val day = 86_400_000L
    private val start = 1_750_000_000_000L

    @Test
    fun `a day alone is one long date`() {
        assertEquals("June 15, 2025", ReelCopy.titleText(start, start + 3_600_000L, null, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test
    fun `the zone decides which day it was`() {
        // 15 June 2025 15:06 UTC is 01:06 on the 16th on a UTC+10 clock.
        assertEquals(
            "June 16, 2025",
            ReelCopy.titleText(start, start, null, ZoneOffset.ofHours(10), Locale.ENGLISH),
        )
    }

    @Test
    fun `a range in one month names the month`() {
        assertEquals("June 2025", ReelCopy.titleText(start, start + 3 * day, null, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test
    fun `a range over months names both ends`() {
        assertEquals(
            "June 15, 2025 – July 20, 2025",
            ReelCopy.titleText(start, start + 35 * day, null, ZoneOffset.UTC, Locale.ENGLISH),
        )
    }

    @Test
    fun `an end before the start is read as one day`() {
        assertEquals("June 15, 2025", ReelCopy.titleText(start, start - 5 * day, null, ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test
    fun `a blank place adds nothing`() {
        assertEquals("June 15, 2025", ReelCopy.titleText(start, start, "   ", ZoneOffset.UTC, Locale.ENGLISH))
    }

    @Test
    fun `the words are one short sentence each`() {
        assertEquals("Picked 4 of 9.", ReelCopy.picked(4, 9))
        for (s in listOf(ReelCopy.BLURB, ReelCopy.NOTHING_TO_USE)) {
            assertTrue(s, s.endsWith(".") && s.length < 70)
        }
        assertEquals("Fade", ReelCopy.transition(ReelTransition.CROSS_FADE))
        assertEquals("Square", ReelCopy.aspect(ReelAspect.SQUARE))
        assertEquals("Pan", ReelCopy.motion(ReelMotion.Pan(NormRect.FULL, NormRect.FULL)))
    }
}
