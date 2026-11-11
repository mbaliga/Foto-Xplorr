package com.fotoxplorr.app.animate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.ShellEdges
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.RoomStyle

/** For tests: the photo grid of step 1, the strip of step 2 and the picture of the preview player. */
internal const val ANIM_GRID_TAG = "animate-photo-grid"
internal const val ANIM_STRIP_TAG = "animate-strip"
internal const val ANIM_PLAYER_TAG = "animate-player"

// The pages of the screen. State lives in the screen; these draw it and say what was pressed.

/**
 * A picture whose words are said by the row or cell around it: [MediaImage] describes itself with the file's
 * name, which would be read a second time beside the cell's own description.
 */
private fun Modifier.silent(): Modifier = clearAndSetSemantics {}

// ---- the head of each step ----------------------------------------------------------------------------------

/** The step's title and, under it, the one button that goes on, so it is never scrolled out of reach. */
@Composable
internal fun StepHead(
    step: Int,
    name: String,
    action: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnHeading(AnimateScreenCopy.stepTitle(step, name))
        if (note != null) Text(note, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        AnButton(action, onAction, modifier = Modifier.fillMaxWidth(), enabled = actionEnabled, primary = true)
    }
}

// ---- step 1: choose ---------------------------------------------------------------------------------------------

@Composable
internal fun SourcePage(
    library: AnimateLibrary,
    draft: AnimateDraft,
    leaveHeld: Boolean,
    onSource: (AnimateSource) -> Unit,
    onTogglePhoto: (MediaAsset) -> Unit,
    onPickClip: (MediaAsset) -> Unit,
    onPickFile: (MediaAsset) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        val note = when {
            leaveHeld -> AnimateScreenCopy.LEAVE_FORGETS
            draft.source == AnimateSource.PHOTOS -> if (draft.photos.size >= AnimateDraft.MIN_PHOTOS) AnimateScreenCopy.chosen(draft.photos.size) else AnimateScreenCopy.NEED_TWO
            else -> null
        }
        StepHead(1, AnimateScreenCopy.STEP_SOURCE, AnimateScreenCopy.NEXT, draft.canContinue, onNext, note = note)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AnimateScreenCopy.SRC_DESCRIPTION, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnChoice(AnimateScreenCopy.SRC_PHOTOS, draft.source == AnimateSource.PHOTOS, { onSource(AnimateSource.PHOTOS) }, Modifier.weight(1f))
                AnChoice(AnimateScreenCopy.SRC_CLIP, draft.source == AnimateSource.CLIP, { onSource(AnimateSource.CLIP) }, Modifier.weight(1f))
                AnChoice(AnimateScreenCopy.SRC_FILE, draft.source == AnimateSource.FILE, { onSource(AnimateSource.FILE) }, Modifier.weight(1f))
            }
            Text(
                when (draft.source) {
                    AnimateSource.PHOTOS -> AnimateScreenCopy.PHOTOS_HINT
                    AnimateSource.CLIP -> AnimateScreenCopy.CLIP_HINT
                    AnimateSource.FILE -> AnimateScreenCopy.FILE_HINT
                },
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
        }
        Spacer(Modifier.size(6.dp))
        when (draft.source) {
            AnimateSource.PHOTOS -> PhotoChooser(library.photos, draft.photos.map { it.id }, onTogglePhoto, Modifier.weight(1f))
            AnimateSource.CLIP -> RowChooser(library.videos, draft.clipId, AnimateScreenCopy.NO_VIDEOS, { a -> AnimateScreenCopy.clipRow(a.displayName, a.durationMillis) }, onPickClip, Modifier.weight(1f))
            AnimateSource.FILE -> RowChooser(library.files, draft.fileId, AnimateScreenCopy.NO_FILES, { a -> a.displayName }, onPickFile, Modifier.weight(1f))
        }
    }
}

