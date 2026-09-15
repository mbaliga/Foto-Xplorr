package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top room's flight is driven by a drag, so it has to be right at every fractional openness
 * the finger can stop at — not just at the two ends a screenshot would catch. These pin the
 * curve, the staggering and the plate's geometry without a device.
 */
class PlaceMorphTest {

    @Test
    fun `flight starts at rest and arrives fully`() {
        assertEquals(0f, PlaceMorph.flight(0f), TOLERANCE)
        assertEquals(1f, PlaceMorph.flight(1f), TOLERANCE)
        // Smoothstep is symmetric about its midpoint, so a half-open room is a half-flown photo.
        assertEquals(0.5f, PlaceMorph.flight(0.5f), TOLERANCE)
    }

    @Test
    fun `flight is monotonic so reversing a drag reverses the motion`() {
        var previous = -1f
        var reveal = 0f
        while (reveal <= 1f) {
            val value = PlaceMorph.flight(reveal)
            assertTrue("flight went backwards at $reveal", value >= previous)
            previous = value
            reveal += 0.02f
        }
    }

    @Test
    fun `flight clamps outside the unit range rather than overshooting`() {
        // The shell clamps its axes, but an overshoot here would scale the thumbnail past its
        // hero size and pop — worth pinning independently of the shell's own guarantees.
        assertEquals(0f, PlaceMorph.flight(-0.4f), TOLERANCE)
        assertEquals(1f, PlaceMorph.flight(1.6f), TOLERANCE)
    }

    @Test
    fun `stagger maps a window onto the full range`() {
        assertEquals(0f, PlaceMorph.stagger(0.45f, 0.45f, 1f), TOLERANCE)
        assertEquals(1f, PlaceMorph.stagger(1f, 0.45f, 1f), TOLERANCE)
        assertEquals(0.5f, PlaceMorph.stagger(0.5f, 0.4f, 0.6f), TOLERANCE)
        // Before the window opens, nothing has happened yet.
        assertEquals(0f, PlaceMorph.stagger(0.2f, 0.45f, 1f), TOLERANCE)
    }

    @Test
    fun `stagger survives a degenerate window`() {
        assertEquals(1f, PlaceMorph.stagger(0.9f, 0.5f, 0.5f), TOLERANCE)
        assertEquals(0f, PlaceMorph.stagger(0.2f, 0.5f, 0.5f), TOLERANCE)
    }

    @Test
    fun `the thumbnail starts hero-sized and lands at pin size`() {
        val hero = 4.2f
        assertEquals(hero, PlaceMorph.thumbnailScale(0f, hero), TOLERANCE)
        assertEquals(1f, PlaceMorph.thumbnailScale(1f, hero), TOLERANCE)
        // It finishes early, so the last stretch of the pull is the text settling rather than
        // the photo still visibly shrinking.
        assertEquals(1f, PlaceMorph.thumbnailScale(0.85f, hero), TOLERANCE)
    }

    @Test
    fun `the thumbnail shrinks monotonically through its flight`() {
        val hero = 4.2f
        var previous = Float.MAX_VALUE
        var reveal = 0f
        while (reveal <= 1f) {
            val scale = PlaceMorph.thumbnailScale(reveal, hero)
            assertTrue("thumbnail grew at $reveal", scale <= previous + TOLERANCE)
            previous = scale
            reveal += 0.02f
        }
    }

    @Test
    fun `the room arrives in order - photo, then plate, then text`() {
        // A third of the way in: the photo is well into its flight, the plate is only starting
        // to resolve, and the text has not begun. That ordering is the whole point of the
        // stagger, so it is asserted as an ordering rather than as three magic numbers.
        val reveal = 0.33f
        val photo = 1f - (PlaceMorph.thumbnailScale(reveal, 4f) - 1f) / 3f
        val plate = PlaceMorph.plateAlpha(reveal)
        val text = PlaceMorph.textAlpha(reveal)
        assertTrue("photo should lead the plate", photo > plate)
        assertTrue("plate should lead the text", plate > text)
        assertEquals(0f, text, TOLERANCE)
    }

