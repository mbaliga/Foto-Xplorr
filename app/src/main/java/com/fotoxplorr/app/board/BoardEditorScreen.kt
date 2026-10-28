package com.fotoxplorr.app.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.ShellEdges
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The editor: the title bar (Back, the name, Undo, Redo, Export), the save's progress strip, the board,
 * the panel of the open tool and the tool chips. Everything that changes the board goes through
 * [MoodBoardState.edit]; what runs work (the picture, the palette, the save) is passed in.
 *
 * The board is kept [ShellEdges.inset] from the left and right edges, because the shell takes a
 * horizontal drag that starts in its edge zone before the board sees it (TRAPS #72). It is not inset
 * from the top and bottom: the title bar is above it and the tool chips below, so it never reaches
 * those zones. The panels only scroll up and down.
 */
@Composable
internal fun BoardEditorScreen(
    state: MoodBoardState,
    open: OpenBoard,
    assets: List<MediaAsset>,
    missing: List<Long>,
    saving: RunningJob?,
    canExport: Boolean,
    onBack: () -> Unit,
    onFindColours: () -> Unit,
    onSave: () -> Unit,
    onCancelSave: () -> Unit,
    onOpenAsset: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = open.editor
    val board = editor.board
    Column(modifier.fillMaxSize()) {
        EditorHeader(
            name = open.name,
            canUndo = editor.canUndo,
            canRedo = editor.canRedo,
            exportOpen = state.panel == Panel.EXPORT,
            onBack = onBack,
            onRename = { state.panel = Panel.CANVAS; state.mode = CanvasMode.Edit },
            onUndo = { state.edit { BoardEditing.undo(it) } },
            onRedo = { state.edit { BoardEditing.redo(it) } },
            onExport = { state.toggle(Panel.EXPORT) },
        )
        ExportStrip(
            saving = saving,
            result = state.exportResult,
            leaveHeld = state.leaveHeld,
            assets = assets,
            onCancel = onCancelSave,
            onOpen = onOpenAsset,
            onShowLayer = { id -> state.select(id); state.panel = Panel.LAYERS },
        )
        open.notice?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).polite(), style = MaterialTheme.typography.bodySmall) }
        state.saveNote?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).polite(), style = MaterialTheme.typography.bodySmall) }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = ShellEdges.inset, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoardCanvasView(
                board = board,
                selectedId = editor.selectedId,
                preview = state.preview,
                snap = state.snap,
                mode = state.mode,
                description = state.description(),
                onTap = { x, y, slop -> state.select(BoardGeometry.hitTest(board, x, y, slop)) },
                onPick = { id -> state.select(id) },
                onTransform = { id, t, key -> state.edit { BoardEditing.setTransform(it, id, t, key) } },
                onPoint = { x, y -> onCanvasPoint(state, x, y) },
                onFit = { fit -> state.previewEdge = BoardGestures.previewEdge(fit) },
                modifier = Modifier.fillMaxSize(),
            )
            if (state.preview == null) {
                Text(BoardCopy.PREVIEW_PENDING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
            }
        }

        if (state.panel != Panel.NONE) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight())
                    .background(RoomStyle.Surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                PanelContent(state, open, editor, missing, canExport, saving != null, onFindColours, onSave)
            }
        }
        ToolBar(state.panel) { state.toggle(it) }
    }
}

/** About four tenths of the window, so the board keeps most of it: between 220 and 420 dp. A panel taller than this scrolls. */
@Composable
private fun panelMaxHeight() = (LocalConfiguration.current.screenHeightDp * 0.4f).dp.coerceIn(220.dp, 420.dp)

/** A tap on the board while a step that places points is open: a corner of the outline, or a mark on the subject. */
private fun onCanvasPoint(state: MoodBoardState, x: Float, y: Float) {
    val editor = state.editor ?: return
    val layer = editor.selected ?: return
    when (val mode = state.mode) {
        is CanvasMode.Corners -> {
            if (mode.points.size >= BoardLimits.MAX_POLYGON_POINTS) return
            BoardEditing.boxPoint(layer, x, y)?.let { state.mode = CanvasMode.Corners(mode.points + it) }
        }
        is CanvasMode.Marks -> {
            BoardEditing.subjectMark(layer, x, y, mode.foreground)?.let { mark -> state.edit { BoardEditing.addSubjectMark(it, mark) } }
        }
        CanvasMode.Edit -> Unit
    }
}

