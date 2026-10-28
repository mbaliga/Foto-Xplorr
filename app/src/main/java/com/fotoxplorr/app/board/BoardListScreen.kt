package com.fotoxplorr.app.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle
import java.text.DateFormat
import java.util.Date

/**
 * The boards the person has made: a New board button first, then each saved board (name, how many
 * layers, its shape, when it was saved) as one button that opens it, with a Delete button beside it that
 * asks first. A file that cannot be read still has a row, with only Delete. Boards are saved as the person
 * works, so there is no Save button; the one line that says so is under the title. The page only scrolls
 * up and down and stops short of the bottom edge (TRAPS #72).
 */
@Composable
internal fun BoardListScreen(
    list: SavedBoards?,
    opening: Boolean,
    openFailed: Boolean,
    onNew: () -> Unit,
    onOpen: (SavedBoard) -> Unit,
    onDelete: (SavedBoard) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardIconButton(Icons.AutoMirrored.Outlined.ArrowBack, BoardCopy.BACK, onBack)
            Text(BoardCopy.TITLE, modifier = Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(BoardCopy.BLURB, style = MaterialTheme.typography.bodyMedium)
            Text(BoardCopy.SAVES_BY_ITSELF, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            val full = (list?.boards?.size ?: 0) >= BoardStore.MAX_BOARDS
            BoardButton(BoardCopy.NEW_BOARD, onNew, enabled = !full && !opening, primary = true)
            if (full) Text(BoardCopy.LIST_FULL, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            if (opening) Text(BoardCopy.OPENING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            if (openFailed) Text(BoardCopy.COULD_NOT_OPEN, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)

            Text(BoardCopy.YOUR_BOARDS, modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = RoomStyle.InkMuted)
            when {
                list == null -> Unit
                list.boards.isEmpty() -> Text(BoardCopy.NO_BOARDS, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                else -> list.boards.forEach { board -> BoardRow(board, enabled = !opening, onOpen = { onOpen(board) }, onDelete = { onDelete(board) }) }
            }
            // Clear of the bottom edge zone, as the hub's last item is.
            Spacer(Modifier.size(72.dp))
        }
    }
}

@Composable
private fun BoardRow(board: SavedBoard, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled && board.readable, role = Role.Button, onClick = onOpen)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(board.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (board.readable) {
                Text(
                    BoardCopy.boardSummary(board.layerCount, canvasName(board.canvas)),
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomStyle.InkMuted,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(board.modifiedMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomStyle.InkFaint,
                )
            }
        }
        BoardIconButton(Icons.Outlined.Delete, BoardCopy.deleteBoard(board.name), onDelete)
    }
}

/** The name of a canvas shape: a preset's own, otherwise the size. */
internal fun canvasName(size: CanvasSize): String =
    CanvasPreset.entries.firstOrNull { it.size == size }?.let(BoardCopy::canvas) ?: "${size.width} by ${size.height}"

/** The question before a board is deleted for good. */
@Composable
internal fun DeleteBoardDialog(board: SavedBoard, onDelete: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(BoardCopy.DELETE_ASK) },
        text = { Text(board.name) },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(BoardCopy.DELETE) } },
        dismissButton = { TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) { Text(BoardCopy.DELETE_KEEP) } },
    )
}
