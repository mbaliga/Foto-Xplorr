package com.fotoxplorr.app.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.shell.AppStateViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Mood board": lay photos, colour blocks, words and shapes out on a canvas and save the result as a new
 * picture. The Experimental host registers this; it is the one public entry. The screen over the pure
 * model ([Board], [BoardEditing]), the renderer ([BoardRenderer]) and the export job ([BoardExportJob]).
 *
 * **Two pages.** The list of saved boards (New board, open, delete) and the editor. Back inside the editor
 * closes the innermost step first (the photo picker, a tap-the-points or mark-the-subject step, an open
 * panel) and then goes back to the list; Back on the list is the host's, which closes the experiment.
 *
 * **Photos.** Only `env.assets` is listed (already browsable-filtered, TRAPS #33). Every pixel, for the
 * picture on screen and for the saved file, comes through `env.decode`, which asks the live browsable set
 * again; the export asks `env.mayExport` once more right before it makes the file. A photo that was
 * moved or restored is found again by name, size and date taken when a board is opened. The picture on
 * screen is drawn by [BoardRenderer] on a background thread and shown a moment behind the finger; it
 * keeps decoded photos in memory only while the editor is open ([PreviewPhotoCache]).
 *
 * **Saving.** A board saves itself about a second after the last change into its own file under
 * `filesDir/boards` ([BoardStore]; atomic, one file per board) and again when it is left, so there is no
 * Save button. A new board with nothing on it is never written. Exporting is a job on the app's job
 * list with progress and Cancel; it makes a NEW picture in Pictures/Fotoz/Boards and never changes a
 * photo, and no location is written. Leaving the screen stops a save in progress.
 *
 * **Process death.** One `rememberSaveable` keeps the open board as text (and which layer was picked,
 * its name and its place in the store), the open panel, snapping, the colour for new things, the text being
 * typed and the export choices. Not kept: the undo history, the picture on screen, the palette strip, a
 * tap-the-points step, the saved-boards list and a save in progress (a killed process ends it and leaves
 * no file, see [BoardWriter]). A board too long for the saved-state bundle comes back from its autosaved
 * file instead ([OpenBoardSaver]).
 *
 * Nothing here has run on a device: the feel of the drag, pinch and twist, the board's inset on a narrow
 * phone, and the layout with a large font are unchecked.
 */
@Composable
fun MoodBoard(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val latestEnv by rememberUpdatedState(env)
    val store = remember(env.context) { BoardStore(File(env.context.filesDir, BoardStore.DIRECTORY_NAME)) }
    MoodBoardScreen(
        assets = env.assets,
        newSource = { board -> EnvPhotoSource.of(latestEnv, board) },
        relink = { board ->
            // An empty list is a library still loading, not "everything is gone": nothing is relinked then.
            if (latestEnv.assets.isEmpty()) board else PhotoResolver.relink(board, EnvPhotoSource.of(latestEnv, board).resolutions)
        },
        library = store,
        newSink = { BoardWriter(latestEnv.context) },
        runner = runner,
        onOpenAsset = { asset, list -> latestEnv.onOpenAsset(asset, list) },
        onClose = onClose,
        modifier = modifier,
    )
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/** The board saves about this long after the last change. */
internal const val AUTOSAVE_MILLIS = 1_000L

/**
 * The screen over its seams: [newSource] makes the photo source for a board (the host's), [relink] points
 * photos that moved at their new ids, [library] is where boards are kept, [newSink] makes what an export
 * writes through, [runner] is the app's job list (null where there is none, and Save is then not offered).
 * [onState] is handed the screen's state holder once, so a test can read the board as it is now.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun MoodBoardScreen(
    assets: List<MediaAsset>,
    newSource: (Board) -> BoardPhotoSource,
    relink: (Board) -> Board,
    library: BoardLibrary,
    newSink: () -> BoardSink,
    runner: JobRunner?,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autosaveMillis: Long = AUTOSAVE_MILLIS,
    onState: (MoodBoardState) -> Unit = {},
) {
    val openState = rememberSaveable(stateSaver = OpenBoardSaver) { mutableStateOf<OpenBoard?>(null) }
    val panelState = rememberSaveable { mutableStateOf(Panel.NONE) }
    val snapState = rememberSaveable { mutableStateOf(true) }
    val inkState = rememberSaveable { mutableStateOf(BoardColours.DEFAULT_INK) }
    val textDraftState = rememberSaveable { mutableStateOf("") }
    val formatState = rememberSaveable { mutableStateOf(OutputFormat.JPEG) }
    val edgeState = rememberSaveable { mutableStateOf(MaxEdgePreset.ORIGINAL) }
    val state = remember { MoodBoardState(openState, panelState, snapState, inkState, textDraftState, formatState, edgeState).also(onState) }

    val scope = rememberCoroutineScope()
    val latestAssets by rememberUpdatedState(assets)
    val latestNewSource by rememberUpdatedState(newSource)
    val latestRelink by rememberUpdatedState(relink)
    val latestNewSink by rememberUpdatedState(newSink)
    val stills = remember(assets) { assets.filter { !it.isVideo && !it.isTrashed } }
    val autosaver = remember(library) { BoardAutosaver(library) }
    // Work that must outlive the screen (the last save of a board) runs here, not in the screen's own scope.
    val lastWords = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var listVersion by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }

    // ---- where photos come from ----
    val board = state.open?.board
    val photoIds = board?.photoRefs?.map { it.mediaId }
    val live = remember { SwitchingPhotoSource(newSource(Board())) }
    val source = remember(assets, photoIds) { newSource(board ?: Board()).also { live.current = it } }
    val previewSource = remember { PreviewPhotoCache(live) }
    DisposableEffect(previewSource) { onDispose { previewSource.clear() } }
    val wantsMissing = state.panel == Panel.LAYERS || state.panel == Panel.EXPORT
    val missing = remember(board, source, wantsMissing) {
        if (board == null || !wantsMissing) emptyList() else BoardRenderer(source).missingNow(board)
    }

    // ---- the saved-boards list ----
    LaunchedEffect(state.open == null, listVersion) {
        if (state.open == null) state.list = withContext(Dispatchers.IO) { library.list() }
    }

    // ---- the picture of the board, drawn behind the finger ----
    LaunchedEffect(previewSource) {
        snapshotFlow { state.open?.board to state.previewEdge }
            .conflate()
            .collect { (current, edge) ->
                if (current == null || edge <= 0) return@collect
                val drawn = try {
                    withContext(Dispatchers.Default) { BoardRenderer(previewSource).preview(current, edge) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // A picture that could not be drawn (memory, a photo that failed) leaves the last one on screen.
                if (drawn is BoardRender.Done) state.preview = drawn.bitmap.asImageBitmap()
            }
    }

    // ---- saving the board as the person works ----
    suspend fun persist(open: OpenBoard) {
        when (val result = autosaver.save(open)) {
            is BoardSave.Saved -> {
                state.saveNote = null
                val now = state.open
                if (now != null && now.token == open.token && now.storeId == null) state.open = now.saved(result.id)
            }
            BoardSave.Full -> state.saveNote = BoardCopy.LIST_FULL
            BoardSave.Failed -> state.saveNote = BoardCopy.COULD_NOT_SAVE
            null -> Unit
        }
    }
    LaunchedEffect(autosaver, autosaveMillis) {
        snapshotFlow { state.open?.let { Triple(it.token, it.name, it.board) } }
            .debounce(autosaveMillis)
            .collect { key -> if (key != null) state.open?.takeIf { it.token == key.first }?.let { persist(it) } }
    }
    val latestOpen by rememberUpdatedState(state.open)
    DisposableEffect(autosaver) {
        onDispose { latestOpen?.let { open -> lastWords.launch { autosaver.save(open) } } }
    }

    // ---- exporting ----
    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val saving = jobs.firstOrNull { it.title == BoardCopy.JOB_TITLE }
    // The saves this screen started: only their outcomes are shown.
    val started = remember { mutableSetOf<String>() }
    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            if (!started.remove(outcome.id)) return@collect
            state.exportResult = outcome.result.fold(
                onSuccess = { value -> (value as? BoardExportOutcome.Done)?.let { ExportResult.Saved(it) } },
                onFailure = { error ->
                    when (error) {
                        is CancellationException -> ExportResult.Cancelled
                        is BoardExportFailure -> ExportResult.Failed(error.problem, error.layerIds)
                        else -> ExportResult.Failed(BoardExportProblem.SAVE_FAILED, emptyList())
                    }
                },
            )
        }
    }
    DisposableEffect(runner) {
        onDispose { runner?.jobs?.value?.filter { it.title == BoardCopy.JOB_TITLE }?.forEach { it.cancel() } }
    }

    fun exportRunning(): Boolean = runner?.jobs?.value?.any { it.title == BoardCopy.JOB_TITLE } == true

    fun startSave() {
        val open = state.open ?: return
        if (runner == null || exportRunning()) return
        state.exportResult = null
        val request = BoardExportRequest(open.board, open.name, state.format, state.maxEdge)
        started += BoardExportJob.launch(runner, request, latestNewSource(open.board), latestNewSink()).id
    }

    // ---- opening, closing, leaving ----
    fun resetEditor() {
        state.panel = Panel.NONE
        state.mode = CanvasMode.Edit
        state.picker = false
        state.preview = null
        state.palette = emptyList()
        state.paletteChosen = null
        state.paletteBusy = false
        state.exportResult = null
        state.leaveHeld = false
        state.saveNote = null
    }

    fun newBoard() {
        resetEditor()
        state.openFailed = false
        state.open = OpenBoard(nextToken(), null, BoardCopy.DEFAULT_NAME, BoardEditor.of(Board()))
    }

    fun openSaved(saved: SavedBoard) {
        if (opening) return
        opening = true
        state.openFailed = false
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                when (val l = library.load(saved.id)) {
                    is BoardLoad.Loaded -> l to latestRelink(l.decoded.board)
                    else -> null
                }
            }
            opening = false
            if (loaded == null) {
                state.openFailed = true
            } else {
                val (l, board) = loaded
                resetEditor()
                state.open = OpenBoard(nextToken(), l.id, l.name, BoardEditor.of(board), noticeFor(l.decoded))
            }
        }
    }

    fun leave() {
        val open = state.open ?: return
        // A save in progress is stopped with the screen it belongs to; its result would be shown on the next board.
        runner?.jobs?.value?.filter { it.title == BoardCopy.JOB_TITLE }?.forEach { it.cancel() }
        started.clear()
        state.open = null
        resetEditor()
        lastWords.launch {
            autosaver.save(open)
            listVersion++
        }
    }

    fun back() {
        if (state.backOneStep()) return
        if (exportRunning() && !state.leaveHeld) {
            // Leaving stops the save, and the line saying so is on screen: the next Back is the person's answer.
            state.leaveHeld = true
            return
        }
        leave()
    }

    // Back inside the editor first; on the list this is off and the host closes the experiment.
    BackHandler(enabled = state.open != null) { back() }

    fun findColours() {
        val current = state.open?.board ?: return
        if (state.paletteBusy) return
        state.paletteBusy = true
        state.paletteChosen = null
        scope.launch {
            val found = try {
                withContext(Dispatchers.Default) { BoardPalette.of(live, current.photoRefs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            state.palette = found
            state.paletteBusy = false
        }
    }

    // ---- the page ----
    val open = state.open
    Box(modifier.fillMaxSize()) {
        when {
            open == null -> BoardListScreen(
                list = state.list,
                opening = opening,
                openFailed = state.openFailed,
                onNew = ::newBoard,
                onOpen = ::openSaved,
                onDelete = { state.deleting = it },
                onBack = onClose,
            )
            state.picker -> BoardPhotoPicker(
                stills = stills,
                room = BoardLimits.MAX_LAYERS - open.board.layers.size,
                onAdd = { chosen ->
                    val refs = chosen.map(PhotoRefs::of)
                    state.edit { BoardEditing.addPhotos(it, refs).editor }
                    state.picker = false
                },
                onBack = { state.picker = false },
            )
            else -> BoardEditorScreen(
                state = state,
                open = open,
                assets = assets,
                missing = missing,
                saving = saving,
                canExport = runner != null,
                onBack = ::back,
                onFindColours = ::findColours,
                onSave = ::startSave,
                onCancelSave = { saving?.cancel?.invoke() },
                onOpenAsset = { asset -> onOpenAsset(asset, latestAssets) },
            )
        }
        state.deleting?.let { doomed ->
            DeleteBoardDialog(
                board = doomed,
                onDelete = {
                    state.deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { library.delete(doomed.id) }
                        listVersion++
                    }
                },
                onKeep = { state.deleting = null },
            )
        }
    }
}

/** A line about how a board was opened, when something could not be read, or null when all of it was. */
internal fun noticeFor(decoded: BoardJson.Decoded): String? = when {
    decoded.fromNewerVersion -> BoardCopy.NEWER_VERSION
    decoded.skippedLayers > 0 || decoded.droppedMasks > 0 -> BoardCopy.partsLost(decoded.skippedLayers, decoded.droppedMasks)
    else -> null
}
