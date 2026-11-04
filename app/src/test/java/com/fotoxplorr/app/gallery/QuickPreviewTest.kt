package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPreviewTest {

    @Test
    fun `hover shows only after the delay`() {
        var s: HoverPreview = HoverPreview.Idle.onEnter(7, 1000)
        assertNull(s.onTick(1399).shownId())
        s = s.onTick(1400)
        assertEquals(7L, s.shownId())
    }

    @Test
    fun `moving to another tile restarts the wait and leaving drops it`() {
        val a = HoverPreview.Idle.onEnter(1, 0).onTick(400)
        val b = a.onEnter(2, 500)
        assertNull(b.shownId())
        assertEquals(HoverPreview.Pending(2, 500), b)
        assertEquals(HoverPreview.Idle, b.onTick(900).onDismiss())
    }

    @Test
    fun `re-entering the same tile does not restart`() {
        val p = HoverPreview.Idle.onEnter(1, 0)
        assertEquals(p, p.onEnter(1, 300))
        val shown = p.onTick(400)
        assertEquals(shown, shown.onEnter(1, 900))
    }

    @Test
    fun `remaining wait counts down and floors at zero`() {
        val p = HoverPreview.Idle.onEnter(1, 100)
        assertEquals(300L, p.remainingMs(200))
        assertEquals(0L, p.remainingMs(9999))
        assertNull(HoverPreview.Idle.remainingMs(0))
    }

    @Test
    fun `scrub frame maps x across the tile and clamps`() {
        assertEquals(0, hoverScrubFrame(0f, 100f, 10))
        assertEquals(5, hoverScrubFrame(50f, 100f, 10))
        assertEquals(9, hoverScrubFrame(100f, 100f, 10))
        assertEquals(9, hoverScrubFrame(500f, 100f, 10))
        assertEquals(0, hoverScrubFrame(-5f, 100f, 10))
        assertEquals(0, hoverScrubFrame(5f, 0f, 10))
        assertEquals(0, hoverScrubFrame(5f, 100f, 1))
    }

    @Test
    fun `space opens on the focused item and is not consumed without one`() {
        val r = QuickLook().onKey(QuickLookKey.SPACE, focused = 3, count = 10, columns = 4)
        assertEquals(3, r.state.index)
        assertTrue(r.consumed)
        assertFalse(QuickLook().onKey(QuickLookKey.SPACE, null, 10, 4).consumed)
        assertFalse(QuickLook().onKey(QuickLookKey.SPACE, 10, 10, 4).consumed)
    }

    @Test
    fun `closed Quick Look leaves arrows to the grid`() {
        assertFalse(QuickLook().onKey(QuickLookKey.RIGHT, 2, 10, 4).consumed)
    }

    @Test
    fun `arrows move by one and by a row and stop at the ends`() {
        val q = QuickLook(5)
        assertEquals(6, q.onKey(QuickLookKey.RIGHT, 5, 10, 4).state.index)
        assertEquals(4, q.onKey(QuickLookKey.LEFT, 5, 10, 4).state.index)
        assertEquals(1, q.onKey(QuickLookKey.UP, 5, 10, 4).state.index)
        assertEquals(9, q.onKey(QuickLookKey.DOWN, 5, 10, 4).state.index)
        assertEquals(9, QuickLook(9).onKey(QuickLookKey.DOWN, 9, 10, 4).state.index)
        assertEquals(0, QuickLook(0).onKey(QuickLookKey.LEFT, 0, 10, 4).state.index)
        assertEquals(5, q.onKey(QuickLookKey.DOWN, 5, 7, 4).state.index)
    }

    @Test
    fun `escape and space close, enter closes and asks for the viewer`() {
        assertFalse(QuickLook(2).onKey(QuickLookKey.ESCAPE, 2, 5, 3).state.isOpen)
        assertFalse(QuickLook(2).onKey(QuickLookKey.SPACE, 2, 5, 3).state.isOpen)
        val e = QuickLook(2).onKey(QuickLookKey.ENTER, 2, 5, 3)
        assertTrue(e.openViewer)
        assertFalse(e.state.isOpen)
    }

    @Test
    fun `a shrinking list clamps or closes`() {
        assertEquals(2, QuickLook(5).clampedTo(3).index)
        assertNull(QuickLook(5).clampedTo(0).index)
        assertEquals(1, QuickLook(1).clampedTo(3).index)
    }

    @Test
    fun `preview pane needs width and the toggle`() {
        assertFalse(previewPaneShown(599, true))
        assertTrue(previewPaneShown(600, true))
        assertFalse(previewPaneShown(900, false))
        assertTrue(previewPaneOffered(600))
        assertFalse(previewPaneOffered(411))
    }

    @Test
    fun `pane item prefers focus then selection and stays in range`() {
        assertEquals(2, previewPaneItem(2, 5, 10))
        assertEquals(5, previewPaneItem(null, 5, 10))
        assertEquals(5, previewPaneItem(40, 5, 10))
        assertNull(previewPaneItem(null, null, 10))
        assertNull(previewPaneItem(1, 1, 0))
    }
}
