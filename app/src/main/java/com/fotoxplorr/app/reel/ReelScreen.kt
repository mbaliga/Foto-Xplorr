package com.fotoxplorr.app.reel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.shell.AppStateViewModel
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Make a reel": join photos and clips into a short video and save it as a new file. The Experimental host
 * registers this; it is the one public entry. The screen over the pure model ([ReelProject], [ReelEditing],
 * [ReelDrafting]), the saved-reels library ([ReelProjectStore]), the preview ([ReelPreview]) and the export job ([ReelJob]).
 *
 * **Three pages.** The first lists the saved reels (Draft one for me, Start with nothing, open, delete). The draft
 * page sets a draft up: which months, how long, what shape; or choose photos by hand. Then the editor. Back
 * inside the editor closes the innermost step first (the picker, an open panel) and then goes back to the list;
 * Back on the list is the host's, which closes the experiment.
 *
 * **The draft** is [ReelDraftBuilder] on dates, favourites and the order of the photos alone: it does not look at a
 * picture, so it may keep a blurred shot or a near-twin. It is a first cut to change, not a judgement.
 *
 * **Photos.** Only `env.assets` is listed and previewed (already browsable-filtered, TRAPS #33), and the picker
 * offers only those. The export reads every pixel through [ReelEnvHost], which asks the host again and refuses
 * protected items (see [ReelJob]); an item whose photo is not in the list now is marked and the Make button waits.
 *
 * **Sound.** One sound file, picked with the phone's own file picker ([PickSoundtrack]: no permission, the app
 * never lists the person's music) and laid under the whole video. Nothing is ever played in Fotoz: the preview
 * shows pictures only. A clip's own sound is off unless switched on.
 *
 * **Saving.** A reel saves itself about a second after the last change into its own file under
 * `filesDir/reels` ([ReelProjectStore]; atomic, one file per reel) and again when it is left, so there is no Save
 * button. A new reel with nothing in it is never written. Making the video is a job on the app's job list with
 * progress and Cancel; it makes a NEW video in Movies/Fotoz/Reels and never changes a photo. Leaving the screen
 * stops a video being made (the line saying so is on screen, and the first Back only reads it).
 *
 * **Process death.** `rememberSaveable` keeps the open reel as text (and its place in the library), the picked
 * item, the open tool, the size, plain cuts and clip-sound choices, and the draft page's choices. Not kept: the
 * preview's place, the picker's page, the saved-reels list, the sound file's name (asked again from its address),
 * and a video being made (a killed process ends it and leaves no file, see [ReelWriter]). A reel too long for the
 * saved-state bundle comes back from its autosaved file instead, from the list.
 *
 * Nothing here has run on a device: the preview's motion and push, the strip on a narrow phone and with a large
 * font, the picker for sound files, and the whole export (see [ReelJob]) are unchecked.
 */
// The export library marks its classes unstable; this one place uses them (the encoder), as the lint check asks.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ReelMaker(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val latestEnv by rememberUpdatedState(env)
    val context = env.context
    val library = remember(context) { ReelProjectStore(File(context.filesDir, ReelProjectStore.DIRECTORY_NAME)) }
    val sounds = remember(context) { AndroidSoundFiles(context) }
    ReelScreen(
        assets = env.assets,
        library = library,
        newHost = { ReelEnvHost(latestEnv) },
        newEncoder = { TransformerReelEncoder(latestEnv.context) },
        newSink = { ReelWriter(latestEnv.context) },
        sounds = sounds,
        runner = runner,
        onWatch = { uri -> watch(latestEnv.context, uri) },
        animatorScale = readAnimatorScale(context),
        onClose = onClose,
        modifier = modifier,
    )
}