/** A grid of photos where the ticked ones are numbered in the order ticked: a check box with a border and a number, so the state is not colour alone. */
@Composable
private fun PhotoChooser(photos: List<MediaAsset>, chosen: List<Long>, onToggle: (MediaAsset) -> Unit, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) {
        Text(AnimateScreenCopy.NO_PHOTOS, modifier = modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxWidth().testTag(ANIM_GRID_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(photos, key = { it.id.value }) { asset ->
            val position = chosen.indexOf(asset.id.value).takeIf { it >= 0 }?.plus(1)
            PhotoCell(asset, position, canAdd = chosen.size < AnimateDraft.MAX_PHOTOS, onToggle = { onToggle(asset) })
        }
        item(key = "end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(ShellEdges.inset)) }
    }
}

@Composable
private fun PhotoCell(asset: MediaAsset, position: Int?, canAdd: Boolean, onToggle: () -> Unit) {
    val ticked = position != null
    val enabled = ticked || canAdd
    Box(
        Modifier
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = asset.displayName }
            .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .then(if (ticked) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        MediaImage(asset, Modifier.fillMaxSize().silent(), ContentScale.Crop)
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        if (position != null) NumberBadge(position, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
    // 24 dp at least, and wider for two digits or a large font, so the number is never wrapped or cut.
    Box(
        modifier.defaultMinSize(24.dp, 24.dp).background(Color.White, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", color = Color.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A list where exactly one row is picked (a video, a moving file): a radio button with a check mark. */
@Composable
private fun RowChooser(
    items: List<MediaAsset>,
    pickedId: Long?,
    empty: String,
    label: (MediaAsset) -> String,
    onPick: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Text(empty, modifier = modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items.size, key = { items[it].id.value }) { i ->
            val asset = items[i]
            val picked = asset.id.value == pickedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .semantics(mergeDescendants = true) { contentDescription = label(asset) }
                    .selectable(selected = picked, role = Role.RadioButton, onClick = { onPick(asset) })
                    .then(if (picked) Modifier.border(2.dp, Color.White) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MediaImage(asset, Modifier.size(64.dp).silent(), ContentScale.Crop)
                Text(label(asset), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (picked) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        item(key = "end") { Spacer(Modifier.size(ShellEdges.inset)) }
    }
}

// ---- step 2: arrange ---------------------------------------------------------------------------------------------

/** What the arrange page can do. One lambda for each button. */
internal class ArrangeActions(
    val onSelect: (Int) -> Unit,
    val onMoveLeft: () -> Unit,
    val onMoveRight: () -> Unit,
    val onRemove: () -> Unit,
    val onHold: (Int) -> Unit,
    val onSameForAll: () -> Unit,
    val onLoop: (Boolean) -> Unit,
    val onSpeed: (Int) -> Unit,
    val onFade: (Boolean) -> Unit,
    val onUseEdits: (Boolean) -> Unit,
    val onKeepTransparency: (Boolean) -> Unit,
    val onClipStart: (Long) -> Unit,
    val onClipEnd: (Long) -> Unit,
    val onFps: (Int) -> Unit,
)

@Composable
internal fun ArrangePage(
    library: AnimateLibrary,
    draft: AnimateDraft,
    anyEdits: Boolean,
    preview: PreviewControls,
    actions: ArrangeActions,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        StepHead(2, AnimateScreenCopy.STEP_ARRANGE, AnimateScreenCopy.NEXT, draft.canContinue, onNext)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewSection(preview)
            when (draft.source) {
                AnimateSource.PHOTOS -> PhotosArrange(library, draft, anyEdits, actions)
                AnimateSource.CLIP -> ClipArrange(library, draft, actions)
                AnimateSource.FILE -> FileArrange(library, draft, actions)
            }
            Spacer(Modifier.size(ShellEdges.inset))
        }
    }
}

@Composable
private fun PhotosArrange(library: AnimateLibrary, draft: AnimateDraft, anyEdits: Boolean, a: ArrangeActions) {
    AnHeading(AnimateScreenCopy.STRIP)
    // A sideways strip drags: it is kept out of the shell's edge zones (TRAPS #72) and every photo has buttons below as well.
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = ShellEdges.inset).testTag(ANIM_STRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(draft.photos, key = { i, p -> "$i-${p.id}" }) { i, p ->
            val asset = library.photo(p.id)
            if (asset != null) StripCell(asset, i + 1, draft.photos.size, p.holdMs, selected = i == draft.selected, onClick = { a.onSelect(i) })
        }
    }
    if (draft.photos.getOrNull(draft.selected) != null) {
        val p = draft.photos[draft.selected]
        AnHeading(AnimateScreenCopy.frameTitle(draft.selected + 1, draft.photos.size))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnButton(AnimateScreenCopy.MOVE_LEFT, a.onMoveLeft, enabled = draft.canMoveLeft)
            AnButton(AnimateScreenCopy.MOVE_RIGHT, a.onMoveRight, enabled = draft.canMoveRight)
            AnButton(AnimateScreenCopy.REMOVE, a.onRemove, enabled = draft.canRemove)
        }
        if (!draft.canRemove) AnCaption(AnimateScreenCopy.KEEP_TWO)
        val fine = AnimateDraft.HOLD_FINE_MS
        val coarse = AnimateDraft.HOLD_COARSE_MS
        StepRow(
            label = AnimateScreenCopy.HOLD,
            value = AnimateScreenCopy.seconds(p.holdMs.toLong()),
            steps = listOf(
                Step(AnimateScreenCopy.shorterText(coarse), AnimateScreenCopy.shorterWords(coarse), draft.canHoldBy(-coarse)) { a.onHold(-coarse) },
                Step(AnimateScreenCopy.shorterText(fine), AnimateScreenCopy.shorterWords(fine), draft.canHoldBy(-fine)) { a.onHold(-fine) },
                Step(AnimateScreenCopy.longerText(fine), AnimateScreenCopy.longerWords(fine), draft.canHoldBy(fine)) { a.onHold(fine) },
                Step(AnimateScreenCopy.longerText(coarse), AnimateScreenCopy.longerWords(coarse), draft.canHoldBy(coarse)) { a.onHold(coarse) },
            ),
        )
        AnButton(AnimateScreenCopy.SAME_FOR_ALL, a.onSameForAll, enabled = draft.canSameForAll)
    }
    StepRow(
        label = AnimateScreenCopy.SPEED,
        value = AnimateScreenCopy.speedValue(draft.speedPercent),
        steps = listOf(
            Step("-${AnimateDraft.SPEED_STEP}", AnimateScreenCopy.SLOWER, draft.speedBy(-1) != draft) { a.onSpeed(-1) },
            Step("+${AnimateDraft.SPEED_STEP}", AnimateScreenCopy.FASTER, draft.speedBy(1) != draft) { a.onSpeed(1) },
        ),
    )
    AnToggle(AnimateScreenCopy.LOOP, draft.loop, a.onLoop)
    AnToggle(AnimateScreenCopy.FADE, draft.fade, a.onFade)
    if (anyEdits) AnToggle(AnimateScreenCopy.USE_EDITS, draft.useEdits, a.onUseEdits)
}

/** One photo of the strip: its picture, its place, its time on screen, and a border and a check when it is the one the buttons act on. */
@Composable
private fun StripCell(asset: MediaAsset, position: Int, count: Int, holdMs: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = AnimateScreenCopy.frameDescription(position, count, asset.displayName, holdMs)
                stateDescription = if (selected) AnimateScreenCopy.SELECTED else AnimateScreenCopy.NOT_SELECTED
            }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint),
    ) {
        MediaImage(asset, Modifier.fillMaxSize().silent(), ContentScale.Crop)
        NumberBadge(position, Modifier.align(Alignment.TopStart).padding(4.dp))
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            AnimateScreenCopy.seconds(holdMs.toLong()),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun ClipArrange(library: AnimateLibrary, draft: AnimateDraft, a: ArrangeActions) {
    val video = draft.clipId?.let(library::video)
    val limit = video?.durationMillis?.takeIf { it > 0 } ?: AnimateDraft.UNKNOWN_VIDEO_LIMIT_MS
    if (video != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaImage(video, Modifier.size(72.dp).silent(), ContentScale.Crop)
            Text(AnimateScreenCopy.clipRow(video.displayName, video.durationMillis), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val fine = AnimateDraft.CLIP_FINE_MS
    val coarse = AnimateDraft.CLIP_COARSE_MS
    StepRow(
        label = AnimateScreenCopy.CLIP_START,
        value = AnimateScreenCopy.seconds(draft.clipStartMs),
        steps = listOf(
            Step(AnimateScreenCopy.earlierText(coarse), AnimateScreenCopy.earlierWords(AnimateScreenCopy.CLIP_START, coarse), draft.startBy(-coarse) != draft) { a.onClipStart(-coarse) },
            Step(AnimateScreenCopy.earlierText(fine), AnimateScreenCopy.earlierWords(AnimateScreenCopy.CLIP_START, fine), draft.startBy(-fine) != draft) { a.onClipStart(-fine) },
            Step(AnimateScreenCopy.laterText(fine), AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, fine), draft.startBy(fine) != draft) { a.onClipStart(fine) },
            Step(AnimateScreenCopy.laterText(coarse), AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, coarse), draft.startBy(coarse) != draft) { a.onClipStart(coarse) },
        ),
    )
    StepRow(
        label = AnimateScreenCopy.CLIP_END,
        value = AnimateScreenCopy.seconds(draft.clipEndMs),
        steps = listOf(
            Step(AnimateScreenCopy.earlierText(coarse), AnimateScreenCopy.earlierWords(AnimateScreenCopy.CLIP_END, coarse), draft.endBy(-coarse, limit) != draft) { a.onClipEnd(-coarse) },
            Step(AnimateScreenCopy.earlierText(fine), AnimateScreenCopy.earlierWords(AnimateScreenCopy.CLIP_END, fine), draft.endBy(-fine, limit) != draft) { a.onClipEnd(-fine) },
            Step(AnimateScreenCopy.laterText(fine), AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_END, fine), draft.endBy(fine, limit) != draft) { a.onClipEnd(fine) },
            Step(AnimateScreenCopy.laterText(coarse), AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_END, coarse), draft.endBy(coarse, limit) != draft) { a.onClipEnd(coarse) },
        ),
    )
    Text(AnimateScreenCopy.clipLength(draft.clipLengthMs), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
    StepRow(
        label = AnimateScreenCopy.CLIP_FPS,
        value = AnimateScreenCopy.fpsValue(draft.clipFps),
        steps = listOf(
            Step("-5", AnimateScreenCopy.fewerWords(5), draft.fpsBy(-5) != draft) { a.onFps(-5) },
            Step("-1", AnimateScreenCopy.fewerWords(1), draft.fpsBy(-1) != draft) { a.onFps(-1) },
            Step("+1", AnimateScreenCopy.moreWords(1), draft.fpsBy(1) != draft) { a.onFps(1) },
            Step("+5", AnimateScreenCopy.moreWords(5), draft.fpsBy(5) != draft) { a.onFps(5) },
        ),
    )
    AnCaption(AnimateScreenCopy.clipFrames(AnimateEstimator.clipFrames(draft.clipLengthMs, draft.clipFps)))
    AnToggle(AnimateScreenCopy.LOOP, draft.loop, a.onLoop)
}

@Composable
private fun FileArrange(library: AnimateLibrary, draft: AnimateDraft, a: ArrangeActions) {
    val file = draft.fileId?.let(library::file)
    if (file != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaImage(file, Modifier.size(72.dp).silent(), ContentScale.Crop)
            Text(file.displayName, style = MaterialTheme.typography.bodyMedium)
        }
        AnCaption(AnimateScreenCopy.FILE_PICKED)
    }
    AnToggle(AnimateScreenCopy.KEEP_SEE_THROUGH, draft.keepTransparency, a.onKeepTransparency)
    AnToggle(AnimateScreenCopy.LOOP, draft.loop, a.onLoop)
}

/** One button of a stepper row: the short [text] on the button, the long [description] for a screen reader. */
internal class Step(val text: String, val description: String, val enabled: Boolean, val onClick: () -> Unit)

/** A label with its value (read out politely when it changes) and a row of 48 dp buttons that change it: the way to set a number without a drag. */
@Composable
private fun StepRow(label: String, value: String, steps: List<Step>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { AnStepButton(it.text, it.description, it.onClick, it.enabled) }
        }
    }
}

