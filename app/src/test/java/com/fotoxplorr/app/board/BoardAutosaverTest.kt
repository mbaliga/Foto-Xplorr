package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.layer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardAutosaverTest {

    private val library = MemoryBoardLibrary()
    private val saver = BoardAutosaver(library)
    private val one = board(layer(1, 100f, 100f))
    private fun open(token: Long, board: Board, name: String = "Mood board", id: String? = null) =
        OpenBoard(token, id, name, BoardEditor.of(board))

    @Test
    fun `a new board with nothing on it is never written`() = runBlocking {
        assertNull(saver.save(open(1, Board())))
        assertTrue(library.entries.isEmpty())
        assertTrue(library.saves.isEmpty())
    }

    @Test
    fun `the first save makes the file and the next save of the same board does nothing`() = runBlocking {
        val first = saver.save(open(1, one)) as BoardSave.Saved
        assertEquals(1, library.saves.size)
        val again = saver.save(open(1, one, id = null)) as BoardSave.Saved
        assertEquals("the second finds the id the first made", first.id, again.id)
        assertEquals("and writes nothing", 1, library.saves.size)
        assertEquals(1, library.entries.size)
    }

    @Test
    fun `a change or a new name saves again under the same id`() = runBlocking {
        val id = (saver.save(open(1, one)) as BoardSave.Saved).id
        saver.save(open(1, one.copy(background = BoardBackground.Transparent)))
        saver.save(open(1, one.copy(background = BoardBackground.Transparent), name = "Spring"))
        assertEquals(3, library.saves.size)
        assertEquals(setOf(id), library.saves.map { it.first }.filterNotNull().toSet())
        assertEquals("Spring", library.entries.getValue(id).name)
    }

    @Test
    fun `a board that already has a file is saved under it, even when it was emptied`() = runBlocking {
        val id = library.put("Old", one)
        val before = library.saves.size
        saver.save(open(2, Board(), id = id))
        assertEquals(before + 1, library.saves.size)
        assertEquals(id, library.saves.last().first)
        assertEquals(0, library.entries.getValue(id).board.layers.size)
    }

    @Test
    fun `a board opened from its file is not written again until it is changed`() = runBlocking {
        val id = library.put("Old", one)
        val opened = open(2, one, name = "Old", id = id)
        saver.baseline(opened)
        assertEquals("nothing to write for a board that is as it was opened", BoardSave.Saved(id), saver.save(opened))
        assertTrue(library.saves.isEmpty())
        saver.save(open(2, one.copy(background = BoardBackground.Transparent), name = "Old", id = id))
        assertEquals(1, library.saves.size)
        saver.save(open(2, one, name = "Renamed", id = id))
        assertEquals("a new name is a change", 2, library.saves.size)
    }

    @Test
    fun `a baseline for one board does not hold back the next board`() = runBlocking {
        val id = library.put("Old", one)
        saver.baseline(open(2, one, name = "Old", id = id))
        saver.save(open(3, one, name = "Other"))
        assertEquals("the new board has no file yet and gets one", 1, library.saves.size)
        assertNull(library.saves.single().first)
    }

    @Test
    fun `a second board is its own file`() = runBlocking {
        val a = (saver.save(open(1, one)) as BoardSave.Saved).id
        val b = (saver.save(open(2, one.copy(background = BoardBackground.Transparent))) as BoardSave.Saved).id
        assertTrue(a != b)
        assertEquals(2, library.entries.size)
    }

    @Test
    fun `callers that meet make one file between them`() = runBlocking {
        val results = List(8) { async { saver.save(open(1, one)) } }.awaitAll()
        assertEquals(1, library.entries.size)
        assertEquals(1, library.saves.size)
        assertEquals(1, results.filterIsInstance<BoardSave.Saved>().map { it.id }.toSet().size)
    }

    @Test
    fun `a refused or failed save is returned, remembered as not saved, and tried again`() = runBlocking {
        library.result = { BoardSave.Full }
        assertEquals(BoardSave.Full, saver.save(open(1, one)))
        library.result = { BoardSave.Failed }
        assertEquals(BoardSave.Failed, saver.save(open(1, one)))
        library.result = null
        assertTrue(saver.save(open(1, one)) is BoardSave.Saved)
        assertEquals(3, library.saves.size)
        assertNotNull(library.entries.values.single())
    }
}