/** Opens the finished video in whatever app plays one; false when there is none. The app plays nothing itself. */
internal fun watch(context: Context, uri: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/** The system's animator duration scale (Settings.Global), 1 when unset or unreadable. 0 means animations are off. */
internal fun readAnimatorScale(context: Context): Float = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (e: Exception) {
    1f
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/** The reel saves about this long after the last change. */
internal const val REEL_AUTOSAVE_MILLIS = 1_000L

/**
 * The screen over its seams: [library] is where reels are kept, [newHost], [newEncoder] and [newSink] make what a
 * video is made through, [sounds] knows the sound file, [runner] is the app's job list (null where there is none,
 * and Make is then not offered), [onWatch] opens a finished video in a player. [onState] is handed the screen's
 * state holder once, so a test can read the project as it is now.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ReelScreen(
    assets: List<MediaAsset>,
    library: ReelLibrary,
    newHost: () -> ReelHost,
    newEncoder: () -> ReelEncoder,
    newSink: () -> ReelSink,
    sounds: ReelSoundFiles,
    runner: JobRunner?,
    onWatch: (String) -> Boolean,
    animatorScale: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    autosaveMillis: Long = REEL_AUTOSAVE_MILLIS,
    zone: ZoneId = ZoneId.systemDefault(),
    onState: (ReelScreenState) -> Unit = {},
) {
    val openState = rememberSaveable(stateSaver = OpenReelSaver) { mutableStateOf<OpenReel?>(null) }
    val selectedState = rememberSaveable { mutableStateOf<String?>(null) }
    val panelState = rememberSaveable { mutableStateOf(ReelPanel.NONE) }
    val sizeState = rememberSaveable { mutableStateOf(ReelAttempts.DEFAULT_SHORT_SIDE) }
    val cutsState = rememberSaveable { mutableStateOf(false) }
    val clipSoundState = rememberSaveable { mutableStateOf(false) }
    val draftPageState = rememberSaveable { mutableStateOf(false) }
    val monthsState = rememberSaveable { mutableStateOf(listOf<String>()) }
    val lengthState = rememberSaveable { mutableStateOf(ReelDraftOptions.DEFAULT_TARGET_MS) }
    val draftAspectState = rememberSaveable { mutableStateOf(ReelAspect.WIDE.code) }
    val state = remember {
        ReelScreenState(openState, selectedState, panelState, sizeState, cutsState, clipSoundState, draftPageState, monthsState, lengthState).also(onState)
    }

    val scope = rememberCoroutineScope()
    val latestAssets by rememberUpdatedState(assets)
    val latestNewHost by rememberUpdatedState(newHost)
    val latestNewEncoder by rememberUpdatedState(newEncoder)
    val latestNewSink by rememberUpdatedState(newSink)
    val latestSounds by rememberUpdatedState(sounds)
    val latestWatch by rememberUpdatedState(onWatch)
    val usable = remember(assets) { assets.filter { !it.isTrashed } }
    val byId = remember(assets) { usable.associateBy { it.id.value } }
    val autosaver = remember(library) { ReelAutosaver(library) }
    // Work that must outlive the screen (the last save of a reel) runs here, not in the screen's own scope.
    val lastWords = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var listVersion by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    val reduceMotion = remember(animatorScale) { ReelPreviewMath.reduceMotion(animatorScale) }

    // ---- the saved-reels list ----
    LaunchedEffect(state.open == null, listVersion) {
        if (state.open == null) state.list = withContext(Dispatchers.IO) { library.list() }
    }

    // ---- the sound file's name, asked from its address so it is there after a restart too ----
    val soundUri = state.project?.soundtrack?.uri
    LaunchedEffect(soundUri) {
        state.soundName = soundUri?.let { uri -> withContext(Dispatchers.IO) { latestSounds.nameOf(uri) } }
    }

    // ---- saving the reel as the person works ----
    suspend fun persist(open: OpenReel) {
        when (val result = autosaver.save(open)) {
            is ReelSave.Saved -> {
                state.saveNote = null
                val now = state.open
                if (now != null && now.token == open.token && now.storeId == null) state.open = now.saved(result.id)
            }
            ReelSave.Full -> state.saveNote = ReelCopy.LIST_FULL
            ReelSave.Failed -> state.saveNote = ReelCopy.COULD_NOT_SAVE
            null -> Unit
        }
    }
    LaunchedEffect(autosaver, autosaveMillis) {
        snapshotFlow { state.open?.let { it.token to it.project } }
            .debounce(autosaveMillis)
            .collect { key -> if (key != null) state.open?.takeIf { it.token == key.first }?.let { persist(it) } }
    }
    val latestOpen by rememberUpdatedState(state.open)
    DisposableEffect(autosaver) {
        onDispose { latestOpen?.let { open -> lastWords.launch { autosaver.save(open) } } }
    }

    // ---- making the video ----
    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val making = jobs.firstOrNull { it.title == ReelCopy.JOB_TITLE }
    // The videos this screen started: only their outcomes are shown. A job that refuses at once can finish, and its
    // outcome be collected, while launch is still on the stack, before its id can be noted: such an outcome is
    // taken as ours (and noted in [early]) when it is the reel job's and a launch of ours is under way.
    val started = remember { mutableSetOf<String>() }
    val early = remember { mutableSetOf<String>() }
    val launching = remember { BooleanArray(1) }
    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            val ours = started.remove(outcome.id) || (launching[0] && outcome.title == ReelCopy.JOB_TITLE && early.add(outcome.id))
            if (!ours) return@collect
            state.result = outcome.result.fold(
                onSuccess = { value -> (value as? ReelOutcome.Done)?.let { ReelResult.Saved(it) } },
                onFailure = { error ->
                    when (error) {
                        is CancellationException -> ReelResult.Cancelled
                        is ReelNeedsAnswer -> ReelResult.AskSilent(error.message ?: ReelCopy.SOUND_UNREADABLE)
                        is ReelFailure -> ReelResult.Failed(error.message ?: ReelCopy.FAILED)
                        else -> ReelResult.Failed(ReelCopy.FAILED)
                    }
                },
            )
        }
    }
    DisposableEffect(runner) {
        onDispose { runner?.jobs?.value?.filter { it.title == ReelCopy.JOB_TITLE }?.forEach { it.cancel() } }
    }

    fun makingNow(): Boolean = runner?.jobs?.value?.any { it.title == ReelCopy.JOB_TITLE } == true

    // ---- what a killed export left in the cache: swept when the Make panel opens (the writer only sweeps when a save is made) ----
    val makePanelOpen = state.open != null && state.panel == ReelPanel.MAKE
    LaunchedEffect(makePanelOpen) {
        if (makePanelOpen && runner != null && !makingNow()) {
            withContext(Dispatchers.IO) { runCatching { latestNewSink().sweepStaging() } }
        }
    }

    fun startMaking(withoutSound: Boolean) {
        val project = state.project ?: return
        if (runner == null || makingNow()) return
        state.result = null
        state.watchNote = null
        val request = ReelRequest(
            project = project.copy(name = ReelEditing.cleanName(project.name)),
            keepClipSound = state.keepClipSound,
            startShortSide = state.shortSide,
            transitions = !state.plainCuts,
            withoutSound = withoutSound,
        )
        launching[0] = true
        val id = try {
            ReelJob.launch(runner, request, latestNewHost(), latestNewEncoder(), latestNewSink()).id
        } finally {
            launching[0] = false
        }
        if (!early.remove(id)) started += id
    }

    // ---- opening, closing, leaving ----
    fun resetEditor() {
        state.panel = ReelPanel.NONE
        state.selectedId = null
        state.picker = false
        state.result = null
        state.leaveHeld = false
        state.saveNote = null
        state.addNote = null
        state.watchNote = null
        state.soundMayStop = false
    }

    fun openNew(project: ReelProject, notice: String? = null) {
        resetEditor()
        state.openFailed = false
        state.draftPage = false
        state.open = OpenReel(nextReelToken(), null, project, notice)
        state.selectedId = project.items.firstOrNull()?.id
    }

    fun openSaved(saved: SavedReel) {
        if (opening) return
        opening = true
        state.openFailed = false
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { library.load(saved.id) as? ReelLoad.Loaded }
            opening = false
            if (loaded == null) {
                state.openFailed = true
            } else {
                resetEditor()
                val project = loaded.decoded.project
                state.open = OpenReel(nextReelToken(), loaded.id, project, noticeFor(loaded.decoded), loaded = project)
            }
        }
    }

    fun leave() {
        val open = state.open ?: return
        // A video being made is stopped with the screen it belongs to; its result would be shown on the next reel.
        runner?.jobs?.value?.filter { it.title == ReelCopy.JOB_TITLE }?.forEach { it.cancel() }
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
        if (state.open != null) {
            if (makingNow() && !state.leaveHeld) {
                // Leaving stops the video and the line saying so is on screen: the next Back is the person's answer.
                state.leaveHeld = true
                return
            }
            leave()
        } else if (state.draftPage) {
            state.draftPage = false
            state.draftNote = null
        }
    }

    // Back inside the screen first; on the list this is off and the host closes the experiment.
    BackHandler(enabled = state.open != null || state.draftPage || state.picker) { back() }

    fun makeDraft() {
        if (state.drafting) return
        state.drafting = true
        state.draftNote = null
        val codes = state.months.toSet()
        val target = state.targetMs
        val aspect = ReelAspect.fromCode(draftAspectState.value)
        scope.launch {
            val draft = try {
                withContext(Dispatchers.Default) { ReelDrafting.draft(latestAssets.filter { !it.isTrashed }, codes, target, aspect, zone) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            state.drafting = false
            when {
                draft == null -> state.draftNote = ReelCopy.DRAFT_FAILED
                draft.project.items.isEmpty() -> state.draftNote = ReelCopy.NOTHING_TO_USE
                else -> openNew(draft.project, ReelCopy.picked(draft.report.picked, draft.report.considered))
            }
        }
    }

    fun addPicked(picked: List<MediaAsset>) {
        state.picker = false
        val current = state.open
        if (current == null) {
            // From the draft page: a new reel made of what was chosen, in the shape chosen there.
            val shaped = ReelProject(aspect = ReelAspect.fromCode(draftAspectState.value))
            val added = ReelEditing.add(shaped, picked)
            openNew(added.project, if (added.skipped > 0) ReelCopy.itemsAdded(added.added, added.skipped) else null)
        } else {
            val after = state.selectedIndex.takeIf { it >= 0 }
            val added = ReelEditing.add(current.project, picked, after)
            state.edit { added.project }
            state.addNote = ReelCopy.itemsAdded(added.added, added.skipped)
            // The first of the new items is picked, so it can be seen at once.
            val first = (after ?: current.project.items.lastIndex) + 1
            added.project.items.getOrNull(first)?.takeIf { added.added > 0 }?.let { state.selectedId = it.id }
        }
    }

    // ---- the sound file ----
    val soundLauncher = rememberLauncherForActivityResult(PickSoundtrack()) { uri ->
        if (uri != null) {
            val address = uri.toString()
            val kept = latestSounds.keep(address)
            state.edit { ReelEditing.setSoundtrack(it, address) }
            state.soundMayStop = !kept
        }
    }

    // ---- the page ----
    val open = state.open
    Box(modifier.fillMaxSize()) {
        when {
            state.picker -> ReelItemPicker(
                candidates = usable,
                room = if (open == null) ReelLimits.MAX_ITEMS else ReelEditing.room(open.project),
                onAdd = ::addPicked,
                onBack = { state.picker = false },
            )
            open != null -> ReelEditorScreen(
                state = state,
                open = open,
                byId = byId,
                making = making,
                makerAvailable = runner != null,
                reduceMotion = reduceMotion,
                actions = ReelEditorActions(
                    onBack = ::back,
                    onMake = { startMaking(withoutSound = false) },
                    onCancelMake = { making?.cancel?.invoke() },
                    onMakeSilent = { startMaking(withoutSound = true) },
                    onWatch = { uri -> state.watchNote = if (latestWatch(uri)) null else ReelCopy.NO_PLAYER },
                    onPickSound = { soundLauncher.launch(Unit) },
                    onPickItems = { state.picker = true },
                ),
            )
            state.draftPage -> ReelDraftPage(
                months = remember(usable) { ReelDrafting.months(usable, zone) },
                chosen = state.months.toSet(),
                onToggleMonth = { code -> state.months = if (code in state.months) state.months - code else state.months + code },
                onAllDates = { state.months = emptyList() },
                target = state.targetMs,
                onTarget = { state.targetMs = it },
                aspect = ReelAspect.fromCode(draftAspectState.value),
                onAspect = { draftAspectState.value = it.code },
                busy = state.drafting,
                note = state.draftNote,
                onMake = ::makeDraft,
                onByHand = { state.picker = true },
                onBack = ::back,
            )
            else -> ReelHomePage(
                list = state.list,
                opening = opening,
                openFailed = state.openFailed,
                onDraft = { state.draftNote = null; state.draftPage = true },
                onBlank = { openNew(ReelProject()) },
                onOpen = ::openSaved,
                onDelete = { state.deleting = it },
                onBack = onClose,
            )
        }
        state.deleting?.let { doomed ->
            DeleteReelDialog(
                reel = doomed,
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

/** A line about how a reel was opened, when something could not be read, or null when all of it was. */
internal fun noticeFor(decoded: ReelDecoded): String? = when {
    decoded.fromNewerVersion -> ReelCopy.NEWER_VERSION
    decoded.skippedItems > 0 -> ReelCopy.partsLost(decoded.skippedItems)
    else -> null
}
