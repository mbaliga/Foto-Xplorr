package com.fotoxplorr.app.editor

/**
 * Undo/redo over a sequence of [EditRecipe] snapshots.
 *
 * A plain list plus a cursor rather than two separate stacks: the redo tail is simply "the states
 * past the cursor", so recording a new state after an undo naturally discards it — the ordinary
 * editor rule that you cannot redo into a branch you have since painted over — with no separate
 * bookkeeping needed to keep two stacks in sync.
 *
 * Immutable and pure, like [EditRecipe] itself: [EditorScreen] holds one in `mutableStateOf` and
 * replaces it wholesale on every commit, the same pattern it already uses for `recipe`.
 */
data class EditHistory private constructor(
    private val states: List<EditRecipe>,
    private val cursor: Int,
) {
    val current: EditRecipe get() = states[cursor]
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < states.lastIndex

    /**
     * Records [recipe] as the new current state, discarding any redo tail.
     *
     * A [recipe] identical to [current] is not recorded at all, so a slider dragged back to
     * exactly where it started does not leave a no-op entry for Undo to have to step through.
     */
    fun commit(recipe: EditRecipe): EditHistory {
        if (recipe == current) return this
        val kept = states.take(cursor + 1) + recipe
        return EditHistory(kept, kept.lastIndex)
    }

    fun undo(): EditHistory = if (canUndo) EditHistory(states, cursor - 1) else this
    fun redo(): EditHistory = if (canRedo) EditHistory(states, cursor + 1) else this

    companion object {
        fun of(recipe: EditRecipe): EditHistory = EditHistory(listOf(recipe), 0)
    }
}