// ---- step 3: make --------------------------------------------------------------------------------------------------

/** What the make page can do. */
internal class OutputActions(
    val onOutput: (AnimOutput) -> Unit,
    val onLoop: (Boolean) -> Unit,
)

@Composable
internal fun OutputPage(
    library: AnimateLibrary,
    draft: AnimateDraft,
    estimate: AnimateEstimate?,
    canVideo: Boolean,
    saving: Boolean,
    preview: PreviewControls,
    actions: OutputActions,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        val blocked = draft.output.isVideo && !canVideo
        StepHead(
            3, AnimateScreenCopy.STEP_OUTPUT, AnimateScreenCopy.SAVE,
            actionEnabled = !saving && draft.canContinue && !blocked,
            onAction = onSave,
            note = if (blocked) AnimateCopy.NO_VIDEO_ENCODER else null,
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(AnimateScreenCopy.OUT_DESCRIPTION, style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnChoice(AnimateScreenCopy.OUT_GIF, draft.output == AnimOutput.GIF, { actions.onOutput(AnimOutput.GIF) }, Modifier.fillMaxWidth(), note = AnimateScreenCopy.OUT_GIF_NOTE)
                AnChoice(
                    AnimateScreenCopy.OUT_VIDEO, draft.output == AnimOutput.VIDEO, { actions.onOutput(AnimOutput.VIDEO) }, Modifier.fillMaxWidth(),
                    note = if (canVideo) AnimateScreenCopy.OUT_VIDEO_NOTE else AnimateCopy.NO_VIDEO_ENCODER,
                    enabled = canVideo || draft.output == AnimOutput.VIDEO,
                )
                AnChoice(AnimateScreenCopy.OUT_APNG, draft.output == AnimOutput.APNG, { actions.onOutput(AnimOutput.APNG) }, Modifier.fillMaxWidth(), note = AnimateScreenCopy.OUT_APNG_NOTE)
            }
            EstimateBlock(draft, estimate, library)
            if (draft.output.isVideo) AnCaption(AnimateScreenCopy.VIDEO_PLAYS_ONCE)
            PreviewSection(preview)
            Spacer(Modifier.size(ShellEdges.inset))
        }
    }
}

