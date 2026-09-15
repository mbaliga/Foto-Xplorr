package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two-finger rotation is free while the fingers are down and squared up on release, because a
 * photo left resting at seven degrees reads as a rendering fault rather than as a choice.
 *
 * The interesting cases are all at the edges of the wrap: a naive round-to-90 leaves 350 degrees
 * sitting at 360 rather than 0, and negatives at -90 rather than 270 — both of which look
 * identical on screen but make the *next* gesture animate the long way round.
 */
class SnapRotationTest {

    @Test
    fun `a barely rotated photo squares back up`() {
        assertEquals(0f, snapRotation(7f), 0.001f)
        assertEquals(0f, snapRotation(-7f), 0.001f)
        assertEquals(0f, snapRotation(0f), 0.001f)
    }

    @Test
    fun `rotation snaps to the nearest quarter turn`() {
        assertEquals(90f, snapRotation(80f), 0.001f)
        assertEquals(90f, snapRotation(100f), 0.001f)
        assertEquals(180f, snapRotation(170f), 0.001f)
        assertEquals(270f, snapRotation(260f), 0.001f)
    }

    @Test
    fun `a whole turn normalises to zero rather than to 360`() {
        // The bug this guards: 360 and 0 look the same but are not the same starting point.
        assertEquals(0f, snapRotation(358f), 0.001f)
        assertEquals(0f, snapRotation(360f), 0.001f)
        assertEquals(90f, snapRotation(450f), 0.001f)
    }

    @Test
    fun `negative rotations come back as positive equivalents`() {
        assertEquals(270f, snapRotation(-90f), 0.001f)
        assertEquals(180f, snapRotation(-180f), 0.001f)
        assertEquals(90f, snapRotation(-270f), 0.001f)
        assertEquals(0f, snapRotation(-360f), 0.001f)
    }

    @Test
    fun `every result is a canonical quarter turn`() {
        // Whatever goes in, what comes out must be one of exactly four values -- anything else
        // means the photo can rest at an angle no gesture intended.
        val allowed = setOf(0f, 90f, 180f, 270f)
        var degrees = -720f
        while (degrees <= 720f) {
            val snapped = snapRotation(degrees)
            assert(snapped in allowed) { "snapRotation($degrees) = $snapped" }
            degrees += 3.7f
        }
    }
}
