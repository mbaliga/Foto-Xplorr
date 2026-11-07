package com.fotoxplorr.app.board

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Saves the open board into the store, one save at a time, and only when it differs from what was saved
 * last. The screen calls [save] from its debounced watcher, when a board is left, and when the screen is
 * torn down; the lock makes those meet in order, so a board that has no file yet gets exactly one (the
 * second caller finds the id the first made and the same board, and does nothing).
 *
 * A board opened from its file is [baseline]d first: it is then as saved, and is written only once the person changes
 * it. Without that, opening a board that was only partly readable (a newer file, a layer or mask that could
 * not be decoded) would write the readable part over the whole file a second later, unasked.
 * A new board with nothing on it is never written, so opening New board and going back leaves no file.
 * Plain Kotlin and the store interface, no Android, so it is tested on the JVM with an in-memory library.
 */
internal class BoardAutosaver(private val library: BoardLibrary) {
    private val lock = Mutex()
    private var token = -1L
    private var id: String? = null
    private var name: String? = null
    private var board: Board? = null

    /** Tells the saver that [open] is exactly what its file holds, as it was opened (relinked photos included). */
    suspend fun baseline(open: OpenBoard) = lock.withLock {
        token = open.token
        id = open.storeId
        name = open.name
        board = open.board
    }

    /** The result of a save that wrote, or null when there was nothing to write. */
    suspend fun save(open: OpenBoard): BoardSave? = lock.withLock {
        if (token != open.token) {
            token = open.token
            id = open.storeId
            name = null
            board = null
        }
        val current = id
        if (current == null && open.board.layers.isEmpty()) return null
        if (current != null && name == open.name && board == open.board) return BoardSave.Saved(current)
        val result = withContext(Dispatchers.IO) { library.save(current, open.name, open.board) }
        if (result is BoardSave.Saved) {
            id = result.id
            name = open.name
            board = open.board
        }
        result
    }
}
