package com.fotoxplorr.app.editor

import kotlin.math.tan
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto-fix, checked against images whose correct answer is known by construction.
 *
 * Synthetic pixels rather than sample photographs: a flat grey image, a washed-out one and one
 * with a deliberate blue cast have answers that can be asserted exactly, where a real photo only
 * has opinions. The property that matters most is the negative one — a good photo must be offered
 * nothing, because an auto-fix that always finds work trains people to ignore it.
 */
class AutoFixTest {

    /** An image whose luminance is spread evenly across [low]..[high] (0..255). */
    private fun ramp(low: Int, high: Int, count: Int = 4096): IntArray =
        IntArray(count) { index ->
            val value = low + ((high - low) * index / (count - 1))
            argb(value, value, value)
        }

    private fun flat(r: Int, g: Int, b: Int, count: Int = 4096) = IntArray(count) { argb(r, g, b) }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    @Test
    fun `a full-range image is measured as using the whole range`() {
        val analysis = AutoFix.analyse(ramp(0, 255))

        assertTrue("black point should sit near 0, was ${analysis.blackPoint}", analysis.blackPoint < 0.05f)
        assertTrue("white point should sit near 1, was ${analysis.whitePoint}", analysis.whitePoint > 0.95f)
        assertEquals(0.5f, analysis.medianLuminance, 0.05f)
    }

    @Test
    fun `a washed-out image is detected as flat and offered a tone fix`() {
        // Nothing darker than 90 or brighter than 170: the classic hazy, low-contrast photo.
        val analysis = AutoFix.analyse(ramp(90, 170))
        val suggestions = AutoFix.suggestionsFor(analysis)

        val tone = suggestions.single { it.id == AutoFix.Suggestion.Id.TONE }
        assertTrue("should push whites up", tone.adjustments.whites > 0f)
        assertTrue("should pull blacks down", tone.adjustments.blacks < 0f)
        assertTrue(tone.reason.contains("Flat"))
    }

    @Test
    fun `a well-exposed neutral photo is offered no tone or colour fix`() {
        // The negative case, and the important one.
        val analysis = AutoFix.analyse(ramp(2, 253))
        val suggestions = AutoFix.suggestionsFor(analysis)

        assertFalse(
            "a good photo must not be offered a tone fix",
            suggestions.any { it.id == AutoFix.Suggestion.Id.TONE },
        )
        assertFalse(
            "a neutral photo must not be offered a colour fix",
            suggestions.any { it.id == AutoFix.Suggestion.Id.WHITE_BALANCE },
        )
    }

    @Test
    fun `a blue cast is corrected by warming, not by cooling further`() {
        // Blue-heavy: the correction must move temperature POSITIVE (towards amber).
        val analysis = AutoFix.analyse(flat(r = 90, g = 110, b = 180))
        val suggestions = AutoFix.suggestionsFor(analysis)

        val wb = suggestions.single { it.id == AutoFix.Suggestion.Id.WHITE_BALANCE }
        assertTrue("blue cast should warm the image, got ${wb.adjustments.temperature}", wb.adjustments.temperature > 0f)
        assertTrue(wb.reason.contains("Cool"))
    }

    @Test
    fun `a warm cast is corrected by cooling`() {
        val analysis = AutoFix.analyse(flat(r = 190, g = 130, b = 90))
        val wb = AutoFix.suggestionsFor(analysis).single { it.id == AutoFix.Suggestion.Id.WHITE_BALANCE }

        assertTrue("warm cast should cool the image", wb.adjustments.temperature < 0f)
        assertTrue(wb.reason.contains("Warm"))
    }

    @Test
    fun `a dark photo is brightened and a bright one is pulled down`() {
        val dark = AutoFix.suggestionsFor(AutoFix.analyse(ramp(0, 90)))
            .single { it.id == AutoFix.Suggestion.Id.TONE }
        assertTrue("a dark photo should gain exposure", dark.adjustments.exposure > 0f)

        val bright = AutoFix.suggestionsFor(AutoFix.analyse(ramp(170, 255)))
            .single { it.id == AutoFix.Suggestion.Id.TONE }
        assertTrue("a bright photo should lose exposure", bright.adjustments.exposure < 0f)
    }

    @Test
    fun `exposure is bounded so auto-fix improves rather than redevelops`() {
        // A nearly-black frame must not be pushed six stops into noise.
        val veryDark = AutoFix.suggestionsFor(AutoFix.analyse(flat(12, 12, 12)))
            .single { it.id == AutoFix.Suggestion.Id.TONE }
        assertTrue("exposure must stay bounded, was ${veryDark.adjustments.exposure}", veryDark.adjustments.exposure <= 1.21f)
    }

    @Test
    fun `suggestions build on what the user already did`() {
        // Taking a colour fix must not silently undo their crop-independent tone work.
        val current = Adjustments.NONE.copy(saturation = 0.5f)
        val wb = AutoFix.suggestionsFor(AutoFix.analyse(flat(90, 110, 180)), current)
            .single { it.id == AutoFix.Suggestion.Id.WHITE_BALANCE }

        assertEquals(0.5f, wb.adjustments.saturation, 0.0001f)
    }

    @Test
    fun `an empty image does not divide by zero`() {
        val analysis = AutoFix.analyse(IntArray(0))
        assertEquals(0.5f, analysis.medianLuminance, 0.0001f)
        // And produces no nonsense offers.
        AutoFix.suggestionsFor(analysis)
    }

    // ---- straighten: Suggestion wiring ----

    @Test
    fun `a horizon correction below the offer threshold is not surfaced`() {
        val suggestions = AutoFix.suggestionsFor(AutoFix.analyse(ramp(2, 253)), horizonDegrees = 0.1f)
        assertFalse(suggestions.any { it.id == AutoFix.Suggestion.Id.STRAIGHTEN })
    }

