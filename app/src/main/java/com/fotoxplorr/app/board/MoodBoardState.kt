package com.fotoxplorr.app.board

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.palette.PaletteSwatch

/** The tool panels under the board. */
internal enum class Panel { NONE, ADD, LAYER, LOOK, LAYERS, CANVAS, COLOURS, EXPORT }

/**
 * The board being edited: its place in the store (null until the first save), its name, the editor
 * state, and [token], which tells one editing session from the next so a save that finishes late is
 * never applied to a different board. [notice] is a line about how it was opened (a layer that could
 * not be read, a newer file).
 */
internal class OpenBoard(
    val token: Long,
    val storeId: String?,
    val name: String,
    val editor: BoardEditor,
    val notice: String? = null,
) {
    fun withEditor(editor: BoardEditor) = OpenBoard(token, storeId, name, editor, notice)

    fun withName(name: String) = OpenBoard(token, storeId, name, editor, notice)

    fun saved(id: String) = OpenBoard(token, id, name, editor, notice)

    val board: Board get() = editor.board
}

/**
 * What survives the process being killed: the board as text, which layer was picked, its place in the
 * store and its name. NOT the undo history. A board whose text is longer than
 * [BoardJson.SAVED_STATE_SAFE_CHARS] is not put in the saved-state bundle (which has a limit of about a
 * megabyte for the whole screen); only its place in the store goes, and nothing reads it: the screen
 * comes back on the saved-boards list, where the board is as its autosaved file has it (the last
 * second of edits before the kill is lost) and is opened again by hand.
 */
internal val OpenBoardSaver: Saver<OpenBoard?, Any> = Saver(
    save = { open ->
        open?.let {
            val text = BoardJson.encode(it.board)
            listOf(it.storeId.orEmpty(), it.name, if (text.length <= BoardJson.SAVED_STATE_SAFE_CHARS) text else "", it.editor.selectedId ?: -1L)
        }
    },
    restore = { saved ->
        val parts = saved as? List<*>
        val text = parts?.getOrNull(2) as? String
        val decoded = text?.takeIf { it.isNotEmpty() }?.let(BoardJson::decode)
        if (parts == null || decoded == null) {
            null
        } else {
            val selected = (parts.getOrNull(3) as? Long)?.takeIf { it >= 0L }
            OpenBoard(
                token = nextToken(),
                storeId = (parts[0] as? String)?.takeIf { it.isNotEmpty() },
                name = parts[1] as? String ?: BoardCopy.DEFAULT_NAME,
                editor = BoardEditor.of(decoded.board, selected),
            )
        }
    },
)

private var tokens = 0L

@Synchronized
internal fun nextToken(): Long = ++tokens

/** How the last export ended. */
internal sealed interface ExportResult {
    class Saved(val done: BoardExportOutcome.Done) : ExportResult

    class Failed(val problem: BoardExportProblem, val layerIds: List<Long>) : ExportResult

    data object Cancelled : ExportResult
}

/**
 * The mood board screen's state, in one place so the composables that draw it are plain. The values that
 * must come back after the process is killed are handed in as saveable states; the rest is here and is
 * lost with the process: the picture of the board, which panel's sub-step is open, the palette strip,
 * the saved-boards list, an export in progress.
 */
@Stable
internal class MoodBoardState(
    openState: MutableState<OpenBoard?>,
    panelState: MutableState<Panel>,
    snapState: MutableState<Boolean>,
    inkState: MutableState<Int>,
    textDraftState: MutableState<String>,
    formatState: MutableState<OutputFormat>,
    edgeState: MutableState<MaxEdgePreset>,
) {
    var open by openState
    var panel by panelState
    var snap by snapState
    var ink by inkState
    var textDraft by textDraftState
    var format by formatState
    var maxEdge by edgeState

    var picker by mutableStateOf(false)
    var mode by mutableStateOf<CanvasMode>(CanvasMode.Edit)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var previewEdge by mutableStateOf(0)
    var list by mutableStateOf<SavedBoards?>(null)
    var openFailed by mutableStateOf(false)
    var deleting by mutableStateOf<SavedBoard?>(null)
    var saveNote by mutableStateOf<String?>(null)

    var palette by mutableStateOf<List<PaletteSwatch>>(emptyList())
    var paletteBusy by mutableStateOf(false)
    var paletteChosen by mutableStateOf<Int?>(null)

    var exportResult by mutableStateOf<ExportResult?>(null)
    var leaveHeld by mutableStateOf(false)

    val editor: BoardEditor? get() = open?.editor

    /** Runs [change] on the open board's editor; nothing happens when no board is open or the change did nothing. */
    fun edit(change: (BoardEditor) -> BoardEditor) {
        val o = open ?: return
        val next = change(o.editor)
        if (next !== o.editor) open = o.withEditor(next)
    }

    fun select(id: Long?) = edit { BoardEditing.select(it, id) }

    /** Opens [panel], or closes it if it is the one open; leaving a panel ends any sub-step it began. */
    fun toggle(target: Panel) {
        panel = if (panel == target) Panel.NONE else target
        mode = CanvasMode.Edit
    }

    /**
     * The first things Back undoes, innermost first: the photo picker, a tap-the-points or mark-the-subject
     * step, an open panel. True when one was undone; false leaves Back to the caller (leave the board).
     */
    fun backOneStep(): Boolean = when {
        picker -> { picker = false; true }
        mode !is CanvasMode.Edit -> { mode = CanvasMode.Edit; true }
        panel != Panel.NONE -> { panel = Panel.NONE; true }
        else -> false
    }

    /** What the screen reader hears about the board. */
    fun description(): String {
        val e = editor ?: return ""
        val picked = e.selected?.let { layer -> BoardCopy.layerName(layer, e.board.indexOf(layer.id) + 1) }
        return BoardCopy.canvasDescription(e.board.layers.size, picked)
    }

    /** True when an export is running: the screen is held once before it is left. */
    fun exporting(jobs: List<RunningJob>): Boolean = jobs.any { it.title == BoardCopy.JOB_TITLE }
}