@Composable
private fun EstimateBlock(draft: AnimateDraft, estimate: AnimateEstimate?, library: AnimateLibrary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnHeading(AnimateScreenCopy.ESTIMATE_HEADING)
        if (estimate == null) {
            val file = draft.fileId?.let(library::file)
            if (file != null && file.sizeBytes > 0) Text(AnimateScreenCopy.sourceNow(file.sizeBytes), style = MaterialTheme.typography.bodyMedium)
            AnCaption(AnimateScreenCopy.SIZE_UNKNOWN)
            return@Column
        }
        Text(AnimateScreenCopy.estimate(estimate.bytes.typicalBytes), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        AnCaption(AnimateScreenCopy.estimateRange(estimate.bytes.lowBytes, estimate.bytes.highBytes))
        AnCaption(AnimateScreenCopy.playInfo(estimate.frames, estimate.width, estimate.height, estimate.playMs))
        AnCaption(AnimateCopy.SIZE_IS_A_GUESS)
        if (estimate.largeGif) Text(AnimateScreenCopy.BIG_GIF, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        estimate.steps.forEach { AnCaption(AnimateCopy.planStep(it)) }
    }
}

// ---- the preview --------------------------------------------------------------------------------------------------

/** What the preview is doing. */
internal sealed interface PreviewUi {
    data object Idle : PreviewUi

    data object Running : PreviewUi

    class Ready(val set: PreviewSet) : PreviewUi

    class Failed(val message: String) : PreviewUi
}

/** The preview section's state and buttons. [reduceMotion] is the system's "remove animations": the player then starts paused. */
internal class PreviewControls(
    val state: PreviewUi,
    val canMake: Boolean,
    val reduceMotion: Boolean,
    val onMake: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
internal fun PreviewSection(p: PreviewControls) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnHeading(AnimateScreenCopy.PREVIEW_HEADING)
        when (val s = p.state) {
            PreviewUi.Idle -> AnButton(AnimateScreenCopy.PREVIEW_MAKE, p.onMake, enabled = p.canMake)
            PreviewUi.Running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
                Text(AnimateScreenCopy.PREVIEW_WORKING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
                AnButton(AnimateScreenCopy.CANCEL, p.onCancel)
            }
            is PreviewUi.Ready -> {
                FramePlayer(s.set, startPaused = p.reduceMotion)
                AnCaption(AnimateScreenCopy.PREVIEW_ROUGH)
                AnButton(AnimateScreenCopy.PREVIEW_REMAKE, p.onMake, enabled = p.canMake)
            }
            is PreviewUi.Failed -> {
                Text(s.message, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                AnButton(AnimateScreenCopy.PREVIEW_MAKE, p.onMake, enabled = p.canMake)
            }
        }
    }
}

// ---- the job's strip -----------------------------------------------------------------------------------------------

/** How the last save ended. */
internal sealed interface SaveResult {
    class Saved(val done: AnimateOutcome.Done) : SaveResult

    class Failed(val message: String) : SaveResult

    data object Cancelled : SaveResult
}

/** The save's progress and Cancel while it runs, or how the last one ended. Shown on every page. */
@Composable
internal fun JobStrip(
    progress: Float?,
    running: Boolean,
    result: SaveResult?,
    leaveHeld: Boolean,
    onCancel: () -> Unit,
    openAsset: MediaAsset?,
    onOpen: () -> Unit,
) {
    if (!running && result == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            }
            Text(AnimateScreenCopy.MAKING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            Text(
                AnimateScreenCopy.LEAVING_CANCELS,
                modifier = if (leaveHeld) Modifier.polite() else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (leaveHeld) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            AnButton(AnimateScreenCopy.CANCEL, onCancel)
        } else when (result) {
            is SaveResult.Saved -> {
                val d = result.done
                Text(d.message, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                AnCaption(AnimateScreenCopy.saved(d.frames, d.width, d.height, d.playMs, d.bytes))
                AnCaption(AnimateScreenCopy.SAVED_NOTE)
                d.notes.forEach { AnCaption(it) }
                if (openAsset != null) {
                    AnButton(AnimateScreenCopy.OPEN, onOpen)
                } else {
                    AnCaption(AnimateScreenCopy.NOT_LISTED)
                }
            }
            is SaveResult.Failed -> Text(result.message, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            SaveResult.Cancelled -> Text(AnimateScreenCopy.CANCELLED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
    }
}
