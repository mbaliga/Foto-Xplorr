package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The non-drag pan of compare/survey: after zooming in with the + button, the arrows (buttons or
 * keys) must reach every corner of the shared view, the way a drag does.
 */
class SurveyPanTest {
    private val w = 400f
    private val h = 300f

    private fun zoomedIn(times: Int = 3): SyncedView {
        var v = SyncedView()
        repeat(times) { v = v.stepZoom(true, w, h) }
        return v
    }

    @Test
    fun `nothing pans at zoom 1`() {
        PanDirection.entries.forEach {
            val v = SyncedView().stepPan(it, w, h)
            assertEquals(1f, v.zoom, 0f)
            assertEquals(0f, v.panX, 0f)
            assertEquals(0f, v.panY, 0f)
        }
    }

    @Test
    fun `looking left brings the photo's left edge in`() {
        val v = zoomedIn().stepPan(PanDirection.LEFT, w, h)
        assertEquals(w * PAN_STEP_OF_CELL, v.panX, 0.01f)
        assertEquals(0f, v.panY, 0.01f)
        assertTrue(zoomedIn().stepPan(PanDirection.UP, w, h).panY > 0f)
        assertTrue(zoomedIn().stepPan(PanDirection.RIGHT, w, h).panX < 0f)
        assertTrue(zoomedIn().stepPan(PanDirection.DOWN, w, h).panY < 0f)
    }

    @Test
    fun `the top-left corner is reachable with buttons alone and the pan stays clamped`() {
        var v = zoomedIn()
        repeat(40) {
            v = v.stepPan(PanDirection.LEFT, w, h).stepPan(PanDirection.UP, w, h)
        }
        val maxX = w * (v.zoom - 1f) / 2f
        val maxY = h * (v.zoom - 1f) / 2f
        assertEquals(maxX, v.panX, 0.01f)
        assertEquals(maxY, v.panY, 0.01f)
    }

    @Test
    fun `every pan control has its own sentence`() {
        val all = listOf(SurveyCopy.PAN_LEFT, SurveyCopy.PAN_RIGHT, SurveyCopy.PAN_UP, SurveyCopy.PAN_DOWN)
        assertEquals(4, all.toSet().size)
        all.forEach { assertTrue(it, it.endsWith(".")) }
    }
}
