package com.fotoxplorr.app.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's keyboard shortcut table -- see [viewerShortcutFor]. */
class ViewerShortcutsTest {

    @Test
    fun `left and right page backward and forward`() {
        assertEquals(ViewerShortcut.Page(forward = false), viewerShortcutFor(ViewerShortcutKey.ARROW_LEFT))
        assertEquals(ViewerShortcut.Page(forward = true), viewerShortcutFor(ViewerShortcutKey.ARROW_RIGHT))
    }

    @Test
    fun `plus and minus zoom, zero resets, escape closes`() {
        assertEquals(ViewerShortcut.ZoomIn, viewerShortcutFor(ViewerShortcutKey.PLUS))
        assertEquals(ViewerShortcut.ZoomOut, viewerShortcutFor(ViewerShortcutKey.MINUS))
        assertEquals(ViewerShortcut.ResetZoom, viewerShortcutFor(ViewerShortcutKey.ZERO))
        assertEquals(ViewerShortcut.Close, viewerShortcutFor(ViewerShortcutKey.ESCAPE))
    }

    @Test
    fun `every key in the enum is bound`() {
        ViewerShortcutKey.entries.forEach { assertTrue("$it unbound", viewerShortcutFor(it) != null) }
    }

    @Test
    fun `zoom in and out are exact reciprocals and other shortcuts have no factor`() {
        assertEquals(VIEWER_KEY_ZOOM_STEP, viewerZoomFactorFor(ViewerShortcut.ZoomIn)!!, 0f)
        assertEquals(1f, viewerZoomFactorFor(ViewerShortcut.ZoomIn)!! * viewerZoomFactorFor(ViewerShortcut.ZoomOut)!!, 1e-6f)
        assertNull(viewerZoomFactorFor(ViewerShortcut.ResetZoom))
        assertNull(viewerZoomFactorFor(ViewerShortcut.Close))
        assertNull(viewerZoomFactorFor(ViewerShortcut.Page(true)))
    }

    @Test
    fun `wheel up zooms in, wheel down zooms out, no travel is neutral`() {
        assertTrue(viewerWheelZoomFactor(-1f) > 1f)
        assertTrue(viewerWheelZoomFactor(1f) < 1f)
        assertEquals(1f, viewerWheelZoomFactor(0f), 0f)
    }
}
