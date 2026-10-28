package com.fotoxplorr.app.animate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.aquarium.motionProfile
import com.fotoxplorr.app.aquarium.readAnimatorScale
import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Make a GIF or video": three steps over the pure core of this package. Choose where the frames come
 * from (photos, a part of a video, or a GIF or other moving file already in the library), arrange them,
 * then pick the file type, see the size it is likely to be and save it. The state and its rules are in
 * [AnimateDraft]; the work is [AnimateJob]; what the screen adds is the choosing, the preview and the
 * words.
 *
 * **Step 1, Choose.** Photos are ticked and numbered in the order ticked (two or more). A video clip or
 * a moving file is one row picked from a list.
 *
 * **Step 2, Arrange.** Photos: a strip of the chosen pictures with the one the buttons act on marked by a
 * border and a check, Move left, Move right and Remove for it, its time on screen in two step sizes, Same
 * for all, the speed of the whole slideshow, whether it repeats for ever, and a fade between photos.
 * A video clip: its start and end in seconds and its frames a second, each with buttons in two sizes.
 * A moving file: whether to keep its see-through parts and whether to repeat. Nothing here is a drag:
 * the strip scrolls sideways but every action on a photo is a button, and the numbers are steppers.
 *
 * **Step 3, Make.** GIF, video (MP4) or APNG, the size the planner expects with the range it could
 * fall in and a line that it is a guess, what the planner gave up to fit, and a warning when a GIF
 * would be large. Save starts the job ([AnimateJob.launch]) with progress and Cancel; when it ends the
 * result and an Open button for the new file are shown on every step.
 *
 * **Preview.** The preview button runs the real frame sources small ([AnimatePreview]: at most 24 frames
 * at 240 px) and loops them in place ([FramePlayer]). It is paused when the screen is not resumed, and
 * starts paused when the system's "remove animations" is on, like the aquarium. A choice that changes
 * the picture or the timing makes the preview go away rather than show something stale.
 *
 * **Protected photos.** Photos and videos come only from `env.assets` (the browsable list, TRAPS #33)
 * and pixels only through [ExperimentAnimateHost] over `env.decode`. The job and the preview ask the
 * host again before they read and before they publish. A photo locked while the screen is open drops
 * out of the strip (the draft is checked against the list again whenever the list changes).
 *
 * **Back.** Back steps from Make to Arrange to Choose. From Choose with something picked, or with a save
 * running, the first Back is held with a line saying what leaving does and the next leaves. Leaving
 * stops a save in progress and the preview: their live "may this still be used" check belongs to the
 * host composition that is going away, as the palette builder's recolour does.
 *
 * **Process death.** One `rememberSaveable` keeps the whole [AnimateDraft]: the step, the source, the
 * ticked photos in order with their times, the video and its start, end and rate, the file, the file
 * type and every switch. Not kept: the preview, a save in progress (a killed process ends it and leaves
 * no row, as [AnimateJob] documents), and how the last save ended.
 *
 * **Edge zones (TRAPS #72).** The strip is the one sideways scroller, so it is inset from both sides by
 * [com.fotoxplorr.app.experimental.ShellEdges.inset] (not the whole `clearOfShellEdges`, whose vertical
 * system-bar padding is wrong for something in the middle of a scrolling page). The rest scrolls
 * vertically, and each page ends short of the bottom edge.
 *
 * Nothing here has run on a device. Unchecked: the feel and size of the touch targets, the strip and the
 * grid at a large font size, the preview's speed with 24 frames on a slow phone, and the whole flow
 * against a real library. [AnimateJob] has the list of what the file making has not been tried on.
 */
@Composable
fun AnimatedImages(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val latestEnv by rememberUpdatedState(env)
    // Only its saved-edit lookup is used: it reads files by id and needs nothing live from the env.
    val edits = remember(context) { ExperimentAnimateHost(latestEnv) }
    AnimatedImagesScreen(
        assets = env.assets,
        newHost = { ExperimentAnimateHost(latestEnv) },
        newSink = { AnimateWriter(latestEnv.context) },
        newVideoMaker = { Media3VideoMaker(latestEnv.context) },
        savedEdit = { asset -> edits.savedEdit(asset) },
        animatorScale = readAnimatorScale(context),
        runner = runner,
        onOpenAsset = { asset, list -> latestEnv.onOpenAsset(asset, list) },
        onClose = onClose,
        modifier = modifier,
    )
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/**
 * The screen over its seams: [newHost], [newSink] and [newVideoMaker] make what a job or a preview reads
 * through and writes to (fresh for each use), [savedEdit] is the editor's autosaved recipe for a photo
 * (read off the main thread), [animatorScale] is the system's animator duration scale (0 means the person
 * turned animations off), [runner] is the app's job list (null where there is none, and saving is then
 * not offered), [onOpenAsset] opens the viewer.
 */
@Composable
internal fun AnimatedImagesScreen(
    assets: List<MediaAsset>,
    newHost: () -> AnimateHost,
    newSink: () -> AnimateSink,
    newVideoMaker: () -> VideoMaker?,
    savedEdit: (MediaAsset) -> EditRecipe?,
    animatorScale: Float,
    runner: JobRunner?,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = remember(assets) { AnimateLibrary(assets) }
    val latestLibrary by rememberUpdatedState(library)
    var saved by rememberSaveable(stateSaver = Saver<AnimateDraft, String>(save = { it.encode() }, restore = { AnimateDraft.decode(it) })) {
        mutableStateOf(AnimateDraft())
    }
    val draft = remember(saved, library) { saved.reconciled(library) }
    val scope = rememberCoroutineScope()
    val reduceMotion = remember(animatorScale) { motionProfile(animatorScale).startPaused }

    var notice by remember { mutableStateOf<SaveResult?>(null) }
    var leaveHeld by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PreviewUi>(PreviewUi.Idle) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    // The saves this screen started: only their outcomes are shown here.
    val started = remember { mutableSetOf<String>() }

    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val running = jobs.firstOrNull { it.title == AnimateCopy.JOB_TITLE }

    // Read from the runner itself, not from `running`: two taps before the next redraw both see the old one.
    fun saveRunning(): Boolean = runner?.jobs?.value?.any { it.title == AnimateCopy.JOB_TITLE } == true

    /** The draft as it is now, including an edit made a moment ago in the same handler. */
    fun current(): AnimateDraft = saved.reconciled(latestLibrary)

    fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
        if (preview == PreviewUi.Running) preview = PreviewUi.Idle
    }

    fun edit(change: (AnimateDraft) -> AnimateDraft) {
        val before = current()
        val after = change(before)
        saved = after
        // A press that changed nothing (a stepper at its end) leaves the line on screen.
        if (after != before) leaveHeld = false
    }

    val canVideo by produceState(true, runner) { value = withContext(Dispatchers.Default) { runCatching { newHost().canEncodeVideo() }.getOrDefault(false) } }
    // Which chosen photos have an edit saved for them: read once per set of photos, off the main thread.
    val photoIds = draft.photos.map { it.id }
    val editedIds by produceState(emptySet<Long>(), photoIds, library) {
        value = withContext(Dispatchers.IO) {
            photoIds.filter { id -> library.photo(id)?.let { runCatching { savedEdit(it) }.getOrNull() } != null }.toSet()
        }
    }

    // A photo that has left the library is dropped from the saved draft too, so it cannot come back if the list blinks empty.
    LaunchedEffect(draft) {
        val fresh = current()
        if (fresh != saved) saved = fresh
    }
    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            // Not ours (a save of an earlier screen of this kind, cancelled when it was left): nothing to show.
            if (!started.remove(outcome.id)) return@collect
            notice = outcome.result.fold(
                onSuccess = { value -> (value as? AnimateOutcome.Done)?.let { SaveResult.Saved(it) } },
                onFailure = { error ->
                    when (error) {
                        is CancellationException -> SaveResult.Cancelled
                        is AnimateFailure -> SaveResult.Failed(error.message.orEmpty())
                        else -> SaveResult.Failed(AnimateCopy.FAILED)
                    }
                },
            )
        }
    }
    DisposableEffect(runner) {
        onDispose {
            previewJob?.cancel()
            runner?.jobs?.value?.filter { it.title == AnimateCopy.JOB_TITLE }?.forEach { it.cancel() }
        }
    }
    // A preview belongs to the choices it was made for: when they change the one being made stops.
    val key = draft.previewKey()
    LaunchedEffect(key) {
        if (preview == PreviewUi.Running && previewKey != key) cancelPreview()
    }

    fun startPreview() {
        val now = current()
        if (!now.canContinue || preview == PreviewUi.Running) return
        val forKey = now.previewKey()
        val host = newHost()
        previewKey = forKey
        preview = PreviewUi.Running
        previewJob = scope.launch {
            val made = withContext(Dispatchers.Default) {
                val request = now.toRequest(latestLibrary) { savedEdit(it) }
                if (request == null) PreviewResult.Failed(AnimateScreenCopy.PREVIEW_FAILED) else AnimatePreview.make(request, host)
            }
            preview = when (made) {
                is PreviewResult.Ready -> PreviewUi.Ready(made.set)
                is PreviewResult.Failed -> PreviewUi.Failed(made.message)
            }
            previewJob = null
        }
    }

    fun startSave() {
        val now = current()
        if (runner == null || saving || saveRunning() || !now.canContinue) return
        saving = true
        notice = null
        scope.launch {
            val request = withContext(Dispatchers.Default) { now.toRequest(latestLibrary) { savedEdit(it) } }
            saving = false
            if (request == null) {
                notice = SaveResult.Failed(AnimateCopy.FAILED)
                return@launch
            }
            started += AnimateJob.launch(runner, request, newHost(), newSink(), newVideoMaker()).id
        }
    }

    fun back() {
        val now = current()
        if (now.hasInnerStep) {
            cancelPreview()
            edit { it.backed() }
        } else if ((saveRunning() || now.hasChoices) && !leaveHeld) {
            leaveHeld = true
        } else {
            onClose()
        }
    }

    // Back inside the screen first; with nothing to step back to, nothing picked and no save to protect, this is off and the host closes it.
    BackHandler(enabled = draft.hasInnerStep || running != null || saving || draft.hasChoices) { back() }

    // A preview made for other choices is not shown.
    val shownPreview: PreviewUi = if (previewKey == key) preview else PreviewUi.Idle
    val previewControls = PreviewControls(
        state = shownPreview,
        canMake = draft.canContinue,
        reduceMotion = reduceMotion,
        onMake = ::startPreview,
        onCancel = ::cancelPreview,
    )
    val openAsset = (notice as? SaveResult.Saved)?.let { AnimateLibrary.savedAsset(assets, it.done.uri) }

    Column(modifier.fillMaxSize()) {
        Header(onBack = ::back)
        JobStrip(
            progress = running?.progress,
            running = running != null || saving,
            result = notice,
            leaveHeld = leaveHeld && (running != null || saving),
            onCancel = { running?.cancel?.invoke() },
            openAsset = openAsset,
            onOpen = { openAsset?.let { onOpenAsset(it, assets) } },
        )
        when (draft.page) {
            AnimatePage.SOURCE -> SourcePage(
                library = library,
                draft = draft,
                leaveHeld = leaveHeld && running == null && !saving,
                onSource = { kind -> edit { it.withSource(kind) } },
                onTogglePhoto = { asset -> edit { it.togglePhoto(asset.id.value) } },
                onPickClip = { asset -> edit { it.pickClip(asset) } },
                onPickFile = { asset -> edit { it.pickFile(asset) } },
                onNext = { edit { it.next() } },
                modifier = Modifier.weight(1f),
            )
            AnimatePage.ARRANGE -> ArrangePage(
                library = library,
                draft = draft,
                anyEdits = editedIds.isNotEmpty(),
                preview = previewControls,
                actions = ArrangeActions(
                    onSelect = { i -> edit { it.select(i) } },
                    onMoveLeft = { edit { it.movedBy(-1) } },
                    onMoveRight = { edit { it.movedBy(1) } },
                    onRemove = { edit { it.removed() } },
                    onHold = { delta -> edit { it.holdBy(delta) } },
                    onSameForAll = { edit { it.sameForAll() } },
                    onLoop = { on -> edit { it.withLoop(on) } },
                    onSpeed = { steps -> edit { it.speedBy(steps) } },
                    onFade = { on -> edit { it.withFade(on) } },
                    onUseEdits = { on -> edit { it.withUseEdits(on) } },
                    onKeepTransparency = { on -> edit { it.withKeepTransparency(on) } },
                    onClipStart = { delta -> edit { it.startBy(delta) } },
                    onClipEnd = { delta -> edit { it.endBy(delta, videoLimit(it, latestLibrary)) } },
                    onFps = { delta -> edit { it.fpsBy(delta) } },
                ),
                onNext = { edit { it.next() } },
                modifier = Modifier.weight(1f),
            )
            AnimatePage.OUTPUT -> OutputPage(
                library = library,
                draft = draft,
                estimate = remember(draft.previewKey(), draft.output, library) { AnimateEstimator.of(draft, library) },
                canVideo = canVideo,
                saving = saving || running != null || runner == null,
                preview = previewControls,
                actions = OutputActions(onOutput = { out -> edit { it.withOutput(out) } }, onLoop = { on -> edit { it.withLoop(on) } }),
                onSave = ::startSave,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** How far into the video the end of a part may go: its length, or a fixed limit for a video that does not say. */
private fun videoLimit(draft: AnimateDraft, library: AnimateLibrary): Long =
    draft.clipId?.let(library::video)?.durationMillis?.takeIf { it > 0 } ?: AnimateDraft.UNKNOWN_VIDEO_LIMIT_MS

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnIconButton(Icons.AutoMirrored.Outlined.ArrowBack, AnimateScreenCopy.BACK, onBack)
        Text(
            AnimateScreenCopy.TITLE,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
