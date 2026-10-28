package com.fotoxplorr.app.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.ui.RoomStyle

/*
 * The panels under the board, one per tool chip. Each takes the editor and an `edit` function and
 * draws the controls for it; nothing in here owns state or does any work, so every number a button
 * changes is a BoardEditing function with its own test.
 */

internal typealias Edit = ((BoardEditor) -> BoardEditor) -> Unit

/** Colours of the photos, as extra swatches next to the basic ones: the colour and the words a screen reader says. */
internal fun List<PaletteSwatch>.asChoices(): List<Pair<Int, String>> = map { it.argb to BoardCopy.swatch(it.hex, it.proportion) }

@Composable
private fun BoardTextField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RoomStyle.Ink,
            unfocusedTextColor = RoomStyle.Ink,
            disabledTextColor = RoomStyle.InkFaint,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = RoomStyle.InkFaint,
            focusedLabelColor = RoomStyle.InkMuted,
            unfocusedLabelColor = RoomStyle.InkMuted,
            cursorColor = Color.White,
        ),
    )
}

// ---- add ------------------------------------------------------------------------------------------------

@Composable
internal fun AddPanel(
    editor: BoardEditor,
    ink: Int,
    onInk: (Int) -> Unit,
    textDraft: String,
    onTextDraft: (String) -> Unit,
    palette: List<PaletteSwatch>,
    edit: Edit,
    onPhotos: () -> Unit,
) {
    val full = editor.board.layers.size >= BoardLimits.MAX_LAYERS
    BoardColumn {
        BoardWrap {
            BoardButton(BoardCopy.ADD_PHOTO, onPhotos, enabled = !full)
            BoardButton(BoardCopy.ADD_COLOUR, { edit { BoardEditing.addColour(it, ink) } }, enabled = !full)
        }
        if (full) Text(BoardCopy.BOARD_FULL, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)

        BoardSectionTitle(BoardCopy.COLOUR_FOR_NEW)
        BoardColourChoices(selectedArgb = ink, onPick = onInk, extra = palette.asChoices())

        BoardSectionTitle(BoardCopy.ADD_SHAPE_HEADING)
        BoardWrap {
            ShapeKind.entries.forEach { kind ->
                BoardButton(BoardCopy.shape(kind), { edit { BoardEditing.addShape(it, kind, ink) } }, enabled = !full)
            }
        }

        BoardSectionTitle(BoardCopy.ADD_TEXT)
        BoardTextField(BoardCopy.TEXT_FIELD, textDraft, { onTextDraft(it.take(BoardLimits.MAX_TEXT_CHARS)) })
        BoardButton(
            BoardCopy.ADD_THE_TEXT,
            {
                edit { BoardEditing.addText(it, textDraft.ifBlank { BoardCopy.TEXT_STARTER }, ink) }
                onTextDraft("")
            },
            enabled = !full,
        )
    }
}

// ---- the picked layer -----------------------------------------------------------------------------------

