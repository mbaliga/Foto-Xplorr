package com.fotoxplorr.app.vision

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The colour-vision check, an Experimental entry: a few pictures of dots with a number hidden in
 * them, a row of digit buttons to answer with, and a result in plain words. Register it as
 * `ExperimentEntry(id = "colour-check", title = VisionCopy.CHECK_TITLE, blurb = VisionCopy.CHECK_BLURB,
 * group = ExperimentGroup.EXPLORE, ...) { env, onClose -> ColourVisionCheck(env, onClose) }`. It is
 * safe as `restorable = true`: opening it starts no work and asks for no permission.
 *
 * It is a game and says so on its first screen and on its last. It never names a condition: the
 * result is "this looks like it may be hard to tell red from green" or the like, with the line that
 * it is not a diagnosis and that an eye doctor can test properly ([ColourCheck] explains why that
 * caution is earned). It reads no photo, so [env] is accepted for the entry's signature and not used.
 *
 * Back: the host closes the experiment; this registers its own handler only while it is past the
 * introduction, to step back one picture (TRAPS #78). The answers are kept when stepping back.
 *
 * Process death: the seed, the step and the answers so far are saved with the screen's state, so the
 * same pictures come back at the same step; nothing is written anywhere else.
 *
 * Nothing here has run on a device: how the dots look on a real screen, at arm's length, with night
 * mode on, is a device check.
 */
@Composable
fun ColourVisionCheck(@Suppress("UNUSED_PARAMETER") env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    ColourVisionCheckScreen(remember { Random.nextLong() }, onClose, modifier)
}

/** The screen over a first [fallback] seed (a saved run, if there is one, wins), so a test can know the pictures. */
@Composable
internal fun ColourVisionCheckScreen(fallback: Long, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var run by rememberSaveable(stateSaver = Saver<CheckRun, String>(save = { it.encode() }, restore = { CheckRun.decode(it, fallback) })) {
        mutableStateOf(CheckRun(fallback))
    }
    val plates by produceState<List<Plate>?>(null, run.seed) {
        value = withContext(Dispatchers.Default) { ColourCheck.plates(run.seed) }
    }

    // An inner step: composed after the host's handler, so it is asked first; at the introduction it is off and the host closes.
    BackHandler(enabled = run.hasInnerStep) { run = run.back() }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        Header(onBack = { if (run.hasInnerStep) run = run.back() else onClose() })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shown = plates
            val index = run.plateIndex
            when {
                run.isIntro -> Intro(onStart = { run = run.start() })
                shown == null -> Text(VisionCopy.CHECK_WORKING, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                index != null -> PlateStep(
                    plate = shown[index],
                    index = index,
                    total = shown.size,
                    onAnswer = { run = run.answer(it) },
                )
                else -> Result(
                    verdict = ColourCheck.verdict(shown, run.padded()),
                    onAgain = { run = run.again(Random.nextLong()) },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = VisionCopy.BACK, tint = RoomStyle.Ink)
        }
        Text(
            VisionCopy.CHECK_TITLE,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = RoomStyle.Ink,
        )
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    Text(VisionCopy.CHECK_INTRO, style = MaterialTheme.typography.bodyLarge, color = RoomStyle.Ink)
    Text(VisionCopy.CHECK_SETUP, style = MaterialTheme.typography.bodyMedium, color = RoomStyle.InkMuted)
    Text(VisionCopy.PLATE_DISCLAIMER, style = MaterialTheme.typography.bodyMedium, color = RoomStyle.InkMuted)
    WideButton(VisionCopy.CHECK_START, onStart)
}

@Composable
private fun ColumnScope.PlateStep(plate: Plate, index: Int, total: Int, onAnswer: (Int?) -> Unit) {
    Text(
        VisionCopy.plateProgress(index + 1, total),
        style = MaterialTheme.typography.labelLarge,
        color = RoomStyle.InkMuted,
    )
    PlateView(plate, Modifier.fillMaxWidth().widthIn(max = 420.dp).align(Alignment.CenterHorizontally))
    Text(VisionCopy.PLATE_QUESTION, style = MaterialTheme.typography.titleMedium, color = RoomStyle.Ink)
    // Two rows of five: a digit button is at least 48 dp each way and the row fills the width.
    for (row in 0..1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (digit in row * 5 until row * 5 + 5) {
                OutlinedButton(
                    onClick = { onAnswer(digit) },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)
                        .semantics { contentDescription = VisionCopy.digitDescription(digit) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("$digit", style = MaterialTheme.typography.titleLarge, color = RoomStyle.Ink)
                }
            }
        }
    }
    WideButton(VisionCopy.PLATE_NOTHING, { onAnswer(null) }, filled = false)
}

@Composable
private fun Result(verdict: CheckVerdict, onAgain: () -> Unit, onClose: () -> Unit) {
    val lines = VisionCopy.resultLines(verdict)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        lines.forEachIndexed { i, line ->
            Text(
                line,
                style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (i == 0) RoomStyle.Ink else RoomStyle.InkMuted,
            )
        }
    }
    WideButton(VisionCopy.CHECK_AGAIN, onAgain)
    WideButton(VisionCopy.CLOSE, onClose, filled = false)
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, filled: Boolean = true) {
    val modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
    if (filled) {
        Button(onClick, modifier, colors = ButtonDefaults.buttonColors(containerColor = RoomStyle.Ink, contentColor = Color.Black)) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick, modifier) { Text(text, color = RoomStyle.Ink) }
    }
}

/**
 * One plate: a mid-grey square with the dots drawn in it. The dots are in a unit square; the
 * canvas is square, so each is placed by its fraction of the side. The description says what it is,
 * not what the number is (a screen reader cannot be given the answer, and the game would be over).
 */
@Composable
private fun PlateView(plate: Plate, modifier: Modifier = Modifier) {
    Box(modifier.semantics { contentDescription = VisionCopy.PLATE_DESCRIPTION }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(PLATE_GROUND),
        ) {
            val side = size.width
            for (dot in plate.dots) {
                drawCircle(Color(dot.argb), radius = dot.r * side, center = Offset(dot.x * side, dot.y * side))
            }
        }
    }
}

/** The plain grey behind the dots: neutral, so it does not tint what the eye adapts to. */
private val PLATE_GROUND = Color(0xFF7F7F7F)
