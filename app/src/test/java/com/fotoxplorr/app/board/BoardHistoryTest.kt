package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardHistoryTest {

    private val start = board(layer(1, 100f, 100f))
    private fun at(x: Float) = board(layer(1, x, 100f))

    @Test
    fun `a fresh history has nothing to undo or redo`() {
        val h = BoardHistory.of(start)
        assertSame(start, h.current)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertSame(h, h.undo())
        assertSame(h, h.redo())
    }

    @Test
    fun `undo and redo step through the commits`() {
        var h = BoardHistory.of(start).commit(at(200f)).commit(at(300f))
        assertEquals(2, h.undoDepth)
        h = h.undo()
        assertEquals(at(200f), h.current)
        h = h.undo()
        assertEquals(start, h.current)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        h = h.redo().redo()
        assertEquals(at(300f), h.current)
        assertFalse(h.canRedo)
    }

    @Test
    fun `a commit identical to the current board is not recorded`() {
        val h = BoardHistory.of(start)
        assertSame(h, h.commit(start))
        assertSame(h, h.commit(start.copy()))
    }

    @Test
    fun `a new commit after an undo drops the redo tail`() {
        var h = BoardHistory.of(start).commit(at(200f)).commit(at(300f)).undo()
        h = h.commit(at(250f))
        assertFalse(h.canRedo)
        assertEquals(at(250f), h.current)
        assertEquals(at(200f), h.undo().current)
    }

    @Test
    fun `commits with the same key are one step, so a drag is a single undo`() {
        var h = BoardHistory.of(start)
        for (x in listOf(110f, 120f, 130f, 140f)) h = h.commit(at(x), mergeKey = "move:1")
        assertEquals(1, h.undoDepth)
        assertEquals(at(140f), h.current)
        assertEquals(start, h.undo().current)
    }

    @Test
    fun `a different key, no key, an undo or a redo each end the run`() {
        var h = BoardHistory.of(start).commit(at(110f), "a").commit(at(120f), "b")
        assertEquals(2, h.undoDepth)
        h = h.commit(at(130f), "b").commit(at(140f))
        assertEquals(3, h.undoDepth)
        h = h.commit(at(150f), "b")
        assertEquals(4, h.undoDepth)
        // After an undo the next commit with the same key is a new step, not a rewrite of the past.
        val u = h.undo().commit(at(160f), "b")
        assertEquals(4, u.undoDepth)
        assertEquals(at(140f), u.undo().current)
    }

    @Test
    fun `scrubbing back to where the gesture began leaves no step behind`() {
        var h = BoardHistory.of(start).commit(at(200f))
        h = h.commit(at(210f), "slide").commit(at(220f), "slide")
        assertEquals(2, h.undoDepth)
        h = h.commit(at(200f), "slide")
        assertEquals(1, h.undoDepth)
        assertEquals(at(200f), h.current)
    }

    @Test
    fun `the oldest boards are dropped past the limit`() {
        var h = BoardHistory.of(start)
        for (i in 1..(BoardHistory.LIMIT + 20)) h = h.commit(at(100f + i))
        assertEquals(BoardHistory.LIMIT - 1, h.undoDepth)
        repeat(BoardHistory.LIMIT) { h = h.undo() }
        // The first board it can still reach is not the original.
        assertTrue(h.current != start)
        assertFalse(h.canUndo)
    }
}
