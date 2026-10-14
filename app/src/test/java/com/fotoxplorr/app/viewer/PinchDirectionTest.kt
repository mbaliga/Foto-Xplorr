package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.adaptive.PINCH_STEP_THRESHOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

/**
 * [ViewerPinchMode.PANEL_SHORTCUTS]'s own judgement call: once a whole gesture's log-scale pinch
 * is in, which panel (if any) it means.
 *
 * Pure, like [SnapRotationTest] and [com.fotoxplorr.app.adaptive.GalleryZoomLadderTest] beside it
 * -- the actual pointer-input loop this feeds is not independently exercised here (see
 * `detectViewerGestures`'s own doc: it needs a live `PointerInputScope`, the same reason no test
 * in this codebase drives that detector directly), but the one decision worth pinning without a
 * device is checked directly: which sign, and how much of it, means which direction.
 */
class PinchDirectionTest {

    @Test
    fun `fingers moved together nets a negative log and means pinch IN`() {
        // ln(0.5) is well past the threshold in the negative direction.
        assertEquals(PinchDirection.IN, resolvePinchDirection(ln(0.5f)))
    }

    @Test
    fun `fingers spread apart nets a positive log and means pinch OUT`() {
        assertEquals(PinchDirection.OUT, resolvePinchDirection(ln(2f)))
    }

    @Test
    fun `a pinch too small to cross the threshold either way means nothing`() {
        assertNull(resolvePinchDirection(0f))
        assertNull(resolvePinchDirection(PINCH_STEP_THRESHOLD * 0.5f))
        assertNull(resolvePinchDirection(-PINCH_STEP_THRESHOLD * 0.5f))
    }

    @Test
    fun `crossing the threshold exactly counts, in both directions`() {
        assertEquals(PinchDirection.OUT, resolvePinchDirection(PINCH_STEP_THRESHOLD))
        assertEquals(PinchDirection.IN, resolvePinchDirection(-PINCH_STEP_THRESHOLD))
    }

    @Test
    fun `a custom threshold is honoured instead of the default`() {
        assertNull(resolvePinchDirection(0.05f, threshold = 0.1f))
        assertEquals(PinchDirection.OUT, resolvePinchDirection(0.2f, threshold = 0.1f))
        assertEquals(PinchDirection.IN, resolvePinchDirection(-0.2f, threshold = 0.1f))
    }
}
