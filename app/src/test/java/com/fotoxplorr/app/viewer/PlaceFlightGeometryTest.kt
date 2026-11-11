package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Rect
import dev.aarso.cellshell.SpatialMotion
import dev.aarso.cellshell.parkDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shrink-to-map flight is a pure function of the shell's progress, so every property that
 * makes it look right -- starts exactly on the photo, ends exactly on the pin, never jumps,
 * reverses -- is asserted here without a device.
 */
class PlaceFlightGeometryTest {

    // A phone-ish shell, in px: 1080 x 2400 with a 72 dp band at 3x.
    private val width = 1080f
    private val height = 2400f
    private val band = 216f

    // A landscape photo fitted into it, and a pin in the room 300 px down on the left.
    private val photo = PlaceMorph.fitRect(width, height, 4000f, 3000f)
    private val pinInRoom = Rect(500f, 1000f, 740f, 1240f)

    // ── fitRect ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fitRect letterboxes a landscape image and centres it`() {
        val fit = PlaceMorph.fitRect(1000f, 2000f, 400f, 300f)
        assertEquals(1000f, fit.width, EPS)
        assertEquals(750f, fit.height, EPS)
        assertEquals(0f, fit.left, EPS)
        assertEquals(625f, fit.top, EPS)
    }

    @Test
    fun `fitRect pillarboxes a portrait image and centres it`() {
        val fit = PlaceMorph.fitRect(1000f, 1000f, 300f, 400f)
        assertEquals(750f, fit.width, EPS)
        assertEquals(1000f, fit.height, EPS)
        assertEquals(125f, fit.left, EPS)
        assertEquals(0f, fit.top, EPS)
    }

    @Test
    fun `fitRect keeps the image's aspect for every shape`() {
        for ((iw, ih) in listOf(400f to 300f, 300f to 400f, 500f to 500f, 4000f to 100f, 100f to 4000f)) {
            val fit = PlaceMorph.fitRect(1080f, 2400f, iw, ih)
            assertEquals("aspect of ${iw}x$ih", iw / ih, fit.width / fit.height, 1e-3f)
            // Centred, and inside the container.
            assertEquals((1080f - fit.width) / 2f, fit.left, EPS)
            assertEquals((2400f - fit.height) / 2f, fit.top, EPS)
            assertTrue(fit.width <= 1080f + EPS && fit.height <= 2400f + EPS)
        }
    }

    @Test
    fun `fitRect on a degenerate image is the container, never NaN`() {
        val container = Rect(0f, 0f, 1080f, 2400f)
        assertEquals(container, PlaceMorph.fitRect(1080f, 2400f, 0f, 300f))
        assertEquals(container, PlaceMorph.fitRect(1080f, 2400f, 400f, 0f))
        assertEquals(container, PlaceMorph.fitRect(1080f, 2400f, Float.NaN, 300f))
        assertEquals(Rect.Zero, PlaceMorph.fitRect(0f, 2400f, 400f, 300f))
    }

    // ── parkedCardRect ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a closed shell leaves the photo exactly where it is`() {
        assertRect(photo, PlaceMorph.parkedCardRect(photo, width, height, 0f, band))
    }

    @Test
    fun `a fully open shell parks the card at 90 percent, centre-scaled and slid down`() {
        val scale = 1f - SpatialMotion.PARK_SCALE_DROP
        val parked = PlaceMorph.parkedCardRect(photo, width, height, 1f, band)
        val slide = parkDistance(height, scale, band)
        assertEquals(photo.width * scale, parked.width, EPS)
        assertEquals(photo.height * scale, parked.height, EPS)
        // Scaled about the card's centre, then moved by the shell's own park distance.
        assertEquals(height / 2f + (photo.top - height / 2f) * scale + slide, parked.top, EPS)
        assertEquals(width / 2f + (photo.left - width / 2f) * scale, parked.left, EPS)
    }

    @Test
    fun `the parked card only ever moves down and shrinks as the pull grows`() {
        var previous = PlaceMorph.parkedCardRect(photo, width, height, 0f, band)
        var v = 0.02f
        while (v <= 1f) {
            val now = PlaceMorph.parkedCardRect(photo, width, height, v, band)
            assertTrue("card rose at $v", now.top >= previous.top - EPS)
            assertTrue("card grew at $v", now.width <= previous.width + EPS)
            previous = now
            v += 0.02f
        }
    }

    @Test
    fun `parkedCardRect clamps a progress outside the unit range`() {
        assertRect(photo, PlaceMorph.parkedCardRect(photo, width, height, -0.5f, band))
        assertRect(
            PlaceMorph.parkedCardRect(photo, width, height, 1f, band),
            PlaceMorph.parkedCardRect(photo, width, height, 1.5f, band),
        )
    }

    // ── lerpRect ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `lerpRect hits both ends and the midpoint`() {
        val a = Rect(0f, 10f, 100f, 110f)
        val b = Rect(40f, 50f, 60f, 70f)
        assertEquals(a, PlaceMorph.lerpRect(a, b, 0f))
        assertEquals(b, PlaceMorph.lerpRect(a, b, 1f))
        assertEquals(Rect(20f, 30f, 80f, 90f), PlaceMorph.lerpRect(a, b, 0.5f))
    }

    // ── flightT / handoff ──────────────────────────────────────────────────────────────────

    @Test
    fun `the flight starts at zero, ends at one and never goes backwards`() {
        assertEquals(0f, PlaceMorph.flightT(0f), EPS)
        assertEquals(1f, PlaceMorph.flightT(1f), EPS)
        var previous = -1f
        var v = 0f
        while (v <= 1f) {
            val t = PlaceMorph.flightT(v)
            assertTrue("flight went backwards at $v", t >= previous)
            previous = t
            v += 0.01f
        }
    }

    @Test
    fun `the flight has no slope at either end so there is no jump into the hand-off`() {
        val step = 0.005f
        assertTrue(PlaceMorph.flightT(step) < 0.001f)
        assertTrue(1f - PlaceMorph.flightT(1f - step) < 0.001f)
    }

    @Test
    fun `the flier is fully opaque until the hand-off and gone at full open`() {
        assertEquals(1f, PlaceMorph.handoff(0f), EPS)
        assertEquals(1f, PlaceMorph.handoff(0.5f), EPS)
        assertEquals(1f, PlaceMorph.handoff(0.96f), EPS)
        assertEquals(0f, PlaceMorph.handoff(1f), EPS)
        assertTrue(PlaceMorph.handoff(0.98f) in 0.01f..0.99f)
        var previous = 2f
        var v = 0f
        while (v <= 1f) {
            val a = PlaceMorph.handoff(v)
            assertTrue("hand-off rose at $v", a <= previous)
            previous = a
            v += 0.01f
        }
    }

    @Test
    fun `exactly one copy is opaque at every progress - the real pin is under the flier before it fades`() {
        var v = 0f
        while (v <= 1f) {
            val flier = PlaceMorph.handoff(v)
            val pin = if (PlaceMorph.realPinVisible(v)) 1f else 0f
            // Never a gap: wherever the flier is partly transparent the pin is fully there.
            assertTrue("gap at $v", flier >= 1f - EPS || pin == 1f)
            // Never two opaque copies fighting: the pin is only visible once the flier is fading.
            assertTrue("double draw at $v", pin == 0f || flier <= 1f)
            v += 0.005f
        }
        assertFalse(PlaceMorph.realPinVisible(0.5f))
        assertTrue(PlaceMorph.realPinVisible(1f))
    }

    @Test
    fun `the pointer comes in only as the flier goes out`() {
        assertEquals(0f, PlaceMorph.pinPointerGate(PlaceMorph.PinMode.FLIGHT, 0.5f), EPS)
        assertEquals(0f, PlaceMorph.pinPointerGate(PlaceMorph.PinMode.FLIGHT, 0.96f), EPS)
        assertEquals(1f, PlaceMorph.pinPointerGate(PlaceMorph.PinMode.FLIGHT, 1f), EPS)
        // Other modes never gate it.
        assertEquals(1f, PlaceMorph.pinPointerGate(PlaceMorph.PinMode.HERO, 0.5f), EPS)
        assertEquals(1f, PlaceMorph.pinPointerGate(PlaceMorph.PinMode.FADE, 0.5f), EPS)
    }

    // ── the pin target ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the pin slides rigidly with the room - analytic position`() {
        // pinY(v) = restY - height * (1 - v): the shell's placement offset, exactly.
        assertRect(pinInRoom, PlaceMorph.pinRectAt(pinInRoom, height, 1f))
        val half = PlaceMorph.pinRectAt(pinInRoom, height, 0.5f)
        assertEquals(pinInRoom.top - height * 0.5f, half.top, EPS)
        assertEquals(pinInRoom.left, half.left, EPS)
        assertEquals(pinInRoom.width, half.width, EPS)
        val closed = PlaceMorph.pinRectAt(pinInRoom, height, 0f)
        assertEquals(pinInRoom.top - height, closed.top, EPS)
    }

    @Test
    fun `a pin is reachable only if the whole tile is inside the room`() {
        val roomHeight = height - band
        assertTrue(PlaceMorph.canFly(pinInRoom, width, roomHeight))
        // Scrolled up and partly out of the top.
        assertFalse(PlaceMorph.canFly(pinInRoom.translate(0f, -1100f), width, roomHeight))
        // Scrolled so far it is gone.
        assertFalse(PlaceMorph.canFly(pinInRoom.translate(0f, -3000f), width, roomHeight))
        // Pushed under the band at the bottom.
        assertFalse(PlaceMorph.canFly(pinInRoom.translate(0f, 1000f), width, roomHeight))
        // An empty rect is not a target.
        assertFalse(PlaceMorph.canFly(Rect.Zero, width, roomHeight))
    }

    // ── the frame ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the flier starts exactly on the photo and lands exactly on the pin`() {
        val start = PlaceMorph.flightRect(photo, pinInRoom, width, height, band, 0f)
        assertRect(photo, start)
        val end = PlaceMorph.flightRect(photo, pinInRoom, width, height, band, 1f)
        // Pixel-exact, so the swap to the real pin cannot be seen.
        assertEquals(pinInRoom, end)  // exact, not approximately
    }

    @Test
    fun `the flier shrinks monotonically from the photo's size to the pin's`() {
        var previous = Float.MAX_VALUE
        var v = 0f
        while (v <= 1f) {
            val rect = PlaceMorph.flightRect(photo, pinInRoom, width, height, band, v)
            assertTrue("flier grew at $v", rect.width <= previous + 0.5f)
            previous = rect.width
            v += 0.02f
        }
    }

    @Test
    fun `the flier stays on screen for the whole pull`() {
        // The thing the earlier morph got wrong was happening where nobody could see it.
        var v = 0.01f
        while (v <= 1f) {
            val rect = PlaceMorph.flightRect(photo, pinInRoom, width, height, band, v)
            val centreY = (rect.top + rect.bottom) / 2f
            assertTrue("flier centre off screen at $v: $centreY", centreY in 0f..height)
            v += 0.01f
        }
    }

    @Test
    fun `no frame while closed, when the pin is unreachable, or without a photo`() {
        assertNull(PlaceMorph.flightFrame(photo, pinInRoom, width, height, band, 0f, 3f))
        assertNull(PlaceMorph.flightFrame(photo, pinInRoom.translate(0f, -1500f), width, height, band, 0.5f, 3f))
        assertNull(PlaceMorph.flightFrame(Rect.Zero, pinInRoom, width, height, band, 0.5f, 3f))
        assertNotNull(PlaceMorph.flightFrame(photo, pinInRoom, width, height, band, 0.5f, 3f))
    }

    @Test
    fun `the frame at full open is the pin tile, framed, with the flier handed off`() {
        val frame = PlaceMorph.flightFrame(photo, pinInRoom, width, height, band, 1f, 3f)!!
        assertEquals(pinInRoom, frame.outer)
        // The photo sits inside the 2 dp border (6 px at 3x) the real pin draws.
        assertRect(pinInRoom.deflate(6f), frame.photo)
        assertEquals(1f, frame.frameAlpha, EPS)
        assertEquals(PlaceMorph.PIN_CORNER_DP * 3f, frame.outerCornerPx, EPS)
        assertEquals(0f, frame.alpha, EPS)
    }

    @Test
    fun `the frame starts borderless and square-cornered like the photo it is`() {
        val frame = PlaceMorph.flightFrame(photo, pinInRoom, width, height, band, 0.01f, 3f)!!
        assertEquals(1f, frame.alpha, EPS)
        assertTrue(frame.frameAlpha < 0.01f)
        assertTrue(frame.outerCornerPx < 0.5f)
        // Photo and outer agree to well under a pixel.
        assertEquals(frame.outer.width, frame.photo.width, 0.1f)
    }

    // ── cropPlacement ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the crop shows the whole photo when the target has the photo's shape`() {
        val target = Rect(100f, 200f, 100f + 400f, 200f + 300f)
        val placement = PlaceMorph.cropPlacement(400f, 300f, target)!!
        assertEquals(1f, placement.scale, EPS)
        assertEquals(Rect(0f, 0f, 400f, 300f), placement.window)
        assertEquals(100f, placement.translationX, EPS)
        assertEquals(200f, placement.translationY, EPS)
    }

    @Test
    fun `the crop to a square takes the centre of a landscape photo, uniformly scaled`() {
        val target = Rect(500f, 1000f, 580f, 1080f)
        val placement = PlaceMorph.cropPlacement(400f, 300f, target)!!
        // A 300 x 300 window from the middle, scaled to 80: uniform, so nothing is squashed.
        assertEquals(300f, placement.window.width, EPS)
        assertEquals(300f, placement.window.height, EPS)
        assertEquals(50f, placement.window.left, EPS)
        assertEquals(80f / 300f, placement.scale, EPS)
        // The window's top-left lands on the target's.
        assertEquals(target.left, placement.translationX + placement.scale * placement.window.left, EPS)
        assertEquals(target.top, placement.translationY + placement.scale * placement.window.top, EPS)
    }

    @Test
    fun `the crop to a square takes the centre band of a portrait photo`() {
        val target = Rect(0f, 0f, 80f, 80f)
        val placement = PlaceMorph.cropPlacement(300f, 400f, target)!!
        assertEquals(300f, placement.window.width, EPS)
        assertEquals(300f, placement.window.height, EPS)
        assertEquals(50f, placement.window.top, EPS)
    }

    @Test
    fun `the window always fits inside the photo and keeps the target's aspect`() {
        var v = 0.01f
        while (v <= 1f) {
            val frame = PlaceMorph.flightFrame(photo, pinInRoom, width, height, band, v, 3f)!!
            val placement = PlaceMorph.cropPlacement(photo.width, photo.height, frame.photo)!!
            val window = placement.window
            assertTrue("window outside photo at $v", window.left >= -EPS && window.top >= -EPS)
            assertTrue("window outside photo at $v", window.right <= photo.width + EPS && window.bottom <= photo.height + EPS)
            assertEquals(frame.photo.width / frame.photo.height, window.width / window.height, 1e-3f)
            // And the scaled window IS the target.
            assertEquals(frame.photo.width, window.width * placement.scale, 0.01f)
            v += 0.05f
        }
    }

    @Test
    fun `a degenerate crop is null rather than NaN`() {
        assertNull(PlaceMorph.cropPlacement(0f, 300f, Rect(0f, 0f, 80f, 80f)))
        assertNull(PlaceMorph.cropPlacement(400f, 300f, Rect.Zero))
    }

    // ── small helpers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `landed dim is the ghost only for a photo with no place`() {
        assertEquals(0f, PlaceMorph.landedDim(placeKnown = true), EPS)
        assertEquals(PlaceMorph.GHOST_DIM, PlaceMorph.landedDim(placeKnown = false), EPS)
    }

    @Test
    fun `sameRect tolerates sub-pixel drift but not a real move`() {
        val a = Rect(10f, 20f, 90f, 100f)
        assertTrue(PlaceMorph.sameRect(a, a.translate(0.3f, -0.3f)))
        assertFalse(PlaceMorph.sameRect(a, a.translate(0f, 2f)))
        assertTrue(PlaceMorph.sameRect(null, null))
        assertFalse(PlaceMorph.sameRect(a, null))
    }

    private fun assertRect(expected: Rect, actual: Rect, message: String = "") {
        assertEquals("$message left", expected.left, actual.left, EPS)
        assertEquals("$message top", expected.top, actual.top, EPS)
        assertEquals("$message right", expected.right, actual.right, EPS)
        assertEquals("$message bottom", expected.bottom, actual.bottom, EPS)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
