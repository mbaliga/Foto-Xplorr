package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
 * "Palette builder": pull a colour palette out of one to six photos, or grow one from a colour; adjust it by hand;
 * check it for text and for colour-blind eyes; recolour a photo toward it; save it; share it. The screen over the
 * pure core in this package; the rules of the screen (pages, edits, undo, what is saved) are in [PaletteDraft].
 *
 * **Pages.** Start (how to begin, Keep editing, the saved palettes), Photos (tick one to six), Colour (type a hex),
 * Edit (the palette), Recolour (pick a photo, preview, save a copy). Back steps to the page before; the first Back
 * from the start page of an unsaved palette is held, with a line saying so, and the next one leaves.
 *
 * **Editing without a drag.** Tap a swatch to pick it. Hue, saturation and lightness each have four buttons (two
 * sizes each way), and Lock, Move left, Move right, Add and Remove act on the picked swatch. A harmony button
 * rebuilds the palette around the picked swatch and leaves locked ones; Undo takes back a harmony or a Remove.
 * Every button is 48 dp or more, has words, and a picked or locked swatch shows a mark as well as a border.
 *
 * **Protected photos.** Photos come only from `env.assets` (the browsable list, TRAPS #33) and pixels only through
 * the host ([PaletteHost] over [ExperimentEnv.decode]). The colours of a palette remember the photos they came from,
 * and a share or a recolour asks [ExportGate] again at the moment it starts, so photos locked while the builder
 * was open stop the next share. The recolour job asks again before it reads and before it writes
 * ([RecolourJob]); the preview likewise. Saved palettes are the person's colours and carry no photo.
 *
 * **Process death.** One `rememberSaveable` keeps the whole [PaletteDraft]: page, palette and locks, picked
 * swatch, source photos, ticked photos, recolour settings, undo list. Not kept: thumbnails, the preview picture,
 * a recolour in progress (a killed process ends it and leaves no file), a delete waiting for a yes, and the hex
 * or name text being typed (the name is kept per palette, the hex is). The saved list is read again on open.
 *
 * **Leaving.** A recolour in progress is cancelled when this screen is left, as the panorama's save is: its live
 * "may this photo still be exported" check belongs to the host composition that is going away. While one runs,
 * the first Back that would leave is held and the next leaves.
 *
 * Edge zones (TRAPS #72): nothing here takes a horizontal drag. The pages scroll vertically and stop short of the
 * bottom edge. Nothing here has run on a device: touch feel, the grid at a large font size, and the swatch strip
 * and contrast grid on a narrow phone are unchecked.
 */
@Composable
fun PaletteBuilder(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val latestEnv by rememberUpdatedState(env)
    val library = remember(context) { PaletteStore(PaletteStore.directoryIn(context.applicationContext.filesDir)) }
    val share = remember(context) { AndroidPaletteShare(context) }
    PaletteBuilderScreen(
        assets = env.assets,
        newHost = { ExperimentPaletteHost(latestEnv) },
        newSink = { EditorRecolourSink(latestEnv.context) },
        library = library,
        share = share,
        runner = runner,
        onOpenAsset = { asset, list -> latestEnv.onOpenAsset(asset, list) },
        onClose = onClose,
        modifier = modifier,
    )
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/** What the recolour preview is doing. */
internal sealed interface PreviewState {
    data object Idle : PreviewState

    data object Running : PreviewState

    class Ready(val key: PreviewKey, val bitmap: Bitmap) : PreviewState

    class Failed(val key: PreviewKey, val message: String) : PreviewState
}

/** How the last recolour ended. */
private sealed interface CopyResult {
    class Saved(val done: RecolourOutcome.Done) : CopyResult

    class Failed(val message: String) : CopyResult

    data object Cancelled : CopyResult
}

/**
 * The builder over its seams: [newHost] and [newSink] make what a job reads through and writes to (fresh ones for
 * each use), [library] keeps the saved palettes, [share] opens the share sheet, [runner] is the app's job list
 * (null where there is none, and the recolour copy is then not offered), [onOpenAsset] opens the viewer.
 */
@Composable
internal fun PaletteBuilderScreen(
    assets: List<MediaAsset>,
    newHost: () -> PaletteHost,
    newSink: () -> RecolourSink,
    library: PaletteLibrary,
    share: PaletteShare,
    runner: JobRunner?,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stills = remember(assets) { PaletteDraft.stills(assets) }
    val byId = remember(stills) { stills.associateBy { it.id.value } }
    val latestById by rememberUpdatedState(byId)
    var saved by rememberSaveable(stateSaver = Saver<PaletteDraft, String>(save = { it.encode() }, restore = { PaletteDraft.decode(it) })) {
        mutableStateOf(PaletteDraft())
    }
    val draft = remember(saved, byId) { saved.reconciled(byId.keys) }
    val scope = rememberCoroutineScope()

    var hex by rememberSaveable { mutableStateOf(PaletteBuilderCopy.COLOUR_DEFAULT) }
    var savedList by remember { mutableStateOf<SavedPalettes?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    var readProgress by remember { mutableFloatStateOf(0f) }
    var readError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PreviewState>(PreviewState.Idle) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<CopyResult?>(null) }
    // The recolours this screen started: only their outcomes are shown here.
    val started = remember { mutableSetOf<String>() }
    var leaveHeld by remember { mutableStateOf(false) }

    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val copying = jobs.firstOrNull { it.title == PaletteBuilderCopy.JOB_TITLE }

    // Read from the runner itself, not from `copying`: two taps before the next redraw both see the old one.
    fun copyRunning(): Boolean = runner?.jobs?.value?.any { it.title == PaletteBuilderCopy.JOB_TITLE } == true

    /** The draft as it is now, including an edit made a moment ago in the same handler. */
    fun current(): PaletteDraft = saved.reconciled(latestById.keys)

    /** The names in the saved list, so a new palette does not take one by default and replace it on Save. */
    fun savedNames(): List<String> = savedList?.palettes?.map { it.name }.orEmpty()

    fun cancelReading() {
        readJob?.cancel()
        readJob = null
    }

    fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
        if (preview == PreviewState.Running) preview = PreviewState.Idle
    }

    fun edit(change: (PaletteDraft) -> PaletteDraft) {
        val before = current()
        val after = change(before)
        saved = after
        // A press that changed nothing (a stepper at its end, a name left as it was) leaves the line on screen.
        if (after != before) {
            leaveHeld = false
            notice = null
        }
    }

    /** Whether the photos this palette came from may still be published, as a sentence when not. */
    fun gate(): String? {
        val host = newHost()
        return ExportGate.refusal(current().sources) { id -> latestById[id]?.let(host::mayExport) == true }
    }

    fun back() {
        val now = current()
        if (now.hasInnerStep) {
            cancelReading()
            cancelPreview()
            readError = null
            edit { it.backed() }
        } else if (copyRunning() && !leaveHeld) {
            leaveHeld = true
        } else if (now.palette != null && now.dirty && !leaveHeld) {
            leaveHeld = true
        } else {
            onClose()
        }
    }

    // Back inside the screen first; with nothing to step back to, nothing unsaved and no copy to protect, this is off and the host closes it.
    BackHandler(enabled = draft.hasInnerStep || copying != null || (draft.palette != null && draft.dirty)) { back() }

    // A ticked photo that has left the library is dropped from the saved draft too, so it cannot come back ticked if
    // the list blinks empty. Recomputed from the draft as it is by then, never from the one this composition saw.
    LaunchedEffect(draft) {
        val fresh = current()
        if (fresh != saved) saved = fresh
    }
    LaunchedEffect(library) { savedList = library.load() }
    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            // Not ours (a copy of an earlier screen of this kind, cancelled when it was left): nothing to show.
            if (!started.remove(outcome.id)) return@collect
            result = outcome.result.fold(
                onSuccess = { value -> (value as? RecolourOutcome.Done)?.let { CopyResult.Saved(it) } },
                onFailure = { error ->
                    when (error) {
                        is CancellationException -> CopyResult.Cancelled
                        is RecolourFailure -> CopyResult.Failed(error.message.orEmpty())
                        else -> CopyResult.Failed(PaletteBuilderCopy.COPY_FAILED)
                    }
                },
            )
        }
    }
    DisposableEffect(runner) {
        onDispose {
            readJob?.cancel()
            previewJob?.cancel()
            runner?.jobs?.value?.filter { it.title == PaletteBuilderCopy.JOB_TITLE }?.forEach { it.cancel() }
        }
    }

    fun startReading() {
        val now = current()
        val chosen = now.picks.mapNotNull(latestById::get)
        if (chosen.isEmpty() || chosen.size != now.picks.size || readJob != null) return
        readError = null
        readProgress = 0f
        val host = newHost()
        readJob = scope.launch {
            val outcome = withContext(Dispatchers.Default) { PhotoPalettes.build(chosen, host) { readProgress = it } }
            readJob = null
            when (outcome) {
                is PhotoPaletteResult.Ready -> edit { it.started(outcome.palette, outcome.sources, savedNames()) }
                is PhotoPaletteResult.Failed -> readError = outcome.message
            }
        }
    }

    fun startFromColour() {
        val argb = ColorMath.parseHex(hex) ?: return
        edit { it.fromColour(argb, savedNames()) }
    }

    fun savePalette(name: String) {
        val named = current().renamed(name)
        val palette = named.palette ?: return
        saved = named
        scope.launch {
            val outcome = library.save(palette)
            notice = when (outcome) {
                is StoreSave.Saved -> if (outcome.replaced) PaletteBuilderCopy.SAVED_REPLACED else PaletteBuilderCopy.SAVED
                StoreSave.Full -> PaletteBuilderCopy.SAVE_FULL
                StoreSave.Failed -> PaletteBuilderCopy.SAVE_FAILED
            }
            if (outcome is StoreSave.Saved && current().palette == palette) saved = current().savedNow()
            savedList = library.load()
        }
    }

    fun deleteSaved(name: String) {
        confirmDelete = null
        scope.launch {
            library.delete(name)
            savedList = library.load()
        }
    }

    fun shareText(format: ExportFormat) {
        val palette = current().palette ?: return
        val refusal = gate()
        notice = when {
            refusal != null -> refusal
            !share.text(palette, format) -> PaletteBuilderCopy.SHARE_FAILED
            else -> null
        }
    }

    fun shareCard() {
        val palette = current().palette ?: return
        val refusal = gate()
        if (refusal != null) {
            notice = refusal
            return
        }
        scope.launch {
            // Asked again once the card is drawn: a photo locked while it was being made is refused.
            val started = share.card(palette) { gate() == null }
            notice = if (started) null else gate() ?: PaletteBuilderCopy.SHARE_FAILED
        }
    }

    fun startPreview() {
        val now = current()
        val palette = now.palette ?: return
        val asset = now.recolourPick?.let(latestById::get) ?: return
        if (preview == PreviewState.Running || copyRunning()) return
        val refusal = gate()
        val key = PreviewKey(asset.id.value, palette.argbs, now.recolour)
        if (refusal != null) {
            preview = PreviewState.Failed(key, refusal)
            return
        }
        val host = newHost()
        preview = PreviewState.Running
        previewJob = scope.launch {
            val made = withContext(Dispatchers.Default) { RecolourPreview.make(asset, key.colours, key.settings, host) }
            preview = when (made) {
                is PreviewResult.Ready -> PreviewState.Ready(key, made.bitmap)
                is PreviewResult.Failed -> PreviewState.Failed(key, made.message)
            }
            previewJob = null
        }
    }

    fun startCopy() {
        val now = current()
        val palette = now.palette ?: return
        val asset = now.recolourPick?.let(latestById::get) ?: return
        if (runner == null || copyRunning()) return
        val refusal = gate()
        if (refusal != null) {
            result = CopyResult.Failed(refusal)
            return
        }
        cancelPreview()
        result = null
        val request = RecolourRequest(asset, palette.argbs, now.recolour)
        started += RecolourJob.launch(runner, request, newHost(), newSink()).id
    }

    // A preview belongs to the photo, palette and settings it was made for; after any change it is not shown.
    val shownPreview: PreviewState = run {
        val key = draft.palette?.let { p -> draft.recolourPick?.let { PreviewKey(it, p.argbs, draft.recolour) } }
        when (val s = preview) {
            is PreviewState.Ready -> if (s.key == key) s else PreviewState.Idle
            is PreviewState.Failed -> if (s.key == key) s else PreviewState.Idle
            else -> s
        }
    }

    Column(modifier.fillMaxSize()) {
        Header(onBack = ::back)
        JobStrip(
            copying = copying,
            result = result,
            leaveHeld = leaveHeld && copying != null,
            onCancel = { copying?.cancel?.invoke() },
            onOpen = { asset -> onOpenAsset(asset, assets) },
            assets = assets,
        )
        when (draft.page) {
            BuilderPage.START -> StartPage(
                draft = draft,
                saved = savedList,
                confirming = confirmDelete,
                unsavedHeld = leaveHeld && copying == null,
                onPhotos = { edit { it.onPage(BuilderPage.PHOTOS) } },
                onColour = { edit { it.onPage(BuilderPage.COLOUR) } },
                onKeep = { edit { it.onPage(BuilderPage.EDIT) } },
                onOpenSaved = { palette -> edit { it.opened(palette) } },
                onAskDelete = { confirmDelete = it },
                onDelete = ::deleteSaved,
                modifier = Modifier.weight(1f),
            )
            BuilderPage.PHOTOS -> PhotosPage(
                stills = stills,
                draft = draft,
                reading = readJob != null,
                progress = readProgress,
                error = readError,
                onToggle = { asset -> edit { it.togglePick(asset.id.value) } },
                onMake = ::startReading,
                onCancel = ::cancelReading,
                modifier = Modifier.weight(1f),
            )
            BuilderPage.COLOUR -> ColourPage(
                text = hex,
                onText = { hex = it.take(MAX_HEX_TEXT) },
                onStart = ::startFromColour,
                modifier = Modifier.weight(1f),
            )
            BuilderPage.EDIT -> EditPage(
                draft = draft,
                notice = notice,
                onPick = { index -> edit { it.selecting(index) } },
                onNudge = { channel, delta -> edit { it.nudged(channel, delta) } },
                actions = SwatchActions(
                    onLock = { edit { it.toggledLock() } },
                    onAdd = { edit { it.added() } },
                    onRemove = { edit { it.removed() } },
                    onLeft = { edit { it.movedBy(-1) } },
                    onRight = { edit { it.movedBy(+1) } },
                ),
                onHarmony = { kind -> edit { it.harmonised(kind) } },
                onUndo = { edit { it.undone() } },
                onRename = { name -> edit { it.renamed(name) } },
                onSave = ::savePalette,
                onShareText = ::shareText,
                onShareCard = ::shareCard,
                onRecolour = { edit { it.onPage(BuilderPage.RECOLOUR) } },
                modifier = Modifier.weight(1f),
            )
            BuilderPage.RECOLOUR -> RecolourPage(
                stills = stills,
                draft = draft,
                preview = shownPreview,
                copying = copying != null,
                canSave = runner != null,
                onPick = { asset -> cancelPreview(); preview = PreviewState.Idle; edit { it.pickRecolour(asset.id.value) } },
                onSettings = { change -> edit { it.withRecolour(change(it.recolour)) } },
                onPreview = ::startPreview,
                onCancelPreview = ::cancelPreview,
                onSave = ::startCopy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** More than a hex is never typed; the field is cut here so a paste cannot grow the saved state. */
private const val MAX_HEX_TEXT = 16

// ---- header and the copy's strip -----------------------------------------------------------------

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PbIconButton(Icons.AutoMirrored.Outlined.ArrowBack, PaletteBuilderCopy.BACK, onBack)
        Text(
            PaletteBuilderCopy.TITLE,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** A recolour's progress and Cancel while it runs, or how the last one ended. Shown on every page. */
@Composable
private fun JobStrip(
    copying: RunningJob?,
    result: CopyResult?,
    leaveHeld: Boolean,
    onCancel: () -> Unit,
    onOpen: (MediaAsset) -> Unit,
    assets: List<MediaAsset>,
) {
    if (copying == null && result == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (copying != null) {
            val progress = copying.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = RoomStyle.InkFaint,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            }
            Text(PaletteBuilderCopy.COPYING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            Text(
                PaletteBuilderCopy.LEAVING_CANCELS,
                modifier = if (leaveHeld) Modifier.polite() else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (leaveHeld) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            PbButton(PaletteBuilderCopy.CANCEL, onCancel)
        } else when (result) {
            is CopyResult.Saved -> {
                Text(result.done.message, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                val asset = PaletteDraft.savedAsset(assets, result.done.ref)
                if (asset != null) {
                    PbButton(PaletteBuilderCopy.COPY_OPEN, { onOpen(asset) })
                } else {
                    Text(PaletteBuilderCopy.COPY_NOT_LISTED, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                }
            }
            is CopyResult.Failed -> Text(result.message, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            CopyResult.Cancelled -> Text(PaletteBuilderCopy.CANCELLED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
    }
}