// The nudge arrows are physical directions, so they are not mirrored for a right-to-left language.
@Suppress("DEPRECATION")
@Composable
internal fun LayerPanel(editor: BoardEditor, palette: List<PaletteSwatch>, edit: Edit) {
    val layer = editor.selected
    if (layer == null) {
        Text(BoardCopy.NOTHING_PICKED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        Text(BoardCopy.LOCKED_PICK_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        return
    }
    val free = !layer.locked
    val index = editor.board.indexOf(layer.id)
    val last = editor.board.layers.lastIndex
    BoardColumn {
        if (!free) Text(BoardCopy.LOCKED_NOTE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)

        BoardWrap {
            val step = BoardEditing.Steps.NUDGE_UNITS
            BoardIconButton(Icons.Outlined.KeyboardArrowLeft, BoardCopy.NUDGE_LEFT, { edit { BoardEditing.nudge(it, -step, 0f) } }, enabled = free)
            BoardIconButton(Icons.Outlined.KeyboardArrowUp, BoardCopy.NUDGE_UP, { edit { BoardEditing.nudge(it, 0f, -step) } }, enabled = free)
            BoardIconButton(Icons.Outlined.KeyboardArrowDown, BoardCopy.NUDGE_DOWN, { edit { BoardEditing.nudge(it, 0f, step) } }, enabled = free)
            BoardIconButton(Icons.Outlined.KeyboardArrowRight, BoardCopy.NUDGE_RIGHT, { edit { BoardEditing.nudge(it, step, 0f) } }, enabled = free)
            BoardButton(BoardCopy.CENTRE, { edit { BoardEditing.centre(it) } }, enabled = free)
        }

        BoardStepper(
            valueText = BoardCopy.sizeNow(layer.transform.scale),
            lessLabel = BoardCopy.SMALLER,
            moreLabel = BoardCopy.BIGGER,
            onLess = { edit { BoardEditing.scaleBy(it, 1f / BoardEditing.Steps.SCALE_FACTOR) } },
            onMore = { edit { BoardEditing.scaleBy(it, BoardEditing.Steps.SCALE_FACTOR) } },
            enabled = free,
            canLess = layer.transform.scale > BoardLimits.MIN_SCALE * 1.001f,
            canMore = layer.transform.scale < BoardLimits.MAX_SCALE * 0.999f,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val coarse = BoardEditing.Steps.TURN_COARSE
            val fine = BoardEditing.Steps.TURN_FINE
            BoardStepButton("-15°", BoardCopy.TURN_LEFT_COARSE, { edit { BoardEditing.turnBy(it, -coarse) } }, enabled = free)
            BoardStepButton("-1°", BoardCopy.TURN_LEFT_FINE, { edit { BoardEditing.turnBy(it, -fine) } }, enabled = free)
            Text(
                BoardCopy.turnNow(layer.transform.rotationDeg),
                modifier = Modifier.weight(1f).polite(),
                style = MaterialTheme.typography.bodySmall,
                color = if (free) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            BoardStepButton("+1°", BoardCopy.TURN_RIGHT_FINE, { edit { BoardEditing.turnBy(it, fine) } }, enabled = free)
            BoardStepButton("+15°", BoardCopy.TURN_RIGHT_COARSE, { edit { BoardEditing.turnBy(it, coarse) } }, enabled = free)
        }

        BoardWrap {
            BoardButton(BoardCopy.FORWARD, { edit { BoardEditing.restack(it, StackMove.FORWARD) } }, enabled = free && index < last)
            BoardButton(BoardCopy.BACKWARD, { edit { BoardEditing.restack(it, StackMove.BACKWARD) } }, enabled = free && index > 0)
            BoardButton(BoardCopy.TO_FRONT, { edit { BoardEditing.restack(it, StackMove.TO_FRONT) } }, enabled = free && index < last)
            BoardButton(BoardCopy.TO_BACK, { edit { BoardEditing.restack(it, StackMove.TO_BACK) } }, enabled = free && index > 0)
        }

        BoardWrap {
            BoardToggle(BoardCopy.LOCK, layer.locked, { edit { e -> BoardEditing.setLocked(e, layer.id, it) } })
            BoardButton(BoardCopy.DUPLICATE, { edit { BoardEditing.duplicate(it) } })
            BoardButton(BoardCopy.DELETE, { edit { BoardEditing.delete(it) } }, enabled = free)
        }

        val colour = when (val c = layer.content) {
            is LayerContent.Colour -> c.argb
            is LayerContent.Shape -> c.fillArgb
            is LayerContent.Text -> c.argb
            is LayerContent.Photo -> null
        }
        if (colour != null) {
            BoardSectionTitle(BoardCopy.COLOUR_OF_LAYER)
            BoardColourChoices(selectedArgb = colour, onPick = { c -> edit { BoardEditing.setColour(it, c) } }, extra = palette.asChoices(), enabled = free)
        }
        val text = layer.content as? LayerContent.Text
        if (text != null) TextControls(text, free, edit)
    }
}

@Composable
private fun TextControls(text: LayerContent.Text, free: Boolean, edit: Edit) {
    BoardColumn {
        BoardSectionTitle(BoardCopy.ADD_TEXT)
        BoardTextField(BoardCopy.TEXT_FIELD, text.text, { value -> edit { BoardEditing.setText(it, value) } }, enabled = free)
        BoardWrap {
            BoardToggle(BoardCopy.TEXT_BOLD, text.bold, { on -> edit { BoardEditing.setTextBold(it, on) } }, enabled = free)
            TextAlign.entries.forEach { align ->
                BoardChoice(
                    when (align) {
                        TextAlign.START -> BoardCopy.ALIGN_START
                        TextAlign.CENTRE -> BoardCopy.ALIGN_CENTRE
                        TextAlign.END -> BoardCopy.ALIGN_END
                    },
                    selected = text.align == align,
                    onClick = { edit { BoardEditing.setTextAlign(it, align) } },
                    enabled = free,
                )
            }
        }
        BoardStepper(
            valueText = "${text.sizeUnits.toInt()}",
            lessLabel = BoardCopy.TEXT_SMALLER,
            moreLabel = BoardCopy.TEXT_BIGGER,
            onLess = { edit { BoardEditing.textSizeBy(it, 1f / BoardEditing.Steps.TEXT_SIZE_FACTOR) } },
            onMore = { edit { BoardEditing.textSizeBy(it, BoardEditing.Steps.TEXT_SIZE_FACTOR) } },
            enabled = free,
        )
    }
}

// ---- how the layer looks --------------------------------------------------------------------------------

@Composable
internal fun LookPanel(editor: BoardEditor, mode: CanvasMode, onMode: (CanvasMode) -> Unit, edit: Edit) {
    val layer = editor.selected
    if (layer == null) {
        Text(BoardCopy.NOTHING_PICKED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val free = !layer.locked
    val kind = MaskKind.of(layer.mask)
    BoardColumn {
        if (!free) Text(BoardCopy.LOCKED_NOTE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)

        BoardStepper(
            valueText = BoardCopy.opacityNow(layer.opacity),
            lessLabel = BoardCopy.OPACITY_LESS,
            moreLabel = BoardCopy.OPACITY_MORE,
            onLess = { edit { BoardEditing.opacityBy(it, -BoardEditing.Steps.OPACITY) } },
            onMore = { edit { BoardEditing.opacityBy(it, BoardEditing.Steps.OPACITY) } },
            enabled = free,
            canLess = layer.opacity > 0f,
            canMore = layer.opacity < 1f,
        )

        BoardSectionTitle(BoardCopy.BLEND_HEADING)
        BoardWrap {
            LayerBlend.entries.forEach { blend ->
                BoardChoice(BoardCopy.blend(blend), layer.blend == blend, { edit { BoardEditing.setBlend(it, blend) } }, enabled = free)
            }
        }

        BoardSectionTitle(BoardCopy.MASK_HEADING)
        BoardWrap {
            MaskKind.entries.forEach { option ->
                BoardChoice(
                    BoardCopy.maskOption(option),
                    selected = kind == option,
                    enabled = free && (option != MaskKind.SUBJECT || layer.isPhoto),
                    onClick = {
                        onMode(CanvasMode.Edit)
                        when (option) {
                            MaskKind.NONE -> edit { BoardEditing.setMaskShape(it, MaskShape.Full) }
                            MaskKind.ROUNDED -> edit { BoardEditing.setMaskShape(it, MaskShape.RoundedRect(BoardEditing.Steps.DEFAULT_ROUNDING)) }
                            MaskKind.OVAL -> edit { BoardEditing.setMaskShape(it, MaskShape.Ellipse) }
                            MaskKind.SHAPE -> if (kind != MaskKind.SHAPE) {
                                edit { BoardEditing.setMaskShape(it, MaskShape.Polygon(MaskPresets.points(MaskPresets.Preset.DIAMOND))) }
                            }
                            MaskKind.SUBJECT -> {
                                if (kind != MaskKind.SUBJECT) edit { BoardEditing.setMaskShape(it, MaskShape.Subject(emptyList())) }
                                onMode(CanvasMode.Marks(foreground = true))
                            }
                        }
                    },
                )
            }
        }
        if (!layer.isPhoto) Text(BoardCopy.SUBJECT_PHOTOS_ONLY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)

        when (kind) {
            MaskKind.ROUNDED -> {
                val radius = (layer.mask.shape as MaskShape.RoundedRect).radius
                BoardStepper(
                    valueText = BoardCopy.roundingNow(radius),
                    lessLabel = BoardCopy.RADIUS_LESS,
                    moreLabel = BoardCopy.RADIUS_MORE,
                    onLess = { edit { BoardEditing.radiusBy(it, -BoardEditing.Steps.RADIUS) } },
                    onMore = { edit { BoardEditing.radiusBy(it, BoardEditing.Steps.RADIUS) } },
                    enabled = free,
                    canLess = radius > 0f,
                    canMore = radius < 0.5f,
                )
            }
            MaskKind.SHAPE -> ShapeControls(layer, mode, free, onMode, edit)
            MaskKind.SUBJECT -> SubjectControls(layer, mode, free, onMode, edit)
            else -> Unit
        }

        if (kind != MaskKind.SUBJECT) {
            BoardStepper(
                valueText = BoardCopy.featherNow(layer.mask.featherClamped),
                lessLabel = BoardCopy.FEATHER_LESS,
                moreLabel = BoardCopy.FEATHER_MORE,
                onLess = { edit { BoardEditing.featherBy(it, -BoardEditing.Steps.FEATHER) } },
                onMore = { edit { BoardEditing.featherBy(it, BoardEditing.Steps.FEATHER) } },
                enabled = free,
                canLess = layer.mask.featherClamped > 0f,
                canMore = layer.mask.featherClamped < BoardLimits.MAX_FEATHER,
            )
        }
    }
}

@Composable
private fun ShapeControls(layer: Layer, mode: CanvasMode, free: Boolean, onMode: (CanvasMode) -> Unit, edit: Edit) {
    val corners = mode as? CanvasMode.Corners
    BoardColumn {
        BoardWrap {
            MaskPresets.Preset.entries.forEach { preset ->
                BoardButton(
                    when (preset) {
                        MaskPresets.Preset.DIAMOND -> BoardCopy.PRESET_DIAMOND
                        MaskPresets.Preset.TRIANGLE -> BoardCopy.PRESET_TRIANGLE
                        MaskPresets.Preset.HEXAGON -> BoardCopy.PRESET_HEXAGON
                        MaskPresets.Preset.STAR -> BoardCopy.PRESET_STAR
                    },
                    {
                        onMode(CanvasMode.Edit)
                        edit { BoardEditing.setMaskShape(it, MaskShape.Polygon(MaskPresets.points(preset))) }
                    },
                    enabled = free,
                )
            }
            if (corners == null) BoardButton(BoardCopy.DRAW_BY_TAPS, { onMode(CanvasMode.Corners(emptyList())) }, enabled = free)
        }
        if (corners != null) {
            Text(BoardCopy.TAP_POINTS, style = MaterialTheme.typography.bodySmall)
            Text(BoardCopy.pointsNow(corners.points.size), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            BoardWrap {
                BoardButton(BoardCopy.UNDO_POINT, { onMode(CanvasMode.Corners(corners.points.dropLast(1))) }, enabled = corners.points.isNotEmpty())
                BoardButton(
                    BoardCopy.DONE,
                    {
                        edit { BoardEditing.setMaskShape(it, MaskShape.Polygon(corners.points)) }
                        onMode(CanvasMode.Edit)
                    },
                    enabled = corners.points.size >= 3,
                    primary = true,
                )
                BoardButton(BoardCopy.CANCEL, { onMode(CanvasMode.Edit) })
            }
            if (corners.points.size in 1..2) Text(BoardCopy.NEED_THREE_POINTS, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        }
    }
}

@Composable
private fun SubjectControls(layer: Layer, mode: CanvasMode, free: Boolean, onMode: (CanvasMode) -> Unit, edit: Edit) {
    val marks = mode as? CanvasMode.Marks
    val ops = (layer.mask.shape as? MaskShape.Subject)?.ops.orEmpty()
    BoardColumn {
        if (marks == null) {
            BoardButton(BoardCopy.MARK_SUBJECT, { onMode(CanvasMode.Marks(foreground = true)) }, enabled = free)
        } else {
            Text(BoardCopy.MARK_HINT, style = MaterialTheme.typography.bodySmall)
            BoardWrap {
                BoardChoice(BoardCopy.MARK_SUBJECT, marks.foreground, { onMode(CanvasMode.Marks(foreground = true)) })
                BoardChoice(BoardCopy.MARK_NOT_SUBJECT, !marks.foreground, { onMode(CanvasMode.Marks(foreground = false)) })
            }
            Text(BoardCopy.marksNow(ops.size), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            BoardWrap {
                BoardButton(BoardCopy.UNDO_MARK, { edit { BoardEditing.undoSubjectMark(it) } }, enabled = free && ops.isNotEmpty())
                BoardButton(BoardCopy.CLEAR_MARKS, { edit { BoardEditing.setMaskShape(it, MaskShape.Subject(emptyList())) } }, enabled = free && ops.isNotEmpty())
                BoardButton(BoardCopy.DONE, { onMode(CanvasMode.Edit) }, primary = true)
            }
        }
        if (!(layer.mask.shape as MaskShape.Subject).isSolvable) {
            Text(BoardCopy.NOT_A_SUBJECT, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        }
        Text(BoardCopy.SUBJECT_SLOW, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        Text(BoardCopy.SUBJECT_EDGE_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
    }
}

// ---- the list of layers ---------------------------------------------------------------------------------

@Composable
internal fun LayersPanel(editor: BoardEditor, missing: Set<Long>, onPick: (Long) -> Unit, edit: Edit) {
    val board = editor.board
    if (board.layers.isEmpty()) {
        Text(BoardCopy.NO_LAYERS, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // The top of the stack is first, the way a list of layers is read.
        board.layers.asReversed().forEach { layer ->
            val number = board.indexOf(layer.id) + 1
            val name = BoardCopy.layerName(layer, number)
            val picked = layer.id == editor.selectedId
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(selected = picked, role = Role.RadioButton, onClick = { onPick(layer.id) })
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    val status = buildList {
                        if (picked) add(BoardCopy.STATUS_PICKED)
                        if (layer.locked) add(BoardCopy.STATUS_LOCKED)
                        if (layer.id in missing) add(BoardCopy.MISSING_PHOTO)
                    }
                    if (status.isNotEmpty()) Text(status.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
                }
                BoardIconButton(
                    Icons.Outlined.KeyboardArrowUp,
                    BoardCopy.forwardLayer(name),
                    { edit { BoardEditing.restack(it, layer.id, StackMove.FORWARD) } },
                    enabled = !layer.locked && number < board.layers.size,
                )
                BoardIconButton(
                    Icons.Outlined.KeyboardArrowDown,
                    BoardCopy.backwardLayer(name),
                    { edit { BoardEditing.restack(it, layer.id, StackMove.BACKWARD) } },
                    enabled = !layer.locked && number > 1,
                )
                BoardIconButton(
                    if (layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    if (layer.locked) BoardCopy.unlockLayer(name) else BoardCopy.lockLayer(name),
                    { edit { BoardEditing.setLocked(it, layer.id, !layer.locked) } },
                )
            }
        }
    }
}

// ---- canvas and background ------------------------------------------------------------------------------

@Composable
internal fun CanvasPanel(editor: BoardEditor, name: String, onName: (String) -> Unit, palette: List<PaletteSwatch>, edit: Edit) {
    val board = editor.board
    BoardColumn {
        BoardTextField(BoardCopy.NAME_LABEL, name, { onName(it.take(BoardStore.MAX_NAME)) })
        BoardSectionTitle(BoardCopy.CANVAS_SHAPE)
        BoardWrap {
            CanvasPreset.entries.forEach { preset ->
                BoardChoice(BoardCopy.canvas(preset), board.canvas == preset.size, { edit { BoardEditing.setCanvas(it, preset.size) } })
            }
        }
        BoardSectionTitle(BoardCopy.BACKGROUND_HEADING)
        BoardColourChoices(
            selectedArgb = (board.background as? BoardBackground.Colour)?.argb,
            onPick = { c -> edit { BoardEditing.setBackground(it, BoardBackground.Colour(c)) } },
            extra = palette.asChoices(),
        )
        BoardChoice(BoardCopy.TRANSPARENT, board.background is BoardBackground.Transparent, { edit { BoardEditing.setBackground(it, BoardBackground.Transparent) } })
        if (board.needsAlpha) Text(BoardCopy.TRANSPARENT_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
    }
}

// ---- the photos' own colours ----------------------------------------------------------------------------

@Composable
internal fun ColoursPanel(
    hasPhotos: Boolean,
    swatches: List<PaletteSwatch>,
    busy: Boolean,
    chosen: Int?,
    onChoose: (Int) -> Unit,
    onFind: () -> Unit,
    onBackground: (Int) -> Unit,
    onInk: (Int) -> Unit,
    onBlock: (Int) -> Unit,
) {
    BoardColumn {
        BoardButton(BoardCopy.FIND_COLOURS, onFind, enabled = hasPhotos && !busy)
        if (!hasPhotos) Text(BoardCopy.NO_PHOTO_COLOURS, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        if (busy) Text(BoardCopy.FINDING_COLOURS, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
        if (swatches.isNotEmpty()) {
            BoardSectionTitle(BoardCopy.COLOURS_OF_PHOTOS)
            BoardWrap {
                swatches.forEach { s ->
                    BoardSwatch(s.argb, BoardCopy.swatch(s.hex, s.proportion), selected = chosen == s.argb, onClick = { onChoose(s.argb) })
                }
            }
            if (chosen == null) Text(BoardCopy.PICK_A_COLOUR, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
            BoardWrap {
                BoardButton(BoardCopy.USE_AS_BACKGROUND, { chosen?.let(onBackground) }, enabled = chosen != null)
                BoardButton(BoardCopy.USE_AS_NEW_COLOUR, { chosen?.let(onInk) }, enabled = chosen != null)
                BoardButton(BoardCopy.ADD_AS_BLOCK, { chosen?.let(onBlock) }, enabled = chosen != null)
            }
        }
    }
}

// ---- saving a picture -----------------------------------------------------------------------------------

@Composable
internal fun ExportPanel(
    board: Board,
    format: OutputFormat,
    maxEdge: MaxEdgePreset,
    onFormat: (OutputFormat) -> Unit,
    onMaxEdge: (MaxEdgePreset) -> Unit,
    missing: List<Long>,
    onShowMissing: () -> Unit,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
) {
    val choosable = BoardFormats.isChoosable(board)
    val shown = BoardFormats.resolve(board, format)
    val size = BoardExportJob.outputSize(board, maxEdge)
    BoardColumn {
        BoardSectionTitle(BoardCopy.FORMAT_HEADING)
        BoardWrap {
            BoardFormats.CHOICES.forEach { option ->
                BoardChoice(
                    if (option == OutputFormat.PNG) BoardCopy.FORMAT_PNG else BoardCopy.FORMAT_JPEG,
                    selected = shown == option,
                    onClick = { onFormat(option) },
                    enabled = choosable,
                )
            }
        }
        if (!choosable) Text(BoardCopy.PNG_NEEDED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)

        BoardSectionTitle(BoardCopy.SIZE_HEADING)
        BoardWrap {
            MaxEdgePreset.entries.forEach { preset -> BoardChoice(preset.label, maxEdge == preset, { onMaxEdge(preset) }) }
        }
        Text(BoardCopy.aboutSize(size.width, size.height), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)

        if (missing.isNotEmpty()) {
            Text(BoardCopy.MISSING_PHOTOS_NOTE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            BoardButton(BoardCopy.SHOW_MISSING, onShowMissing)
        }
        Text(BoardCopy.NEW_FILE_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        BoardButton(BoardCopy.SAVE_PICTURE, onSave, enabled = canSave && !saving && missing.isEmpty(), primary = true)
    }
}