    @Test
    fun `every part is fully arrived at full open`() {
        assertEquals(1f, PlaceMorph.plateAlpha(1f), TOLERANCE)
        assertEquals(1f, PlaceMorph.textAlpha(1f), TOLERANCE)
    }

    @Test
    fun `graticule lines stay inside the plate`() {
        val fractions = PlaceMorph.graticuleFractions(17.4435)
        assertTrue("expected some graticule lines", fractions.isNotEmpty())
        fractions.forEach {
            assertTrue("line at $it is off the plate", it in 0f..1f)
        }
    }

    @Test
    fun `graticule spacing is the step, as a fraction of the span`() {
        val fractions = PlaceMorph.graticuleFractions(17.4435)
        val expectedGap = (PlaceMorph.GRATICULE_STEP_DEGREES / PlaceMorph.PLATE_SPAN_DEGREES).toFloat()
        fractions.zipWithNext { a, b -> assertEquals(expectedGap, b - a, 1e-4f) }
    }

    @Test
    fun `the grid shifts with the coordinate - it is not decoration`() {
        // Two coordinates a fraction of a step apart must produce visibly different grids;
        // if they did not, the plate would be drawing the same picture for every photo.
        val here = PlaceMorph.graticuleFractions(17.4435)
        val nearby = PlaceMorph.graticuleFractions(17.4443)
        assertTrue("expected lines on both plates", here.isNotEmpty() && nearby.isNotEmpty())
        assertTrue("the grid did not move with the coordinate", here.first() != nearby.first())
    }

    @Test
    fun `a coordinate on a whole step gets a line through the pin`() {
        // 17.44 is a whole multiple of the 0.002 step, so a line falls exactly on the
        // coordinate — which is the plate's centre, where the pin sits. The run either side
        // must then be symmetric: that is what catches an off-by-one in the low-edge
        // arithmetic, which would slide every grid half a step without changing its spacing.
        val fractions = PlaceMorph.graticuleFractions(17.44)
        assertTrue("no line through the pin", fractions.any { kotlin.math.abs(it - 0.5f) < 1e-4f })
        fractions.zip(fractions.reversed()) { low, high ->
            assertEquals(1f, low + high, 1e-4f)
        }
    }

    @Test
    fun `longitude degrees shrink towards the poles`() {
        val atEquator = PlaceMorph.metresPerDegreeLongitude(0.0)
        val atSixty = PlaceMorph.metresPerDegreeLongitude(60.0)
        assertEquals(111_320.0, atEquator, 1.0)
        // cos(60°) is exactly a half, so this is a real check rather than "smaller than".
        assertEquals(atEquator / 2.0, atSixty, 50.0)
    }

    @Test
    fun `the scale readout describes the plate at this latitude`() {
        assertEquals("1.1 km across", PlaceMorph.scaleLine(0.0))
        // The same span is a shorter distance further north, and the copy has to follow.
        assertEquals("557 m across", PlaceMorph.scaleLine(60.0))
    }

    @Test
    fun `coordinates carry their hemisphere`() {
        assertEquals("17.4435° N · 78.3772° E", PlaceMorph.coordinateLine(17.4435, 78.3772))
        assertEquals("33.8688° S · 78.3772° W", PlaceMorph.coordinateLine(-33.8688, -78.3772))
    }

    @Test
    fun `colour space is named only when the file says so`() {
        assertEquals("sRGB", colorSpaceName(1))
        assertEquals("Uncalibrated", colorSpaceName(0xFFFF))
        // -1 is ExifInterface's "tag absent" default; a guess here would be a fabricated fact.
        assertNull(colorSpaceName(-1))
        assertNull(colorSpaceName(7))
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