    @Test
    fun `a null horizon produces no straighten offer`() {
        val suggestions = AutoFix.suggestionsFor(AutoFix.analyse(ramp(2, 253)), horizonDegrees = null)
        assertFalse(suggestions.any { it.id == AutoFix.Suggestion.Id.STRAIGHTEN })
    }

    @Test
    fun `a horizon correction above the threshold is offered unchanged and does not disturb adjustments`() {
        val current = Adjustments.NONE.copy(saturation = 0.4f)
        val suggestions = AutoFix.suggestionsFor(AutoFix.analyse(ramp(2, 253)), current, horizonDegrees = 4f)

        val straighten = suggestions.single { it.id == AutoFix.Suggestion.Id.STRAIGHTEN }
        assertEquals(4f, straighten.straightenDegrees!!, 0.0001f)
        assertTrue(straighten.reason.isNotBlank())
        // A straighten offer must not silently reset colour work the user already kept: it rides
        // on top of `current` unmodified, because straighten and Adjustments are unrelated fields.
        assertEquals(0.4f, straighten.adjustments.saturation, 0.0001f)

        // And every other suggestion's straightenDegrees stays null -- the field is exclusive to
        // the STRAIGHTEN id, not a general-purpose slot the other three could accidentally fill.
        suggestions.filter { it.id != AutoFix.Suggestion.Id.STRAIGHTEN }.forEach {
            assertNull("only STRAIGHTEN should set straightenDegrees", it.straightenDegrees)
        }
    }

    // ---- straighten: detectHorizon ----

    /**
     * A synthetic "horizon" tilted by [tiltDegrees]: bright above the line `y = x * tan(tilt)`
     * (in coordinates centred on the image), dark below it. At tilt 0 this is a perfectly
     * horizontal edge across the middle of the image; small non-zero tilts are exactly what a
     * levelled-but-not-quite photograph looks like, which is the case this function has to get
     * both the magnitude AND the sign right for.
     */
    private fun tiltedEdgeImage(size: Int, tiltDegrees: Float, bright: Int = 220, dark: Int = 40): IntArray {
        val centre = (size - 1) / 2.0
        val slope = tan(Math.toRadians(tiltDegrees.toDouble()))
        return IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val isBright = (y - centre) < (x - centre) * slope
            val v = if (isBright) bright else dark
            argb(v, v, v)
        }
    }

    /** Independent random luminance per pixel: plenty of strong local gradients, no shared angle. */
    private fun noiseImage(size: Int, seed: Long = 42L): IntArray {
        val random = Random(seed)
        return IntArray(size * size) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
    }

    @Test
    fun `a clean tilted horizon is detected with approximately the right magnitude`() {
        val correction = AutoFix.detectHorizon(tiltedEdgeImage(HORIZON_TEST_SIZE, 6f), HORIZON_TEST_SIZE, HORIZON_TEST_SIZE)
        assertTrue("expected a correction, got null", correction != null)
        assertEquals(-6f, correction!!, 1.5f)
    }

    @Test
    fun `the correction sign flips with the tilt's sign`() {
        // The classic bug this guards against: levelling a photo tilted one way by rotating it
        // FURTHER the same way, which makes a slightly crooked horizon badly crooked.
        val leaningRight = AutoFix.detectHorizon(tiltedEdgeImage(HORIZON_TEST_SIZE, 6f), HORIZON_TEST_SIZE, HORIZON_TEST_SIZE)
        val leaningLeft = AutoFix.detectHorizon(tiltedEdgeImage(HORIZON_TEST_SIZE, -6f), HORIZON_TEST_SIZE, HORIZON_TEST_SIZE)

        assertTrue(leaningRight != null && leaningLeft != null)
        assertTrue("a +6 degree tilt must correct negative, got $leaningRight", leaningRight!! < 0f)
        assertTrue("a -6 degree tilt must correct positive, got $leaningLeft", leaningLeft!! > 0f)
        // And symmetric in magnitude, since the two images are mirror images of each other.
        assertEquals(-leaningRight, leaningLeft, 1.5f)
    }

    @Test
    fun `an already-level image needs no correction`() {
        val correction = AutoFix.detectHorizon(tiltedEdgeImage(HORIZON_TEST_SIZE, 0f), HORIZON_TEST_SIZE, HORIZON_TEST_SIZE)
        assertNull("a perfectly level edge must not be offered a correction", correction)
    }

    @Test
    fun `an image with no dominant edge direction offers no correction`() {
        // The negative case that matters as much as the positive one: gravel, foliage, a crowd --
        // real texture that must not be mistaken for a horizon.
        val correction = AutoFix.detectHorizon(noiseImage(HORIZON_TEST_SIZE), HORIZON_TEST_SIZE, HORIZON_TEST_SIZE)
        assertNull("scattered edge directions must not be offered a straighten correction", correction)
    }

    @Test
    fun `a flat image with no edges at all offers no correction`() {
        val flatPixels = IntArray(HORIZON_TEST_SIZE * HORIZON_TEST_SIZE) { argb(128, 128, 128) }
        assertNull(AutoFix.detectHorizon(flatPixels, HORIZON_TEST_SIZE, HORIZON_TEST_SIZE))
    }

    @Test
    fun `detectHorizon does not crash on degenerate input`() {
        assertNull(AutoFix.detectHorizon(IntArray(0), 0, 0))
        assertNull(AutoFix.detectHorizon(IntArray(4), 2, 2))
    }

    private companion object {
        /** Big enough that a full-width tilted edge clears MIN_HORIZON_STRONG_PIXELS comfortably. */
        const val HORIZON_TEST_SIZE = 90
    }
}
