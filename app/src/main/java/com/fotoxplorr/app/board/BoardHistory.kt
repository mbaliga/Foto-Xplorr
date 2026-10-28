package com.fotoxplorr.app.board

/**
 * Undo and redo over [Board] snapshots. A board is immutable and its layers are shared between
 * snapshots, so a snapshot costs a list, not a copy of the pictures (the pictures are not in the
 * board at all: a layer holds a reference).
 *
 * A list and a cursor, like the editor's own history: recording after an undo drops the redo tail.
 * Two refinements for a board:
 *  - A commit identical to the current board is not recorded.
 *  - A commit with the same [mergeKey] as the one just before it replaces that entry instead of
 *    adding one, so scrubbing an opacity slider or nudging a layer is one undo step, not a hundred.
 *    The key is forgotten by an undo, a redo, and any commit without a key.
 *
 * The depth is capped at [LIMIT] boards; the oldest are dropped, so Undo cannot walk back past the
 * first state the history still holds. The history is not saved: after the process is killed the
 * board comes back from its saved text (see [BoardJson]) with an empty history.
 */
data class BoardHistory private constructor(
    private val states: List<Board>,
    private val cursor: Int,
    private val lastKey: String?,
) {
    val current: Board get() = states[cursor]
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < states.lastIndex

    /** How many steps Undo can still take. */
    val undoDepth: Int get() = cursor

    fun commit(board: Board, mergeKey: String? = null): BoardHistory {
        if (board == current) return this
        val merge = mergeKey != null && mergeKey == lastKey && cursor == states.lastIndex && cursor > 0
        if (merge && board == states[cursor - 1]) {
            // Scrubbed back to where the gesture began: no step is left behind.
            return BoardHistory(states.take(cursor), cursor - 1, null)
        }
        val kept = if (merge) states.take(cursor) + board else states.take(cursor + 1) + board
        val trimmed = if (kept.size > LIMIT) kept.drop(kept.size - LIMIT) else kept
        return BoardHistory(trimmed, trimmed.lastIndex, mergeKey)
    }

    fun undo(): BoardHistory = if (canUndo) BoardHistory(states, cursor - 1, null) else this
    fun redo(): BoardHistory = if (canRedo) BoardHistory(states, cursor + 1, null) else this

    companion object {
        const val LIMIT = 100

        fun of(board: Board): BoardHistory = BoardHistory(listOf(board), 0, null)
    }
}