@Composable
private fun PanelContent(
    state: MoodBoardState,
    open: OpenBoard,
    editor: BoardEditor,
    missing: List<Long>,
    canExport: Boolean,
    saving: Boolean,
    onFindColours: () -> Unit,
    onSave: () -> Unit,
) {
    val edit: Edit = { change -> state.edit(change) }
    val hasPhotos = editor.board.photoRefs.isNotEmpty()
    when (state.panel) {
        Panel.NONE -> Unit
        Panel.ADD -> AddPanel(
            editor = editor,
            ink = state.ink,
            onInk = { state.ink = it },
            textDraft = state.textDraft,
            onTextDraft = { state.textDraft = it },
            palette = state.palette,
            edit = edit,
            onPhotos = { state.picker = true },
        )
        Panel.LAYER -> BoardColumn {
            BoardToggle(BoardCopy.SNAP, state.snap, { state.snap = it })
            LayerPanel(editor, state.palette, edit)
        }
        Panel.LOOK -> LookPanel(editor, state.mode, { state.mode = it }, edit)
        Panel.LAYERS -> LayersPanel(editor, missing.toSet(), { id -> state.select(id) }, edit)
        Panel.CANVAS -> CanvasPanel(editor, open.name, { name -> state.open = state.open?.withName(name) }, state.palette, edit)
        Panel.COLOURS -> ColoursPanel(
            hasPhotos = hasPhotos,
            swatches = state.palette,
            busy = state.paletteBusy,
            chosen = state.paletteChosen,
            onChoose = { state.paletteChosen = it },
            onFind = onFindColours,
            onBackground = { c -> edit { BoardEditing.setBackground(it, BoardBackground.Colour(c)) } },
            onInk = { state.ink = it },
            onBlock = { c -> edit { BoardEditing.addColour(it, c) } },
        )
        Panel.EXPORT -> ExportPanel(
            board = editor.board,
            format = state.format,
            maxEdge = state.maxEdge,
            onFormat = { state.format = it },
            onMaxEdge = { state.maxEdge = it },
            missing = missing,
            onShowMissing = { missing.firstOrNull()?.let { id -> state.select(id); state.panel = Panel.LAYERS } },
            canSave = canExport && editor.board.layers.isNotEmpty(),
            saving = saving,
            onSave = onSave,
        )
    }
}

// ---- the title bar ------------------------------------------------------------------------------------

@Composable
private fun EditorHeader(
    name: String,
    canUndo: Boolean,
    canRedo: Boolean,
    exportOpen: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardIconButton(Icons.AutoMirrored.Outlined.ArrowBack, BoardCopy.BACK, onBack)
        Text(
            name.ifBlank { BoardCopy.DEFAULT_NAME },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = BoardCopy.RENAME, onClick = onRename)
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BoardIconButton(Icons.AutoMirrored.Outlined.Undo, BoardCopy.UNDO, onUndo, enabled = canUndo)
        BoardIconButton(Icons.AutoMirrored.Outlined.Redo, BoardCopy.REDO, onRedo, enabled = canRedo)
        BoardToggle(BoardCopy.EXPORT, exportOpen, { onExport() })
    }
}

// ---- the save's strip -----------------------------------------------------------------------------------

/** A save's progress and Cancel while it runs, or how the last one ended. Shown whatever panel is open. */
@Composable
private fun ExportStrip(
    saving: RunningJob?,
    result: ExportResult?,
    leaveHeld: Boolean,
    assets: List<MediaAsset>,
    onCancel: () -> Unit,
    onOpen: (MediaAsset) -> Unit,
    onShowLayer: (Long) -> Unit,
) {
    if (saving == null && result == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saving != null) {
            val progress = saving.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            }
            Text(BoardCopy.SAVING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            Text(
                BoardCopy.LEAVING_CANCELS,
                modifier = if (leaveHeld) Modifier.polite() else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (leaveHeld) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            BoardButton(BoardCopy.CANCEL, onCancel)
        } else when (result) {
            is ExportResult.Saved -> {
                Text(BoardCopy.SAVED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    BoardCopy.sizeNote(result.done.width, result.done.height, result.done.steppedDown),
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomStyle.InkMuted,
                )
                val asset = BoardExportJob.savedAsset(assets, result.done.uri)
                if (asset != null) {
                    BoardButton(BoardCopy.OPEN_IT, { onOpen(asset) })
                } else {
                    Text(BoardCopy.NOT_LISTED_YET, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                }
            }
            is ExportResult.Failed -> {
                Text(BoardCopy.problem(result.problem), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                result.layerIds.firstOrNull()?.let { id -> BoardButton(BoardCopy.SHOW_MISSING, { onShowLayer(id) }) }
            }
            ExportResult.Cancelled -> Text(BoardCopy.CANCELLED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
    }
}

// ---- the tool chips ---------------------------------------------------------------------------------------

@Composable
private fun ToolBar(open: Panel, onToggle: (Panel) -> Unit) {
    // Clear of the bottom edge zone, as the panorama's bar is; the chips are taps, not drags.
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .navigationBarsPadding()
            .heightIn(min = 72.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolChip(BoardCopy.TOOL_ADD, open == Panel.ADD, { onToggle(Panel.ADD) }, Modifier.weight(1f))
        ToolChip(BoardCopy.TOOL_LAYER, open == Panel.LAYER, { onToggle(Panel.LAYER) }, Modifier.weight(1f))
        ToolChip(BoardCopy.TOOL_LOOK, open == Panel.LOOK, { onToggle(Panel.LOOK) }, Modifier.weight(1f))
        ToolChip(BoardCopy.TOOL_LAYERS, open == Panel.LAYERS, { onToggle(Panel.LAYERS) }, Modifier.weight(1f))
        ToolChip(BoardCopy.TOOL_CANVAS, open == Panel.CANVAS, { onToggle(Panel.CANVAS) }, Modifier.weight(1f))
        ToolChip(BoardCopy.TOOL_COLOURS, open == Panel.COLOURS, { onToggle(Panel.COLOURS) }, Modifier.weight(1f))
    }
}

/** A tool's button: the open one is bold and has a line under it as well as being selected for a screen reader. */
@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) RoomStyle.Ink else RoomStyle.InkMuted,
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .heightIn(min = 3.dp)
                .fillMaxWidth(0.6f)
                .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}
