package com.fotoxplorr.app.viewer.colour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProOverlayChoiceTest {
    @Test
    fun `all off by default`() {
        val c = ProOverlayChoice()
        assertFalse(c.anyOn)
        assertFalse(c.needsPixels)
    }

    @Test
    fun `info alone needs no pixels, the others do`() {
        assertFalse(ProOverlayChoice().with(ProOverlay.INFO, true).needsPixels)
        for (o in listOf(ProOverlay.HISTOGRAM, ProOverlay.CLIPPING, ProOverlay.PEAKING)) {
            assertTrue(o.name, ProOverlayChoice().with(o, true).needsPixels)
        }
    }

    @Test
    fun `with and has round trip for every overlay`() {
        for (o in ProOverlay.values()) {
            val on = ProOverlayChoice().with(o, true)
            assertTrue(on.has(o))
            assertEquals(ProOverlayChoice(), on.with(o, false))
        }
    }

    @Test
    fun `captions are one short sentence and every target has a label`() {
        for (o in ProOverlay.values()) {
            assertTrue(o.caption, o.caption.endsWith(".") && o.caption.count { it == '.' } == 1 && o.caption.length <= 70)
            assertTrue(o.label.isNotBlank())
        }
    }
}
