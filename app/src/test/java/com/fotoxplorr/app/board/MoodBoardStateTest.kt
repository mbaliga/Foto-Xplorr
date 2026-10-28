package com.fotoxplorr.app.board

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.mutableStateOf
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.hint
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodBoardStateTest {

    private fun newState(open: OpenBoard? = null) = MoodBoardState(
        mutableStateOf(open), mutableStateOf(Panel.NONE), mutableStateOf(true), mutableStateOf(0xFF000000.toInt()),
        mutableStateOf(""), mutableStateOf(OutputFormat.JPEG), mutableStateOf(MaxEdgePreset.ORIGINAL),
    )

    private fun open(board: Board = board(layer(1, 100f, 100f)), selected: Long? = null) =
        OpenBoard(nextToken(), null, "Spring", BoardEditor.of(board, selected))

    private val scope = SaverScope { true }

    // ---- Back, innermost first ----

    @Test
    fun `back undoes the picker, then a tap step, then the panel, and then leaves it to the caller`() {
        val s = newState(open())
        s.panel = Panel.LOOK
        s.mode = CanvasMode.Marks(true)
        s.picker = true
        assertTrue(s.backOneStep()); assertFalse(s.picker)
        assertTrue(s.backOneStep()); assertTrue(s.mode is CanvasMode.Edit)
        assertEquals(Panel.LOOK, s.panel)
        assertTrue(s.backOneStep()); assertEquals(Panel.NONE, s.panel)
        assertFalse("nothing left to step back from", s.backOneStep())
    }

    @Test
    fun `a tool chip opens its panel, closes it when pressed again, and ends a tap step`() {
        val s = newState(open())
        s.toggle(Panel.ADD)
        assertEquals(Panel.ADD, s.panel)
        s.mode = CanvasMode.Corners(emptyList())
        s.toggle(Panel.LOOK)
        assertEquals(Panel.LOOK, s.panel)
        assertTrue(s.mode is CanvasMode.Edit)
        s.toggle(Panel.LOOK)
        assertEquals(Panel.NONE, s.panel)
    }

    // ---- edits ----

    @Test
    fun `an edit goes to the open board and nothing happens without one`() {
        val none = newState()
        none.edit { BoardEditing.addColour(it, RED) }
        assertNull(none.open)
        val s = newState(open())
        val before = s.open
        s.edit { it }
        assertTrue("an edit that changes nothing leaves the open board as it was", before === s.open)
        s.edit { BoardEditing.addColour(it, RED) }
        assertEquals(2, s.open!!.board.layers.size)
        assertEquals(before!!.token, s.open!!.token)
    }

    @Test
    fun `selecting through the state picks the layer`() {
        val s = newState(open())
        s.select(1L)
        assertEquals(1L, s.editor!!.selectedId)
        s.select(null)
        assertNull(s.editor!!.selectedId)
    }

    @Test
    fun `the board is described for a screen reader with its count and the layer picked`() {
        val s = newState(open(board(layer(1, 1f, 1f), layer(2, 2f, 2f)), selected = 2L))
        assertEquals("Board, 2 layers. Colour 2 picked.", s.description())
        s.select(null)
        assertEquals("Board, 2 layers.", s.description())
        assertEquals("", newState().description())
    }

    // ---- what survives the process ----

    @Test
    fun `the open board comes back from the saved state with its place, name and pick`() {
        val o = OpenBoard(nextToken(), "11111111-2222-3333-4444-555555555555", "Spring", BoardEditor.of(board(layer(1, 100f, 100f), layer(2, 5f, 5f)), 2L))
        val saved = with(OpenBoardSaver) { scope.save(o) }
        assertNotNull(saved)
        val back = OpenBoardSaver.restore(saved!!)!!
        assertEquals(o.board, back.board)
        assertEquals("11111111-2222-3333-4444-555555555555", back.storeId)
        assertEquals("Spring", back.name)
        assertEquals(2L, back.editor.selectedId)
        assertFalse("the history is not kept", back.editor.canUndo)
        assertTrue("a restored board is a new session", back.token != o.token)
    }

    @Test
    fun `no board and nothing picked are saved as nothing`() {
        assertNull(with(OpenBoardSaver) { scope.save(null) })
        val o = OpenBoard(nextToken(), null, "x", BoardEditor.of(board(layer(1, 1f, 1f))))
        val back = OpenBoardSaver.restore(with(OpenBoardSaver) { scope.save(o) }!!)!!
        assertNull(back.storeId)
        assertNull(back.editor.selectedId)
    }

    @Test
    fun `a board too long for the bundle is not put in it`() {
        // Subject marks are the long part of a board.
        val marks = List(BoardLimits.MAX_SELECTION_OPS) { hint(true, it / 300f, 0.5f) }
        var b = Board()
        repeat(60) { i -> b = BoardOps.addColour(b, RED).board.let { x -> BoardOps.setMask(x, x.layers.last().id, LayerMask(MaskShape.Subject(marks))) } }
        val text = BoardJson.encode(b)
        assertTrue("the test board is long enough (${text.length})", text.length > BoardJson.SAVED_STATE_SAFE_CHARS)
        val o = OpenBoard(nextToken(), "11111111-2222-3333-4444-555555555555", "Big", BoardEditor.of(b))
        val saved = with(OpenBoardSaver) { scope.save(o) }!!
        assertTrue((saved as List<*>).none { it is String && it.length > BoardJson.SAVED_STATE_SAFE_CHARS })
        assertNull("the screen comes back on the list, where the board is its autosaved file", OpenBoardSaver.restore(saved))
    }

    @Test
    fun `damaged saved state is nothing, not a crash`() {
        assertNull(OpenBoardSaver.restore("nonsense"))
        assertNull(OpenBoardSaver.restore(listOf("", "n", "{ not a board", -1L)))
        assertNull(OpenBoardSaver.restore(emptyList<Any>()))
    }

    // ---- the notice ----

    @Test
    fun `a board opened with parts missing says so, and a whole one says nothing`() {
        val b = Board()
        assertNull(noticeFor(BoardJson.Decoded(b, 0, 0, false)))
        assertEquals(BoardCopy.NEWER_VERSION, noticeFor(BoardJson.Decoded(b, 3, 1, true)))
        assertEquals("Could not read 1 layer.", noticeFor(BoardJson.Decoded(b, 1, 0, false)))
        assertEquals("Could not read 2 layers and 1 mask.", noticeFor(BoardJson.Decoded(b, 2, 1, false)))
        assertEquals("Could not read 3 masks.", noticeFor(BoardJson.Decoded(b, 0, 3, false)))
    }
}
