package com.fotoxplorr.app.board

import java.util.Collections

/** A [BoardLibrary] in memory: what the screen is tested against, with switches for the ways a disk fails. */
internal class MemoryBoardLibrary : BoardLibrary {
    class Entry(val name: String, val board: Board, val modified: Long, val decoded: BoardJson.Decoded? = null, val readable: Boolean = true)

    val entries: MutableMap<String, Entry> = Collections.synchronizedMap(LinkedHashMap())
    val saves: MutableList<Pair<String?, Board>> = Collections.synchronizedList(ArrayList())
    @Volatile var result: ((String?) -> BoardSave)? = null
    @Volatile var clock = 1_000L

    override fun list(): SavedBoards = SavedBoards(
        entries.entries.sortedByDescending { it.value.modified }.map { (id, e) ->
            SavedBoard(id, if (e.readable) e.name else BoardCopy.DAMAGED_BOARD, e.modified, e.board.canvas, e.board.layers.size, e.readable)
        },
    )

    override fun load(id: String): BoardLoad {
        val e = entries[id] ?: return BoardLoad.Missing
        if (!e.readable) return BoardLoad.Unreadable
        return BoardLoad.Loaded(id, e.name, e.decoded ?: BoardJson.Decoded(e.board, 0, 0, false))
    }

    override fun save(id: String?, name: String, board: Board): BoardSave {
        saves += id to board
        result?.let { forced -> return forced(id) }
        val target = id ?: BoardStore.newId()
        entries[target] = Entry(BoardStore.cleanName(name), board, ++clock)
        return BoardSave.Saved(target)
    }

    override fun delete(id: String): Boolean = entries.remove(id) != null

    fun put(name: String, board: Board, modified: Long = ++clock, decoded: BoardJson.Decoded? = null, readable: Boolean = true): String {
        val id = BoardStore.newId()
        entries[id] = Entry(name, board, modified, decoded, readable)
        return id
    }
}
