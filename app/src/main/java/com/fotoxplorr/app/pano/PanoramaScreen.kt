package com.fotoxplorr.app.pano

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.experimental.ShellEdges
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Stitch a panorama": join two to twelve photos taken side to side into one wide picture. The screen
 * over [PanoramaJob]; the pure rules (what is chosen, in what order, what is saved) are in [PanoramaDraft].
 *
 * **Two steps.** Choose the photos (a thumbnail grid of the gallery's browsable stills, newest first),
 * then arrange them: a strip in sweep order where each photo has Move left, Move right and Remove
 * buttons of 48 dp with words for a screen reader (the way to reorder with no drag at all). The order
 * starts as date taken, then name. **Preview** runs the whole job on a small canvas and shows the result
 * with an "about this size" line; **Save panorama** runs it for real as a [JobRunner] job, so it has the
 * activity shade's progress and Cancel. When the job cannot join a photo the screen says so in plain
 * words ("Photo 3 doesn't overlap enough.") and offers to remove that photo. A saved picture is opened in
 * the gallery by a button, once the gallery's list has it.
 *
 * **Protected photos.** The screen lists only `env.assets` (already browsable-filtered, and without
 * flagged ones while they are blurred), and every pixel and EXIF read is the job's, through
 * [ExperimentPanoramaHost] -> [ExperimentEnv.decode] / [ExperimentEnv.mayExport] (TRAPS #33), asked again
 * right before the file is created. The preview goes through the same host, so a photo locked while the
 * preview ran is refused and never left on screen.
 *
 * **Leaving.** A save in progress is cancelled when this screen is left (as the wall's print is): its live
 * "may this photo still be exported" check belongs to the host composition that is going away, and a
 * job that outlived it would be reading a stale list. Back inside the screen steps from arranging to
 * choosing first; the strip, the preview and a running save stay as they are. The save's progress bar and
 * Cancel show on both steps. While a save runs, the first Back that would leave is held (the line "Leaving
 * this screen stops the save." is then read out) and the next one leaves, so a reflexive second press does
 * not throw away a long save. Only the outcome of a save THIS screen started is shown: a cancel that
 * finishes after the screen was left is not put on the next one.
 *
 * **What a screen reader hears.** How a save ended, a photo that did not join, the saving line and the
 * preview picture are polite live regions; the photo just moved is one too (its new place), and the strip
 * scrolls to keep it in view, so the person can press the same button again. A message that names a photo
 * is shown only while the photos are the ones it was made for.
 *
 * **Process death.** One `rememberSaveable` keeps the step, the chosen photo ids in strip order and
 * whether the order was set by hand ([PanoramaDraft]). Not kept: thumbnails, the preview picture and its
 * size, a message about a photo, a save in progress (a killed process ends it and leaves no row, see
 * [PanoramaWriter]) and the "saved" line. A photo locked or trashed since drops out of the draft.
 *
 * **Edge zones (TRAPS #72).** The strip takes horizontal drags, so it is inset [ShellEdges.inset] from the
 * left and right edges. The picker grid and the arrange page only scroll vertically, and both stop short of
 * the bottom edge (a bar, a spacer) as the hub does.
 *
 * Nothing here has run on a device: touch feel, the strip's inset on a narrow phone, and the layout with
 * a large font are unchecked.
 */
@Composable
fun PanoramaExperience(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val latestEnv by rememberUpdatedState(env)
    PanoramaScreen(
        assets = env.assets,
        newHost = { ExperimentPanoramaHost(latestEnv) },
        newSink = { PanoramaWriter(latestEnv.context) },
        runner = runner,
        onOpenAsset = { asset, list -> latestEnv.onOpenAsset(asset, list) },
        onClose = onClose,
        modifier = modifier,
    )
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/** What the preview is doing. */
private sealed interface Check {
    data object Idle : Check

    data object Running : Check

    class Ready(val ids: List<Long>, val preview: PanoramaPreview) : Check

    class Failed(val ids: List<Long>, val problem: PanoramaProblem) : Check
}

/** How the last save ended. */
private sealed interface SaveResult {
    class Saved(val done: PanoramaOutcome.Done) : SaveResult

    class Failed(val ids: List<Long>, val problem: PanoramaProblem) : SaveResult

    data object Cancelled : SaveResult
}

/**
 * The panorama screen over its seams: [newHost] and [newSink] make what a job reads through and writes to
 * (fresh ones for each run), [runner] is the app's job list (null where there is none, and Save is then not
 * offered), [onOpenAsset] opens the viewer.
 */
@Composable
internal fun PanoramaScreen(
    assets: List<MediaAsset>,
    newHost: () -> PanoramaHost,
    newSink: () -> PanoramaSink,
    runner: JobRunner?,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stills = remember(assets) { assets.filter { !it.isVideo && !it.isTrashed } }
    val byId = remember(stills) { stills.associateBy { it.id.value } }
    var saved by rememberSaveable(stateSaver = Saver<PanoramaDraft, String>(save = { it.encode() }, restore = { PanoramaDraft.decode(it) })) {
        mutableStateOf(PanoramaDraft())
    }
    val draft = remember(saved, byId) { saved.reconciled(byId.keys) }
    val latestDraft by rememberUpdatedState(draft)

    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    var previewProgress by remember { mutableFloatStateOf(0f) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<SaveResult?>(null) }
    // The saves this screen started (job id -> the photos it was made for): only their outcomes are shown.
    val started = remember { mutableMapOf<String, List<Long>>() }
    var leaveHeld by remember { mutableStateOf(false) }

    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val saving = jobs.firstOrNull { it.title == PanoCopy.JOB_TITLE }

    // Read from the runner itself, not from `saving`: two taps before the next redraw both see the old one.
    fun saveRunning(): Boolean = runner?.jobs?.value?.any { it.title == PanoCopy.JOB_TITLE } == true

    fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
        if (check == Check.Running) check = Check.Idle
    }

    // Any change to what is chosen or its order makes the preview and a failure message out of date.
    fun edit(change: (PanoramaDraft) -> PanoramaDraft) {
        val before = latestDraft
        val next = change(before)
        if (next.ids != before.ids) {
            cancelPreview()
            check = Check.Idle
            if (result is SaveResult.Failed) result = null
        }
        saved = next
    }

    fun back() {
        if (latestDraft.hasInnerStep) {
            cancelPreview()
            edit { it.backed() }
        } else if (saveRunning() && !leaveHeld) {
            // Leaving stops the save, and the line saying so is on screen: the next Back is the person's answer.
            leaveHeld = true
        } else {
            onClose()
        }
    }

    // Back inside the screen first; with nothing to step back to and no save to protect this is off and the host closes it.
    BackHandler(enabled = draft.hasInnerStep || saving != null) { back() }

    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            // Not ours (a save of an earlier screen of this kind, cancelled when it was left): nothing to show.
            val ids = started.remove(outcome.id) ?: return@collect
            result = outcome.result.fold(
                onSuccess = { value -> (value as? PanoramaOutcome.Done)?.let { SaveResult.Saved(it) } },
                onFailure = { error ->
                    when (error) {
                        is CancellationException -> SaveResult.Cancelled
                        is PanoramaFailure -> SaveResult.Failed(ids, PanoramaProblem.of(error.message.orEmpty()))
                        else -> SaveResult.Failed(ids, PanoramaProblem.of(PanoCopy.FAILED))
                    }
                },
            )
        }
    }
    DisposableEffect(runner) {
        onDispose {
            previewJob?.cancel()
            runner?.jobs?.value?.filter { it.title == PanoCopy.JOB_TITLE }?.forEach { it.cancel() }
        }
    }

    fun startPreview() {
        val chosen = latestDraft.assets(byId)
        if (!latestDraft.canJoin || chosen.size != latestDraft.ids.size || check == Check.Running || saveRunning()) return
        val ids = latestDraft.ids
        result = null
        previewProgress = 0f
        check = Check.Running
        val host = newHost()
        previewJob = scope.launch {
            val outcome = PanoramaPreviewRun.run(chosen, host) { previewProgress = it }
            check = when (outcome) {
                is PreviewResult.Ready -> Check.Ready(ids, outcome.preview)
                is PreviewResult.Failed -> Check.Failed(ids, outcome.problem)
            }
            previewJob = null
        }
    }

    fun startSave() {
        val chosen = latestDraft.assets(byId)
        if (runner == null || !latestDraft.canJoin || chosen.size != latestDraft.ids.size || saveRunning()) return
        cancelPreview()
        result = null
        started[PanoramaJob.launch(runner, PanoramaRequest(chosen), newHost(), newSink()).id] = latestDraft.ids
    }

    // A preview or a failure belongs to the photos it was made for; after any change it is not shown.
    val shownCheck: Check = when (val c = check) {
        is Check.Ready -> if (c.ids == draft.ids) c else Check.Idle
        is Check.Failed -> if (c.ids == draft.ids) c else Check.Idle
        else -> c
    }

    // A failure that names a photo belongs to the photos it was made for, like a preview does.
    val shownResult = result.takeUnless { it is SaveResult.Failed && it.ids != draft.ids }

    Column(modifier.fillMaxSize()) {
        Header(onBack = ::back)
        JobStrip(
            saving = saving,
            // On the arrange step a failure is said with its Remove button, further down the page.
            result = shownResult.takeUnless { it is SaveResult.Failed && draft.step == PanoramaStep.ARRANGE },
            leaveHeld = leaveHeld,
            onCancel = { saving?.cancel?.invoke() },
            onOpen = { asset -> onOpenAsset(asset, assets) },
            assets = assets,
        )
        when (draft.step) {
            PanoramaStep.PICK -> PickStep(
                stills = stills,
                draft = draft,
                onToggle = { asset -> edit { it.toggled(asset, byId) } },
                onNext = { edit { it.copy(step = PanoramaStep.ARRANGE) } },
                modifier = Modifier.weight(1f),
            )
            PanoramaStep.ARRANGE -> ArrangeStep(
                draft = draft,
                byId = byId,
                check = shownCheck,
                previewProgress = previewProgress,
                saving = saving != null,
                canSave = runner != null,
                problem = (shownCheck as? Check.Failed)?.problem ?: (shownResult as? SaveResult.Failed)?.problem,
                onMove = { index, by -> edit { it.moved(index, by) } },
                onRemove = { id -> edit { it.removed(id) } },
                onSort = { edit { it.sortedByDate(byId) } },
                onPreview = ::startPreview,
                onCancelPreview = ::cancelPreview,
                onSave = ::startSave,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---- header and the save's strip -----------------------------------------------------------------

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanoIconButton(Icons.AutoMirrored.Outlined.ArrowBack, PanoCopy.BACK, onBack)
        Text(
            PanoCopy.TITLE,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** A save's progress and Cancel while it runs, or how the last one ended. Shown on both steps. */
@Composable
private fun JobStrip(
    saving: RunningJob?,
    result: SaveResult?,
    leaveHeld: Boolean,
    onCancel: () -> Unit,
    onOpen: (MediaAsset) -> Unit,
    assets: List<MediaAsset>,
) {
    if (saving == null && result == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saving != null) {
            val progress = saving.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = RoomStyle.InkFaint,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = RoomStyle.InkFaint,
                )
            }
            // The words are the bar's name too, so a screen reader hears them once: here, as the save begins.
            Text(PanoCopy.SAVING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            Text(
                PanoCopy.LEAVING_CANCELS,
                modifier = if (leaveHeld) Modifier.polite() else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (leaveHeld) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            PanoButton(PanoCopy.CANCEL, onCancel)
        } else when (result) {
            is SaveResult.Saved -> {
                Text(PanoCopy.SAVED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                result.done.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
                val asset = PanoramaDraft.savedAsset(assets, result.done.uri)
                if (asset != null) {
                    PanoButton(PanoCopy.OPEN, { onOpen(asset) })
                } else {
                    Text(PanoCopy.NOT_LISTED_YET, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                }
            }
            is SaveResult.Failed -> Text(result.problem.text, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            SaveResult.Cancelled -> Text(PanoCopy.CANCELLED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
    }
}

// ---- step 1: choose ---------------------------------------------------------------------------------

@Composable
private fun PickStep(
    stills: List<MediaAsset>,
    draft: PanoramaDraft,
    onToggle: (MediaAsset) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            PanoCopy.PICK_HINT,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (stills.isEmpty()) {
            Text(
                PanoCopy.NO_PHOTOS,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkFaint,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(stills, key = { it.id.value }) { asset ->
                    val position = draft.ids.indexOf(asset.id.value).takeIf { it >= 0 }?.plus(1)
                    PickCell(asset, position, enabled = position != null || !draft.isFull, onToggle = { onToggle(asset) })
                }
            }
        }
        // Clear of the bottom edge zone, and where Next is: the one thing the first step leads to.
        Row(
            Modifier
                .fillMaxWidth()
                .background(RoomStyle.Surface)
                .navigationBarsPadding()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(PanoCopy.chosen(draft.ids.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            PanoButton(PanoCopy.NEXT, onNext, enabled = draft.canJoin, primary = true)
        }
    }
}

/** One thumbnail of the picker: a checkbox that shows its place in the strip once chosen. */
@Composable
private fun PickCell(asset: MediaAsset, position: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val chosen = position != null
    Box(
        Modifier
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = asset.displayName }
            .toggleable(value = chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .then(if (chosen) Modifier.border(3.dp, Color.White) else Modifier),
    ) {
        MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        if (position != null) NumberBadge(position, Modifier.align(Alignment.TopStart).padding(6.dp))
    }
}

@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier) {
    // 24 dp at least, and wider (a pill) for two digits or a large font, so the number is never wrapped or cut.
    Box(
        modifier.defaultMinSize(24.dp, 24.dp).background(Color.White, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", color = Color.Black, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

// ---- step 2: arrange, preview, save ------------------------------------------------------------------

@Composable
private fun ArrangeStep(
    draft: PanoramaDraft,
    byId: Map<Long, MediaAsset>,
    check: Check,
    previewProgress: Float,
    saving: Boolean,
    canSave: Boolean,
    problem: PanoramaProblem?,
    onMove: (Int, Int) -> Unit,
    onRemove: (Long) -> Unit,
    onSort: () -> Unit,
    onPreview: () -> Unit,
    onCancelPreview: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = remember(draft.ids, byId) { draft.assets(byId) }
    val previewing = check == Check.Running
    val ready = check as? Check.Ready
    // The strip is inset, so about one photo shows at a time on a phone: a photo that was moved is followed.
    val listState = rememberLazyListState()
    var moved by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(moved, draft.ids) {
        val index = draft.ids.indexOf(moved ?: return@LaunchedEffect)
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            PanoCopy.ORDER_HINT,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        // The strip takes horizontal drags, so it keeps clear of the shell's side edge zones (TRAPS #72).
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ShellEdges.inset).testTag(STRIP_TAG),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(chosen, key = { _, asset -> asset.id.value }) { index, asset ->
                StripItem(
                    position = index + 1,
                    asset = asset,
                    canLeft = draft.canMove(index, -1),
                    canRight = draft.canMove(index, +1),
                    flagged = problem?.photo == index + 1,
                    announce = moved == asset.id.value,
                    onLeft = { moved = asset.id.value; onMove(index, -1) },
                    onRight = { moved = asset.id.value; onMove(index, +1) },
                    onRemove = { moved = null; onRemove(asset.id.value) },
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.arranged) PanoButton(PanoCopy.SORT_BY_DATE, { moved = null; onSort() })
            if (!draft.canJoin) Text(PanoCopy.NEED_PHOTOS, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
            if (problem != null) {
                Text(problem.text, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                val photo = problem.photo
                val id = photo?.let { draft.ids.getOrNull(it - 1) }
                if (photo != null && id != null) PanoButton(PanoCopy.remove(photo), { moved = null; onRemove(id) })
            }

            if (previewing) {
                LinearProgressIndicator(
                    progress = { previewProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = RoomStyle.InkFaint,
                )
                Text(PanoCopy.PREVIEWING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
                PanoButton(PanoCopy.CANCEL, onCancelPreview)
            } else {
                PanoButton(PanoCopy.PREVIEW, onPreview, enabled = draft.canJoin && !saving)
            }
            if (ready != null) {
                PreviewPicture(ready.preview.bitmap.asImageBitmap())
                ready.preview.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
            }

            Text(
                ready?.preview?.exportSize?.let { PanoCopy.aboutSize(it.width, it.height) } ?: PanoCopy.upToSize(PanoGeometry.DEFAULT_MAX_PIXELS),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
            if (canSave) PanoButton(PanoCopy.SAVE, onSave, enabled = draft.canJoin && !saving && !previewing, primary = true)
        }
        // Clear of the bottom edge zone, as the hub's last item is.
        Spacer(Modifier.size(72.dp))
    }
}

@Composable
private fun PreviewPicture(image: ImageBitmap) {
    Image(
        bitmap = image,
        contentDescription = PanoCopy.PREVIEW_DESCRIPTION,
        // Polite: the picture appearing is how a screen reader learns the preview is done.
        modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height).background(Color.Black).polite(),
        contentScale = ContentScale.Fit,
    )
}

/** One photo in the strip with its three 48 dp buttons: the way to reorder without dragging. */
@Composable
private fun StripItem(
    position: Int,
    asset: MediaAsset,
    canLeft: Boolean,
    canRight: Boolean,
    flagged: Boolean,
    announce: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.width(STRIP_ITEM_WIDTH)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .semantics {
                    contentDescription = PanoCopy.photoInStrip(position, asset.displayName)
                    // The photo just moved says where it is now; the one it swapped with stays quiet.
                    if (announce) liveRegion = LiveRegionMode.Polite
                }
                .then(if (flagged) Modifier.border(BorderStroke(3.dp, FLAGGED)) else Modifier),
        ) {
            MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
            NumberBadge(position, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PanoIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, PanoCopy.moveLeft(position), onLeft, enabled = canLeft)
            PanoIconButton(Icons.Outlined.Close, PanoCopy.remove(position), onRemove)
            PanoIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, PanoCopy.moveRight(position), onRight, enabled = canRight)
        }
    }
}

// ---- controls ----------------------------------------------------------------------------------------

private val STRIP_ITEM_WIDTH = 152.dp

/** For tests: the strip is a lazy row, so a photo off the screen is not there until it is scrolled to. */
internal const val STRIP_TAG = "panorama-strip"
private val FLAGGED = Color(0xFFFF8A80)

/** Read out by a screen reader when it appears or changes, without cutting off what it is saying. */
private fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A button with words, at least 48 dp tall. */
@Composable
private fun PanoButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContainerColor = RoomStyle.InkFaint.copy(alpha = 0.3f),
                disabledContentColor = RoomStyle.InkFaint,
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
            border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
        ) { Text(label) }
    }
}

/** A 48 dp icon button that always says what it does. */
@Composable
private fun PanoIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint.copy(alpha = 0.4f)),
    ) { Icon(icon, contentDescription = description) }
}
