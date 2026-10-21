package com.fotoxplorr.app.gallery

/**
 * What holding a photo in the grid does (owner, #3 and #14: *"Long press action should be
 * configurable in settings -- edit, or select or whatever"*).
 *
 * [PEEK] is the default and what a hold has done since the quick preview shipped: the photo large
 * for as long as the finger stays down. [SELECT] starts (or extends) a selection, which is what a
 * hold did before the preview and what anyone who had turned peeking off is migrated to -- see
 * [LongPressResolver.fromLegacyPeekFlag]. [FAVOURITE], [SHARE] and [EDIT] act on that one photo.
 * [NOTHING] makes a hold inert.
 *
 * There is deliberately no Trash: a slipped hold must never be destructive. Trash stays in the
 * right-click menu, the selection bar and the viewer.
 *
 * Only the NAME is persisted, so the order here can change; [label] is the Settings chip.
 */
enum class GridLongPress(val label: String) {
    PEEK("Quick preview"),
    SELECT("Select"),
    FAVOURITE("Favourite"),
    SHARE("Share"),
    EDIT("Edit"),
    NOTHING("Nothing"),
}

/** What one completed hold on a tile asks the tile to do; see [LongPressResolver.resolve]. */
sealed interface LongPressOutcome {
    /** Show the held preview; the tile's press handler hides it again on release. */
    object Peek : LongPressOutcome

    /** Add the tile to the selection, or take it out of it. */
    object ToggleSelection : LongPressOutcome

    /** Hand [action] to the grid's owner through [GridChromeBridge.onContextAction]. */
    data class Run(val action: MediaContextAction) : LongPressOutcome

    /** Swallow the hold. */
    object None : LongPressOutcome
}

/**
 * A long-click for accessibility services: the [outcome] to run and the [label] a screen reader
 * announces for it.
 */
data class LongClickSemantics(val label: String, val outcome: LongPressOutcome)

/**
 * The pure rules behind a hold on a grid tile. Android-free so every combination is a plain JVM
 * test; the tile only maps an outcome to its callbacks.
 */
object LongPressResolver {

    /**
     * What a hold does under [setting] while a selection is [selectionActive] or not.
     *
     * While a selection is running a hold always toggles the tile (extending the selection is
     * obviously what the gesture means, and favourite / share / edit are the bulk bar's job at that
     * point) -- except [GridLongPress.NOTHING], which stays inert. The photo picker always passes
     * `selectionActive = true`, so there a hold is the same as a tap.
     */
    fun resolve(setting: GridLongPress, selectionActive: Boolean): LongPressOutcome = when {
        setting == GridLongPress.NOTHING -> LongPressOutcome.None
        selectionActive -> LongPressOutcome.ToggleSelection
        else -> when (setting) {
            GridLongPress.PEEK -> LongPressOutcome.Peek
            GridLongPress.SELECT -> LongPressOutcome.ToggleSelection
            GridLongPress.FAVOURITE -> LongPressOutcome.Run(MediaContextAction.TOGGLE_FAVORITE)
            GridLongPress.SHARE -> LongPressOutcome.Run(MediaContextAction.SHARE)
            GridLongPress.EDIT -> LongPressOutcome.Run(MediaContextAction.EDIT)
            GridLongPress.NOTHING -> LongPressOutcome.None
        }
    }

    /**
     * The setting a grid should act on in the Trash. Favourite, share and edit make no sense on a
     * trashed photo (restore and delete are the actions there), so they fall back to the preview;
     * everything else is unchanged.
     */
    fun effectiveFor(setting: GridLongPress, inTrash: Boolean): GridLongPress = when {
        inTrash && (setting == GridLongPress.FAVOURITE || setting == GridLongPress.SHARE || setting == GridLongPress.EDIT) ->
            GridLongPress.PEEK
        else -> setting
    }

    /** The old on/off "Peek on long press" switch as a setting: on was the preview, off was select. */
    fun fromLegacyPeekFlag(peek: Boolean): GridLongPress = if (peek) GridLongPress.PEEK else GridLongPress.SELECT

    /**
     * The long-click an accessibility service is offered, or null for none.
     *
     * A preview lives only as long as a finger is down, so a screen reader's one-shot long-click
     * cannot drive it: under [GridLongPress.PEEK] it selects instead, and says so.
     */
    fun longClickSemantics(setting: GridLongPress, selectionActive: Boolean): LongClickSemantics? {
        val outcome = when (val held = resolve(setting, selectionActive)) {
            LongPressOutcome.Peek -> LongPressOutcome.ToggleSelection
            else -> held
        }
        val label = when (outcome) {
            LongPressOutcome.None, LongPressOutcome.Peek -> return null
            LongPressOutcome.ToggleSelection -> if (selectionActive) "Add or remove from selection" else "Select"
            is LongPressOutcome.Run -> when (outcome.action) {
                MediaContextAction.TOGGLE_FAVORITE -> "Favourite"
                MediaContextAction.SHARE -> "Share"
                MediaContextAction.EDIT -> "Edit"
                MediaContextAction.OPEN, MediaContextAction.MOVE_TO_TRASH -> return null
            }
        }
        return LongClickSemantics(label, outcome)
    }

    /** The label for a tap on a tile: it opens the photo, or toggles it while a selection runs. */
    fun tapLabel(selectionActive: Boolean): String = if (selectionActive) "Add or remove from selection" else "Open"
}
