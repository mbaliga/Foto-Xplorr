@file:OptIn(ExperimentalLayoutApi::class)

package com.fotoxplorr.app.wall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.RoomStyle

/** The wall photo's preview decode, drawable, and the 35 mm focal length it said (null when it said none). */
internal class WallPhoto(val decoded: DecodedBitmap, val focal35mm: Float?) {
    val image: ImageBitmap = decoded.bitmap.asImageBitmap()
    val widthPx: Int get() = decoded.bitmap.width
    val heightPx: Int get() = decoded.bitmap.height
}

/** A hung picture's preview decode, drawable. Its `sourceWidth`/`sourceHeight` are the real pixels the print has. */
internal class HungPicture(val decoded: DecodedBitmap) {
    val image: ImageBitmap = decoded.bitmap.asImageBitmap()
}

/**
 * The four steps of the mock-up on the pane's black ground: choose the wall photo, set its scale,
 * choose the pictures, hang them. Stateless: the draft and the decoded pictures come in, an edit
 * goes out through [onEdit] (a function from the current draft to the next, so a fast sequence of
 * taps never works from a stale one).
 *
 * Every control has a non-touch-gesture path: the frames are chosen from a list, moved and
 * resized and tilted with buttons, and the scale is set by typing a distance or the wall's width.
 * [export], when the host can run one, adds the print controls to the hang panel.
 */
@Composable
internal fun WallPreviewScreen(
    draft: WallDraft,
    stills: List<MediaAsset>,
    wall: WallPhoto?,
    pictures: Map<Long, HungPicture?>,
    onEdit: ((WallDraft) -> WallDraft) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    export: PrintActions? = null,
) {
    val step = draft.step
    BackHandler(enabled = step != WallStep.WALL) {
        onEdit { it.copy(step = WallStep.entries[it.step.ordinal - 1]) }
    }
    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Header(step = step, onClose = onClose)
        StepBar(draft = draft, onStep = { target -> onEdit { it.copy(step = target) } })
        when (step) {
            WallStep.WALL -> WallStepContent(draft, stills, onEdit)
            WallStep.PICTURES -> PicturesStep(draft, stills, wall, onEdit)
            WallStep.SCALE, WallStep.HANG -> WallAndPanel(draft, wall, pictures, onEdit, export)
        }
    }
}

@Composable
private fun Header(step: WallStep, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        WallIconButton(Icons.Outlined.Close, WallCopy.CLOSE, onClose)
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(WallCopy.TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                when (step) {
                    WallStep.WALL -> WallCopy.PICK_WALL
                    WallStep.SCALE -> WallCopy.SCALE_HEADER
                    WallStep.PICTURES -> WallCopy.PICK_PICTURES
                    WallStep.HANG -> WallCopy.HANG_HINT
                },
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
        }
    }
}

/** Where the person is, and a way back to a step already done. A step not yet reachable is dimmed and inert. */
@Composable
private fun StepBar(draft: WallDraft, onStep: (WallStep) -> Unit) {
    val reachable = mapOf(
        WallStep.WALL to true,
        WallStep.SCALE to (draft.wallId != null),
        WallStep.PICTURES to draft.isCalibrated,
        WallStep.HANG to draft.frames.isNotEmpty(),
    )
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            WallStep.WALL to WallCopy.STEP_WALL,
            WallStep.SCALE to WallCopy.STEP_SCALE,
            WallStep.PICTURES to WallCopy.STEP_PICTURES,
            WallStep.HANG to WallCopy.STEP_HANG,
        ).forEach { (step, label) ->
            WallChip(label, selected = draft.step == step, onClick = { onStep(step) }, enabled = reachable.getValue(step))
        }
    }
}

// --- Step 1 and 3: choosing photos ---

