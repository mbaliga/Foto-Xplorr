package com.fotoxplorr.app.chroma

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.min

/**
 * The Chroma ID experiment: the one entry the Experimental host registers (with `restorable = false`,
 * because it uses the camera and asks a permission). It reads nothing from [env]: the pictures are the
 * camera's own and no library photo is involved, so `mayExport` and `decode` do not apply.
 *
 * ## The permission
 * Asked here, when the experiment opens, with [ChromaCopy.PRIVACY] on screen as the reason; never at app
 * start. A refusal shows [ChromaCopy.CAMERA_DENIED] with Ask again and Settings.
 *
 * ## What is released, and when
 * Pause (and leaving) stops the job: the camera, session, image reader and thread are released and the
 * gyroscope is unregistered. Resume opens them again. Nothing is kept across that.
 *
 * ## What is saved
 * Nothing. The pictures live in memory for the few seconds a measurement takes and are overwritten
 * once the answer is worked out; no photo, no number and no setting is written anywhere. A card
 * measurement is kept as numbers only while the light stays held, and is dropped by leaving, pausing,
 * turning the phone (the activity is recreated) or measuring the card again. The "use a card" switch
 * survives turning the phone; the card step then starts over.
 *
 * Nothing here has run on a device. There is no drag, so the shell's edge zones (TRAPS #72) do not matter.
 */
@Composable
fun ChromaIdExperience(@Suppress("UNUSED_PARAMETER") env: ExperimentEnv, onClose: () -> Unit) {
    val context = LocalContext.current
    val choice = remember { CameraProbe.choose(context.applicationContext) }
    PaneScaffold(title = ChromaCopy.TITLE, onBack = onClose) {
        if (choice == null) {
            Column(Modifier.padding(horizontal = RoomStyle.GutterStart, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ChromaCopy.NO_CAMERA, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(ChromaCopy.PRIVACY, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
            }
        } else {
            ChromaCameraBody(choice)
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ChromaCameraBody(choice: CameraChoice) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val surfaceFlow = remember { MutableStateFlow<Surface?>(null) }
    val motion = remember { MotionWindow() }
    val source = remember(choice) { Camera2Source(context.applicationContext, choice, surfaceFlow) }
    val job = remember(source) { ChromaJob(source, motion, scope) }
    val gyro = remember { GyroSensor(context.applicationContext, motion) }

    var permitted by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var useCard by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        job.permissionResult(granted)
    }

    fun ask() {
        asked = true
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
                        !asked -> ask()
                        else -> job.permissionResult(false)
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

    val rotationDegrees = (LocalView.current.display?.rotation ?: Surface.ROTATION_0) * 90
    val aspect = PreviewShape.displayAspect(
        choice.facts.previewWidth,
        choice.facts.previewHeight,
        choice.sensorOrientation,
        rotationDegrees,
    )

    Column(Modifier.fillMaxSize()) {
        if (permitted) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = RoomStyle.GutterStart)) {
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
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
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
                    useCard = want
                    if (!want) job.forgetCard()
                },
                live = live,
                steady = steady,
                onAsk = ::ask,
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onMeasure = { role, ambientOnly, relock -> job.measure(role, ambientOnly, relock) },
                onCancel = job::cancel,
                onAgain = {
                    job.again()
                    if (job.state.value == CaptureState.Idle && permitted) job.open()
                },
            )
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
) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        CaptureState.Idle, CaptureState.NeedsPermission -> {
            Text(ChromaCopy.PRIVACY, modifier = polite)
            ChromaButton(ChromaCopy.ALLOW_CAMERA, onAsk, primary = true)
        }
        CaptureState.PermissionDenied -> {
            Text(ChromaCopy.CAMERA_DENIED, modifier = polite)
            ChromaButton(ChromaCopy.ASK_AGAIN, onAsk, primary = true)
            ChromaButton(ChromaCopy.OPEN_SETTINGS, onSettings)
        }
        CaptureState.Opening -> Text(ChromaCopy.OPENING, modifier = polite)
        is CaptureState.Ready, is CaptureState.Locked -> {
            val facts = state.cameraFacts!!
            if (outcome != null) Result(outcome)
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
            if (outcome == null) {
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
        Text(ChromaCopy.USE_CARD, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** The finished reading, in words and numbers as well as the swatch, so colour alone never carries it. */
@Composable
private fun Result(outcome: Outcome) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (outcome) {
        Outcome.CardKept -> Text(ChromaCopy.CARD_KEPT, modifier = polite)
        is Outcome.Refused -> Text(outcome.reason.message, modifier = polite)
        is Outcome.RoomLight -> {
            ReportBlock(outcome.report, polite)
            Text(ChromaCopy.AMBIENT_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        }
        is Outcome.Reading -> {
            ReportBlock(outcome.reading.report, polite)
            val notes = buildList {
                if (outcome.withCard) add(ChromaCopy.MEASURED)
                outcome.reading.measurement.cautions.forEach { add(it.message) }
            }
            notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
        }
    }
}

@Composable
private fun ReportBlock(report: ColourReport, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(72.dp)
                .background(Color(report.argb or (0xFF shl 24)))
                .border(BorderStroke(1.dp, RoomStyle.InkFaint))
                .semantics { contentDescription = "${report.description}, ${report.hex}" },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(report.description, style = MaterialTheme.typography.titleMedium)
            Text("${report.hex}  RGB ${report.red} ${report.green} ${report.blue}", style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            report.nearest.forEach {
                Text(ChromaCopy.nearest(it.name, it.deltaE), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            }
        }
    }
}

/** A button with words, at least 48 dp tall. */
@Composable
private fun ChromaButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink),
            border = BorderStroke(1.dp, RoomStyle.InkFaint),
        ) { Text(label) }
    }
}
