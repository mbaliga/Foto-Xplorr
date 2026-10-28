package com.fotoxplorr.app.chroma

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.palettebuilder.PaletteLibrary
import com.fotoxplorr.app.palettebuilder.PaletteStore
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The Chroma ID experiment: the one entry the Experimental host registers (with `restorable = false`,
 * because it uses the camera and asks a permission). It reads nothing from [env]: the pictures are the
 * camera's own and no library photo is involved, so `mayExport` and `decode` do not apply.
 *
 * ## The flow
 * Three steps: 1 an optional white card beside the surface, 2 hold the phone near it and keep still (with
 * live words on distance, steadiness and a washed-out view), 3 the result: a swatch, the colour in words,
 * hex, RGB, Lab and LCh, the three nearest names with their distance, and one line on how far to trust it.
 * From the result: copy the hex, compare it with a past reading (two swatches and a verdict), save it to a
 * palette, and measure again. A list of past readings is one button away. No camera picture is shown after
 * a measurement: the view is covered by the sampled colour, and the camera's own frames are gone.
 *
 * ## The permission
 * Asked here, never at app start, and only after the person has read why: the screen opens with
 * [ChromaCopy.PRIVACY] and an Allow button, and the system dialog comes from that button. A refusal shows
 * [ChromaCopy.CAMERA_DENIED] with Settings, and with Ask again only while the system would still show its
 * dialog (the rationale flag), because after "don't ask again" the request is answered at once and a button
 * that does nothing is worse than none.
 *
 * ## What is released, and when
 * Pause (and leaving, and opening the history, compare or palette view, and a turn of the phone) stops the
 * job: the camera, session, image reader and thread are released and the gyroscope is unregistered. Coming
 * back opens them again. The activity handles a rotation itself and is not recreated, so the camera body is
 * keyed on the display rotation: a turn is a stop and a fresh start, with a surface and a session made for the
 * new orientation (and the card measurement dropped).
 *
 * ## What is saved
 * The camera pictures: nothing; they live in memory for the few seconds a measurement takes and are
 * overwritten once the answer is worked out. Every finished colour is added to the history
 * ([ChromaHistoryStore], `filesDir/chroma/history.txt`, plain text, the last 50, a colour a kind and a time
 * each; delete-all is on the history view). A colour goes to a palette only when asked, through the palette
 * builder's own store ([ChromaPalettes]). A card measurement is kept as numbers only while the light stays
 * held, and is dropped by leaving, pausing, turning the phone or measuring the card again. Delete all
 * readings also clears the colour on screen and the ones picked for a comparison or a palette.
 *
 * ## Recreation and process death
 * If the screen is recreated while the process lives, the view, the colour on screen, the colours picked
 * for a comparison or a palette, the card switch and whether the permission was asked come back. Not kept:
 * the camera (it reopens), a card measurement and the live words. After a process kill nothing comes back:
 * the entry is not restorable (it opens the camera and asks a permission), so the tool reopens as the hub;
 * the colours are still in the history.
 *
 * Nothing here has run on a device. There is no drag, so the shell's edge zones (TRAPS #72) do not matter.
 * The inner views register a Back handler that returns to measuring (TRAPS #78); Back from measuring closes.
 */
@Composable
fun ChromaId(@Suppress("UNUSED_PARAMETER") env: ExperimentEnv, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val choice = remember { CameraProbe.choose(app) }
    val history = remember { ChromaHistoryStore(ChromaHistoryStore.directoryIn(app.filesDir)) }
    val palettes = remember { PaletteStore(PaletteStore.directoryIn(app.filesDir)) }
    ChromaIdScreen(choice, history, palettes, onClose)
}

/** The four views of the tool; the camera is open only in [MEASURE]. */
internal enum class ChromaView { MEASURE, HISTORY, COMPARE, SAVE }

/** A reading in saved state: its history line, or the empty string for none. */
private val ReadingSaver = Saver<SavedReading?, String>(
    save = { it?.toLine().orEmpty() },
    restore = { SavedReading.fromLine(it) },
)

/**
 * The tool with its stores handed in, so a test can give it in-memory ones and no camera ([choice] null
 * shows the no-camera message, and the history, compare and palette views still work).
 */
@Composable
internal fun ChromaIdScreen(choice: CameraChoice?, history: ChromaHistory, palettes: PaletteLibrary, onClose: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(ChromaView.MEASURE) }
    var shown by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf<SavedReading?>(null) }
    var subject by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf<SavedReading?>(null) }
    var other by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf<SavedReading?>(null) }
    var useCard by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }

    var loaded by remember { mutableStateOf<HistoryLoad?>(null) }
    var targets by remember { mutableStateOf<List<PaletteTarget>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(history) { loaded = history.load() }

    fun go(next: ChromaView) {
        notice = null
        view = next
    }

    fun copy(hex: String) {
        clipboard.setText(AnnotatedString(hex))
        notice = ChromaCopy.copied(hex)
    }

    fun reading(r: SavedReading) {
        shown = r
        notice = null
        scope.launch {
            val ok = history.add(r)
            loaded = history.load()
            if (!ok) notice = ChromaCopy.HISTORY_NOT_ADDED
        }
    }

    fun openCompare(r: SavedReading) {
        subject = r
        other = null
        go(ChromaView.COMPARE)
    }

    fun openSave(r: SavedReading) {
        subject = r
        targets = null
        go(ChromaView.SAVE)
        scope.launch {
            val saved = palettes.load()
            targets = ChromaPalettes.targets(r.argb, saved.palettes, ChromaPalettes.partnerFor(r, loaded?.readings.orEmpty()))
            if (saved.unreadable) notice = ChromaCopy.PALETTE_FAILED
        }
    }

    fun pickTarget(r: SavedReading, target: PaletteTarget) {
        scope.launch {
            val result = ChromaPalettes.save(palettes, r.argb, target)
            notice = when (result) {
                is HandoffResult.Added -> ChromaCopy.addedTo(result.name)
                is HandoffResult.Created -> ChromaCopy.createdPalette(result.name)
                is HandoffResult.Full -> ChromaCopy.paletteFull(result.name)
                is HandoffResult.AlreadyThere -> ChromaCopy.paletteHasIt(result.name)
                HandoffResult.ListFull -> ChromaCopy.PALETTE_LIST_FULL
                HandoffResult.Failed -> ChromaCopy.PALETTE_FAILED
            }
            val saved = palettes.load()
            targets = ChromaPalettes.targets(r.argb, saved.palettes, ChromaPalettes.partnerFor(r, loaded?.readings.orEmpty()))
        }
    }

    fun deleteAll() {
        scope.launch {
            val ok = history.clear()
            loaded = history.load()
            if (ok) {
                // Deleted means the colours too: the one on screen and the ones picked for a comparison or a palette.
                shown = null
                subject = null
                other = null
                targets = null
            }
            notice = if (ok) ChromaCopy.DELETED else ChromaCopy.DELETE_FAILED
        }
    }

    PaneScaffold(title = ChromaCopy.TITLE, onBack = { if (view != ChromaView.MEASURE) go(ChromaView.MEASURE) else onClose() }) {
        BackHandler(enabled = view != ChromaView.MEASURE) { go(ChromaView.MEASURE) }
        when (view) {
            ChromaView.MEASURE -> {
                val actions = remember {
                    ResultActions(
                        onCopy = { shown?.let { copy(it.hex) } },
                        onCompare = { shown?.let(::openCompare) },
                        onSave = { shown?.let(::openSave) },
                    )
                }
                if (choice == null) {
                    Column(
                        Modifier.padding(horizontal = RoomStyle.GutterStart, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(ChromaCopy.NO_CAMERA, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text(ChromaCopy.PRIVACY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                        ChromaButton(ChromaCopy.HISTORY, { go(ChromaView.HISTORY) })
                    }
                } else {
                    // A new body for each display rotation: the activity is not recreated by a turn, and a camera
                    // session and its surface made for one orientation are not right for the other.
                    key(rememberDisplayRotation()) {
                        ChromaCameraBody(
                            choice = choice,
                            shown = shown,
                            notice = notice,
                            actions = actions,
                            useCard = useCard,
                            onUseCard = { useCard = it },
                            asked = asked,
                            onAsked = { asked = true },
                            onReading = ::reading,
                            onClear = {
                                shown = null
                                notice = null
                            },
                            onHistory = { go(ChromaView.HISTORY) },
                        )
                    }
                }
            }
            ChromaView.HISTORY -> HistoryPanel(
                load = loaded,
                notice = notice,
                onCopy = { copy(it.hex) },
                onCompare = ::openCompare,
                onSave = ::openSave,
                onDeleteAll = ::deleteAll,
                onBack = { go(ChromaView.MEASURE) },
                modifier = Modifier.fillMaxSize(),
            )
            ChromaView.COMPARE -> {
                val first = subject
                if (first == null) {
                    LaunchedEffect(Unit) { go(ChromaView.MEASURE) }
                } else {
                    ComparePanel(
                        subject = first,
                        other = other,
                        candidates = CompareView.candidates(first, loaded?.readings.orEmpty()),
                        onPick = { other = it },
                        onPickAgain = { other = null },
                        onBack = { go(ChromaView.MEASURE) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            ChromaView.SAVE -> {
                val colour = subject
                if (colour == null) {
                    LaunchedEffect(Unit) { go(ChromaView.MEASURE) }
                } else {
                    SavePanel(
                        colour = colour,
                        targets = targets,
                        notice = notice,
                        onPick = { pickTarget(colour, it) },
                        onBack = { go(ChromaView.MEASURE) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * The rotation of the display the tool is on (a `Surface.ROTATION_*`), kept current by a display listener. A
 * rotation does not recreate the activity, so nothing else tells the camera body to start afresh.
 */
@Composable
internal fun rememberDisplayRotation(): Int {
    val context = LocalContext.current
    val view = LocalView.current
    var rotation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(context, view) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                val now = view.display?.rotation ?: return
                if (now != rotation) rotation = now
            }
        }
        manager?.registerDisplayListener(listener, null)
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return rotation
}

@Composable
private fun ChromaCameraBody(
    choice: CameraChoice,
    shown: SavedReading?,
    notice: String?,
    actions: ResultActions,
    useCard: Boolean,
    onUseCard: (Boolean) -> Unit,
    asked: Boolean,
    onAsked: () -> Unit,
    onReading: (SavedReading) -> Unit,
    onClear: () -> Unit,
    onHistory: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val surfaceFlow = remember { MutableStateFlow<Surface?>(null) }
    val motion = remember { MotionWindow() }
    val source = remember(choice) { Camera2Source(context.applicationContext, choice, surfaceFlow) }
    val job = remember(source) { ChromaJob(source, motion, scope) }
    val gyro = remember { GyroSensor(context.applicationContext, motion) }
    val latestAsked by rememberUpdatedState(asked)
    val latestOnAsked by rememberUpdatedState(onAsked)
    val latestOnReading by rememberUpdatedState(onReading)

    var permitted by remember { mutableStateOf(hasCameraPermission(context)) }

    // Whether the system would still show its dialog: true after one refusal, false after "don't ask again"
    // (and before any request, which is not a denial and is handled by the Allow button).
    var canAskAgain by remember { mutableStateOf(true) }

    fun refreshCanAskAgain() {
        val host = context.hostActivity()
        canAskAgain = host != null && ActivityCompat.shouldShowRequestPermissionRationale(host, Manifest.permission.CAMERA)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (!granted) refreshCanAskAgain()
        job.permissionResult(granted)
    }

    fun ask() {
        latestOnAsked()
        job.needPermission()
        launcher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner, job, gyro) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    gyro.start()
                    permitted = hasCameraPermission(context)
                    when {
                        permitted -> job.open()
                        // Not asked yet: the reason and the Allow button come first; the dialog is that button's.
                        !latestAsked -> job.needPermission()
                        else -> {
                            refreshCanAskAgain()
                            job.permissionResult(false)
                        }
                    }
                }
                // Pause comes before stop, and is when the camera is taken away from a window that loses focus.
                Lifecycle.Event.ON_PAUSE -> {
                    gyro.stop()
                    job.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gyro.stop()
            job.stop()
        }
    }

    val state by job.state.collectAsState()
    val outcome by job.outcome.collectAsState()
    val cardKept by job.cardKept.collectAsState()
    val live by source.live.collectAsState()
    val steady by gyro.steady.collectAsState()

    // A finished colour goes up to the screen, which shows it and adds it to the history; the job's own
    // outcome is then only needed for the cautions that belong to this measurement.
    LaunchedEffect(outcome) {
        ReadingView.savedFrom(outcome, System.currentTimeMillis())?.let { latestOnReading(it) }
    }

    val rotationDegrees = (LocalView.current.display?.rotation ?: Surface.ROTATION_0) * 90
    val aspect = PreviewShape.displayAspect(
        choice.facts.previewWidth,
        choice.facts.previewHeight,
        choice.sensorOrientation,
        rotationDegrees,
    )
    val resultOn = shown != null
    val measuring = state is CaptureState.Locking || state is CaptureState.Ambient || state is CaptureState.Flash

    Column(Modifier.fillMaxSize()) {
        if (permitted) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(if (resultOn) 0.3f else 1f).padding(horizontal = RoomStyle.GutterStart)) {
                val h = min(maxHeight.value, maxWidth.value / aspect.toFloat()).dp
                val w = (h.value * aspect.toFloat()).dp
                PreviewBox(
                    width = w,
                    height = h,
                    bufferWidth = choice.facts.previewWidth,
                    bufferHeight = choice.facts.previewHeight,
                    surfaceFlow = surfaceFlow,
                    onSurface = { if (permitted) job.open() },
                    onGone = { job.stop() },
                    modifier = Modifier.align(Alignment.Center),
                )
                // Once there is a colour the live view is covered by it, and nothing of the picture is seen.
                // The view stays in the tree so the camera keeps running and "Measure again" is instant.
                if (shown != null) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(w, h)
                            .background(Color(shown.argb))
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (resultOn) Modifier.weight(0.7f) else Modifier.heightIn(max = 320.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RoomStyle.GutterStart, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChromaControls(
                state = state,
                outcome = outcome,
                cardKept = cardKept,
                useCard = useCard,
                onUseCard = { want ->
                    onUseCard(want)
                    if (!want) job.forgetCard()
                },
                live = live,
                steady = steady,
                canAskAgain = canAskAgain,
                onAsk = ::ask,
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onMeasure = { role, ambientOnly, relock ->
                    onClear()
                    job.measure(role, ambientOnly, relock)
                },
                onCancel = job::cancel,
                onAgain = {
                    onClear()
                    job.again()
                    if (job.state.value == CaptureState.Idle && permitted) job.open()
                },
                shown = shown?.let { ReadingView.of(it, outcome) },
                actions = actions,
                notice = notice,
            )
            if (!measuring) ChromaButton(ChromaCopy.HISTORY, onHistory)
            Text(ChromaCopy.PRIVACY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        }
    }
}

/** The live view, with the box that marks the part of it that is measured. */
@Composable
private fun PreviewBox(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    bufferWidth: Int,
    bufferHeight: Int,
    surfaceFlow: MutableStateFlow<Surface?>,
    onSurface: () -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestSurface by androidx.compose.runtime.rememberUpdatedState(onSurface)
    val latestGone by androidx.compose.runtime.rememberUpdatedState(onGone)
    Box(modifier.size(width, height).semantics { contentDescription = ChromaCopy.PREVIEW_DESCRIPTION }) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFixedSize(bufferWidth, bufferHeight)
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                surfaceFlow.value = h.surface
                                latestSurface()
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = Unit

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                surfaceFlow.value = null
                                latestGone()
                            }
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxSize().testTag("chroma-preview"),
        )
        // The measured part (the middle 40% by 40%, PatchRegion.CENTRE): a dark line inside a light one, so it shows on any picture.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.4f)
                .fillMaxHeight(0.4f)
                .border(4.dp, Color.Black.copy(alpha = 0.6f))
                .border(2.dp, Color.White)
                .clearAndSetSemantics { },
        )
    }
}

/** What the person sees and presses for each [CaptureState]; kept apart from the camera so a test can drive every state. */
@Composable
internal fun ChromaControls(
    state: CaptureState,
    outcome: Outcome?,
    cardKept: Boolean,
    useCard: Boolean,
    onUseCard: (Boolean) -> Unit,
    live: LiveView,
    steady: Boolean?,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    onMeasure: (ShotRole, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
    onAgain: () -> Unit,
    /** The colour on screen, or null; it is step 3 and replaces the measuring controls. */
    shown: ReadingView? = null,
    actions: ResultActions? = null,
    notice: String? = null,
    /** False once the system would answer a permission request without a dialog: only the Settings are then offered. */
    canAskAgain: Boolean = true,
) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (shown != null && (state is CaptureState.Ready || state is CaptureState.Locked || state is CaptureState.Done)) {
        StepHeader(ChromaStep.RESULT)
        Text(ChromaCopy.STEP_RESULT, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        ReadingCard(shown, actions, notice)
        ChromaButton(ChromaCopy.AGAIN, onAgain, primary = true)
        return
    }
    when (state) {
        CaptureState.Idle, CaptureState.NeedsPermission -> {
            Text(ChromaCopy.PRIVACY, modifier = polite)
            ChromaButton(ChromaCopy.ALLOW_CAMERA, onAsk, primary = true)
        }
        CaptureState.PermissionDenied -> {
            Text(ChromaCopy.CAMERA_DENIED, modifier = polite)
            if (canAskAgain) ChromaButton(ChromaCopy.ASK_AGAIN, onAsk, primary = true)
            ChromaButton(ChromaCopy.OPEN_SETTINGS, onSettings, primary = !canAskAgain)
        }
        CaptureState.Opening -> Text(ChromaCopy.OPENING, modifier = polite)
        is CaptureState.Ready, is CaptureState.Locked -> {
            val facts = state.cameraFacts!!
            if (outcome != null) Result(outcome)
            StepHeader(ChromaStep.of(state, useCard, cardKept, resultShown = false))
            if (!facts.hasFlash) {
                Text(ChromaCopy.NO_FLASH, modifier = polite)
                Guidance(facts, live, steady)
                ChromaButton(ChromaCopy.AMBIENT_ONLY, { onMeasure(ShotRole.SURFACE, true, true) }, primary = true)
            } else {
                Guidance(facts, live, steady)
                CardSwitch(useCard, onUseCard)
                when {
                    !useCard -> {
                        Text(ChromaCopy.NO_CARD_STEP)
                        ChromaButton(ChromaCopy.MEASURE, { onMeasure(ShotRole.SURFACE, false, true) }, primary = true)
                    }
                    !cardKept -> {
                        Text(ChromaCopy.CARD_STEP)
                        ChromaButton(ChromaCopy.MEASURE_CARD, { onMeasure(ShotRole.CARD, false, true) }, primary = true)
                    }
                    else -> {
                        Text(ChromaCopy.SURFACE_STEP, modifier = polite)
                        Text(ChromaCopy.STOPPED_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
                        ChromaButton(ChromaCopy.MEASURE_SURFACE, { onMeasure(ShotRole.SURFACE, false, false) }, primary = true)
                        ChromaButton(ChromaCopy.RETAKE_CARD, { onMeasure(ShotRole.CARD, false, true) })
                    }
                }
            }
        }
        is CaptureState.Locking, is CaptureState.Ambient, is CaptureState.Flash -> {
            Text(
                if (state is CaptureState.Locking) ChromaCopy.SETTING_LIGHT else ChromaCopy.SHOOTING,
                modifier = polite,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            ChromaButton(ChromaCopy.CANCEL, onCancel)
        }
        is CaptureState.Done -> {
            // A colour is on its way to the screen (shown is set a moment after the outcome); a refusal ends here.
            if (outcome == null || outcome is Outcome.Reading || outcome is Outcome.RoomLight) {
                Text(ChromaCopy.SHOOTING, modifier = polite)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            } else {
                Result(outcome)
                ChromaButton(ChromaCopy.AGAIN, onAgain, primary = true)
            }
        }
        is CaptureState.Failed -> {
            Text(state.failure.message, modifier = polite)
            ChromaButton(ChromaCopy.AGAIN, onAgain, primary = true)
        }
        is CaptureState.Cancelled -> {
            Text(ChromaCopy.CANCELLED, modifier = polite)
            ChromaButton(ChromaCopy.AGAIN, onAgain, primary = true)
        }
    }
}

/** How far to hold the phone, whether it is steady, and a warning when the view is washed out. */
@Composable
private fun Guidance(facts: CameraFacts, live: LiveView, steady: Boolean?) {
    val target = HoldDistance.targetCm(facts.minFocusDiopters, live.brightRoom)
    val hint = HoldDistance.hint(live.distanceMetres, target)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ChromaCopy.holdAbout(target))
        val measured = live.distanceMetres
        when (hint) {
            DistanceHint.UNKNOWN -> Unit
            DistanceHint.GOOD -> Text(ChromaCopy.GOOD_DISTANCE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            DistanceHint.CLOSER -> Text(
                ChromaCopy.MOVE_CLOSER + (measured?.let { " " + ChromaCopy.distanceNow((it * 100).toInt()) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
            DistanceHint.FARTHER -> Text(
                ChromaCopy.MOVE_FARTHER + (measured?.let { " " + ChromaCopy.distanceNow((it * 100).toInt()) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
        }
        when (steady) {
            true -> Text(ChromaCopy.STEADY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            false -> Text(ChromaCopy.HOLD_STILL, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            null -> Unit
        }
        if (live.washedOut) Text(ChromaCopy.WASHED_OUT_LIVE, style = MaterialTheme.typography.bodySmall)
    }
}

/** The switch for the optional first step, with what the step is. */
@Composable
private fun CardSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ChromaCopy.USE_CARD)
            Text(ChromaCopy.STEP_CARD, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/** What is said of a measurement that gave no colour: a refusal in one sentence, or that the card is in. */
@Composable
private fun Result(outcome: Outcome) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (outcome) {
        Outcome.CardKept -> Text(ChromaCopy.CARD_KEPT, modifier = polite)
        is Outcome.Refused -> Text(outcome.reason.message, modifier = polite)
        // A colour is shown by the result step, from the screen's copy of it.
        is Outcome.RoomLight, is Outcome.Reading -> Unit
    }
}