@Composable
private fun WallStepContent(draft: WallDraft, stills: List<MediaAsset>, onEdit: ((WallDraft) -> WallDraft) -> Unit) {
    PhotoGrid(
        stills = stills,
        order = listOfNotNull(draft.wallId),
        onTap = { asset ->
            val id = asset.id.value
            onEdit { d ->
                // A different wall starts again from the scale; the same one keeps what is set.
                if (d.wallId == id) d.copy(step = if (d.isCalibrated) WallStep.PICTURES else WallStep.SCALE)
                else d.copy(wallId = id, wallWidthCm = 0f, frames = emptyList(), step = WallStep.SCALE)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PicturesStep(draft: WallDraft, stills: List<MediaAsset>, wall: WallPhoto?, onEdit: ((WallDraft) -> WallDraft) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            WallCopy.LAYOUT,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SetKind.entries.forEach { kind ->
                WallChip(
                    WallCopy.kind(kind),
                    selected = draft.kind == kind,
                    onClick = { onEdit { it.copy(kind = kind, pictures = it.pictures.take(kind.capacity), frames = emptyList()) } },
                )
            }
        }
        Text(
            WallCopy.chosen(draft.pictures.size, draft.kind.capacity),
            style = MaterialTheme.typography.bodySmall,
            color = RoomStyle.InkMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        PhotoGrid(
            stills = stills,
            order = draft.pictures,
            onTap = { asset ->
                val id = asset.id.value
                onEdit { d ->
                    when {
                        id in d.pictures -> d.copy(pictures = d.pictures - id, frames = emptyList())
                        d.pictures.size < d.kind.capacity -> d.copy(pictures = d.pictures + id, frames = emptyList())
                        else -> d
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        WallButton(
            if (draft.frames.isEmpty()) WallCopy.HANG_THEM else WallCopy.ARRANGE_AGAIN,
            onClick = {
                val height = wall?.let { draft.wallWidthCm * it.heightPx / it.widthPx } ?: return@WallButton
                onEdit { d -> d.arranged(height).let { if (it.frames.isEmpty()) it else it.copy(step = WallStep.HANG) } }
            },
            enabled = draft.pictures.isNotEmpty() && wall != null,
            primary = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

/**
 * Thumbnails of [stills]. Those in [order] are chosen and carry their position as a number in a
 * disc (the cue besides the white border); the node says "selected" to TalkBack.
 */
@Composable
private fun PhotoGrid(stills: List<MediaAsset>, order: List<Long>, onTap: (MediaAsset) -> Unit, modifier: Modifier = Modifier) {
    if (stills.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(WallCopy.NO_PHOTOS, color = RoomStyle.InkMuted) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(stills, key = { it.id.value }) { asset ->
            val position = order.indexOf(asset.id.value)
            val chosen = position >= 0
            Box(
                Modifier
                    .aspectRatio(1f)
                    .then(if (chosen) Modifier.border(BorderStroke(3.dp, Color.White)) else Modifier)
                    .semantics { selected = chosen; role = Role.Button }
                    .clickable { onTap(asset) },
            ) {
                MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
                if (chosen) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${position + 1}", color = Color.Black, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

// --- Step 2 and 4: the wall and what is under it ---

@Composable
private fun WallAndPanel(
    draft: WallDraft,
    wall: WallPhoto?,
    pictures: Map<Long, HungPicture?>,
    onEdit: ((WallDraft) -> WallDraft) -> Unit,
    export: PrintActions?,
) {
    val marks = remember { mutableStateListOf<Offset>() }
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            WallCanvas(
                wall = wall?.image,
                draft = draft,
                pictures = pictures,
                marks = marks,
                onMark = { p ->
                    if (marks.size >= 2) marks.clear()
                    marks.add(p)
                },
                onDraft = { next -> onEdit { next } },
                modifier = Modifier.fillMaxSize(),
            )
            if (wall == null) Text(WallCopy.LOADING, color = RoomStyle.InkMuted)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxPanel).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (draft.step == WallStep.SCALE) ScalePanel(draft, wall, marks, onEdit) else HangPanel(draft, pictures, onEdit, export)
        }
    }
}

@Composable
private fun ScalePanel(draft: WallDraft, wall: WallPhoto?, marks: List<Offset>, onEdit: ((WallDraft) -> WallDraft) -> Unit) {
    var reference by remember { mutableStateOf(ReferenceObject.A4_LONG) }
    var referenceCm by remember { mutableStateOf(ReferenceObject.A4_LONG.cm) }
    // The wall width follows whichever way the person set it last: two marked ends of the reference...
    LaunchedEffect(marks.toList(), referenceCm, wall) {
        if (marks.size == 2 && wall != null) {
            val px = WallScale.lengthPx(marks[0].x, marks[0].y, marks[1].x, marks[1].y)
            WallScale.fromReference(referenceCm, px, wall.widthPx)?.let { cm -> onEdit { it.copy(wallWidthCm = cm) } }
        }
    }
    // ...or a distance typed before the photo had finished decoding, applied once it has.
    LaunchedEffect(wall) {
        if (wall != null && !draft.isCalibrated && draft.distanceCm > 0f) {
            val focal = wall.focal35mm ?: WallScale.GUESSED_FOCAL_35MM
            WallScale.fromDistance(draft.distanceCm, focal, wall.widthPx, wall.heightPx)?.let { w -> onEdit { it.copy(wallWidthCm = w) } }
        }
    }
    Text(
        when (marks.size) {
            0 -> WallCopy.MARK_FIRST
            1 -> WallCopy.MARK_SECOND
            else -> WallCopy.MARK_DONE
        },
        style = MaterialTheme.typography.bodySmall,
        color = RoomStyle.InkMuted,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReferenceObject.entries.forEach { ref ->
            WallChip(
                WallCopy.reference(ref),
                selected = reference == ref,
                onClick = {
                    reference = ref
                    referenceCm = ref.cm
                },
            )
        }
    }
    NumberField(WallCopy.REFERENCE_LENGTH, referenceCm, { referenceCm = it }, Modifier.fillMaxWidth())
    NumberField(
        WallCopy.DISTANCE,
        draft.distanceCm,
        { cm ->
            onEdit { it.copy(distanceCm = cm) }
            if (wall != null) {
                val focal = wall.focal35mm ?: WallScale.GUESSED_FOCAL_35MM
                WallScale.fromDistance(cm, focal, wall.widthPx, wall.heightPx)?.let { w -> onEdit { it.copy(wallWidthCm = w) } }
            }
        },
        Modifier.fillMaxWidth(),
    )
    if (wall != null && wall.focal35mm == null) {
        Text(WallCopy.LENS_GUESSED, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
    }
    NumberField(WallCopy.WALL_WIDTH, draft.wallWidthCm, { cm -> onEdit { it.copy(wallWidthCm = cm) } }, Modifier.fillMaxWidth())
    Text(
        if (draft.isCalibrated) WallCopy.wallIs(draft.wallWidthCm) else WallCopy.NEED_SCALE,
        style = MaterialTheme.typography.bodyMedium,
    )
    WallButton(
        WallCopy.NEXT,
        onClick = { onEdit { it.copy(step = WallStep.PICTURES) } },
        enabled = draft.isCalibrated,
        primary = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HangPanel(draft: WallDraft, pictures: Map<Long, HungPicture?>, onEdit: ((WallDraft) -> WallDraft) -> Unit, export: PrintActions?) {
    if (draft.frames.isEmpty()) return
    val index = draft.selected.coerceIn(0, draft.frames.lastIndex)
    val frame = draft.frames[index]
    val unit = if (draft.inches) PrintMath.CM_PER_INCH.toFloat() else 1f
    fun edit(change: (WallFrame) -> WallFrame) = onEdit { it.withFrame(index, change) }
    fun nudge(dx: Float, dy: Float) = edit { it.copy(cx = (it.cx + dx * unit * 2 / draft.wallWidthCm).coerceIn(0f, 1f), cy = (it.cy + dy * unit * 2 / draft.wallWidthCm).coerceAtLeast(0f)) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        draft.frames.forEachIndexed { i, _ ->
            WallChip(WallCopy.picture(i + 1), selected = i == index, onClick = { onEdit { it.copy(selected = i) } })
        }
    }
    QualityLines(frame, pictures.containsKey(frame.assetId), pictures[frame.assetId], draft.inches)

    // Size
    Row(verticalAlignment = Alignment.CenterVertically) {
        WallIconButton(Icons.Outlined.Remove, WallCopy.SMALLER, { edit { f -> resized(f, -2f * unit) } })
        Text(
            FramePresets.sizeLabel(frame.wCm, frame.hCm, draft.inches),
            Modifier.padding(horizontal = 8.dp).semantics { contentDescription = WallCopy.SIZE + " " + FramePresets.sizeLabel(frame.wCm, frame.hCm, draft.inches) },
        )
        WallIconButton(Icons.Outlined.Add, WallCopy.BIGGER, { edit { f -> resized(f, 2f * unit) } })
        WallIconButton(Icons.Outlined.ScreenRotation, WallCopy.TURN, { edit { it.copy(wCm = it.hCm, hCm = it.wCm) } })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SizeMenu(onPick = { size -> edit { it.copy(wCm = size.wCm, hCm = size.hCm) } })
        WallChip(WallCopy.UNIT_CM, selected = !draft.inches, onClick = { onEdit { it.copy(inches = false) } })
        WallChip(WallCopy.UNIT_IN, selected = draft.inches, onClick = { onEdit { it.copy(inches = true) } })
    }

    // Place and tilt
    Row(verticalAlignment = Alignment.CenterVertically) {
        WallIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, WallCopy.MOVE_LEFT, { nudge(-1f, 0f) })
        WallIconButton(Icons.Outlined.KeyboardArrowUp, WallCopy.MOVE_UP, { nudge(0f, -1f) })
        WallIconButton(Icons.Outlined.KeyboardArrowDown, WallCopy.MOVE_DOWN, { nudge(0f, 1f) })
        WallIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, WallCopy.MOVE_RIGHT, { nudge(1f, 0f) })
        WallIconButton(Icons.AutoMirrored.Outlined.RotateLeft, WallCopy.TILT_LEFT, { edit { it.copy(rotationDeg = WallView.turned(it.rotationDeg - 1f)) } })
        WallIconButton(Icons.AutoMirrored.Outlined.RotateRight, WallCopy.TILT_RIGHT, { edit { it.copy(rotationDeg = WallView.turned(it.rotationDeg + 1f)) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(WallCopy.tilt(frame.rotationDeg), style = MaterialTheme.typography.bodySmall)
        WallButton(WallCopy.LEVEL, { edit { it.copy(rotationDeg = 0f) } }, enabled = frame.rotationDeg != 0f)
    }

    // Look: the style and mat are the whole set's
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameStyle.entries.forEach { style ->
            WallChip(WallCopy.style(style), selected = draft.style == style, onClick = { onEdit { it.copy(style = style) } })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        WallIconButton(Icons.Outlined.Remove, WallCopy.LESS_MAT, { onEdit { it.copy(matCm = (it.matCm - unit).coerceAtLeast(0f)) } }, enabled = draft.matCm > 0f)
        Text(WallCopy.mat(draft.matCm, draft.inches), Modifier.padding(horizontal = 8.dp))
        WallIconButton(Icons.Outlined.Add, WallCopy.MORE_MAT, { onEdit { it.copy(matCm = (it.matCm + unit).coerceAtMost(FramePresets.MAT_MAX_CM)) } }, enabled = draft.matCm + unit <= FramePresets.MAT_MAX_CM + 0.01f)
    }

    if (export != null) ExportControls(frame, pictures[frame.assetId], export)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WallButton(WallCopy.CHANGE_PICTURES, { onEdit { it.copy(step = WallStep.PICTURES) } })
        WallButton(WallCopy.REDO_SCALE, { onEdit { it.copy(step = WallStep.SCALE) } })
    }
    Text(WallCopy.PRIVACY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
}

/** [f] with its width changed by [deltaCm], keeping its shape. */
private fun resized(f: WallFrame, deltaCm: Float): WallFrame {
    val (w, h) = FramePresets.scaled(f.wCm, f.hCm, (f.wCm + deltaCm) / f.wCm)
    return f.copy(wCm = w, hCm = h)
}

@Composable
private fun SizeMenu(onPick: (FrameSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        WallButton(WallCopy.STANDARD_SIZES, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FramePresets.all.forEach { size ->
                DropdownMenuItem(text = { Text(size.label) }, onClick = { open = false; onPick(size) })
            }
        }
    }
}

/** The selected picture's two honest numbers: the dpi at this size, and the biggest size at 150 dpi. */
@Composable
private fun QualityLines(frame: WallFrame, loaded: Boolean, picture: HungPicture?, inches: Boolean) {
    val decoded = picture?.decoded
    if (decoded == null) {
        Text(if (loaded) WallCopy.CANT_SHOW else WallCopy.LOADING, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        return
    }
    val dpi = PrintMath.effectiveDpi(decoded.sourceWidth, decoded.sourceHeight, frame.wCm.toDouble(), frame.hCm.toDouble())
    val (maxW, maxH) = PrintMath.largestCm(decoded.sourceWidth, decoded.sourceHeight, (frame.wCm / frame.hCm).toDouble())
    val crop = PrintMath.coverCrop(decoded.sourceWidth, decoded.sourceHeight, frame.wCm.toDouble(), frame.hCm.toDouble())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(WallCopy.thisSize(dpi, PrintQuality.of(dpi)), style = MaterialTheme.typography.bodyMedium)
        Text(WallCopy.printsWellUpTo(PrintMath.floorCm(maxW), PrintMath.floorCm(maxH), inches), style = MaterialTheme.typography.bodySmall)
        if (crop.isCropped) Text(WallCopy.CROPPED, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
    }
}
