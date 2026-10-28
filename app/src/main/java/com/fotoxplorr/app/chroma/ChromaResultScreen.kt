package com.fotoxplorr.app.chroma

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle
import java.text.DateFormat
import java.util.Date

/** The words for how a reading was made, as a tag beside it in a list. */
internal val ReadingKind.label: String
    get() = when (this) {
        ReadingKind.APPROXIMATE -> ChromaCopy.KIND_APPROXIMATE
        ReadingKind.CALIBRATED -> ChromaCopy.KIND_CALIBRATED
        ReadingKind.ROOM -> ChromaCopy.KIND_ROOM
    }

/** What the result card can do. */
internal class ResultActions(val onCopy: () -> Unit, val onCompare: () -> Unit, val onSave: () -> Unit)

private val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }

/** A flat patch of colour. [description] is what a screen reader says for it; null for a purely decorative one. */
@Composable
internal fun Swatch(argb: Int, size: Dp, description: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Color(argb or (0xFF shl 24)))
            .border(BorderStroke(1.dp, RoomStyle.InkFaint))
            .semantics { if (description != null) contentDescription = description },
    )
}

@Composable
internal fun StepHeader(step: ChromaStep) {
    Text(ChromaCopy.step(step.number), style = MaterialTheme.typography.labelLarge, color = RoomStyle.InkMuted, modifier = Modifier.semantics { heading() })
}

/**
 * The finished reading in words and numbers as well as a swatch, so colour alone never carries it: the
 * words, hex, RGB, Lab and LCh, the three nearest names with their distance, and how far to trust it.
 * With [actions] it also offers copy, compare and save, each at least 48 dp tall.
 */
@Composable
internal fun ReadingCard(view: ReadingView, actions: ResultActions?, notice: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(view.report.argb, 72.dp, view.swatchDescription)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(view.report.description, style = MaterialTheme.typography.titleMedium, modifier = polite)
                Text(view.hex, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(view.accuracy, modifier = polite)
        view.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
        Column {
            for (line in listOf(view.rgbLine, view.labLine, view.lchLine)) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            }
        }
        Text(ChromaCopy.NEAREST, style = MaterialTheme.typography.labelLarge, color = RoomStyle.InkMuted, modifier = Modifier.semantics { heading() })
        Column {
            view.nearestLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
        }
        if (actions != null) {
            ChromaButton(ChromaCopy.COPY_HEX, actions.onCopy)
            ChromaButton(ChromaCopy.COMPARE, actions.onCompare)
            ChromaButton(ChromaCopy.SAVE_TO_PALETTE, actions.onSave)
        }
        if (notice != null) Text(notice, modifier = polite)
    }
}

private fun timeText(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** One reading in a list: a swatch, its words, its hex, how it was made and when. */
@Composable
private fun ReadingLine(reading: SavedReading, modifier: Modifier = Modifier) {
    val report = remember(reading.argb) { ColourReport.of(reading.argb) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(reading.argb, 48.dp, "${report.description}, ${reading.hex}")
        Column {
            Text(report.description, style = MaterialTheme.typography.bodyLarge)
            Text("${reading.hex}  ${reading.kind.label}", style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            Text(timeText(reading.atMillis), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        }
    }
}

/**
 * The past readings, newest first, each with copy, compare and save, and a delete-all that asks once.
 * [load] is null while the file is being read.
 */
@Composable
internal fun HistoryPanel(
    load: HistoryLoad?,
    notice: String?,
    onCopy: (SavedReading) -> Unit,
    onCompare: (SavedReading) -> Unit,
    onSave: (SavedReading) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf(false) }
    val readings = load?.readings.orEmpty()
    LazyColumn(
        modifier.padding(horizontal = RoomStyle.GutterStart),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(ChromaCopy.HISTORY, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        item { Text(ChromaCopy.HISTORY_KEPT, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
        if (load?.unreadable == true) item { Text(ChromaCopy.HISTORY_UNREADABLE, modifier = polite) }
        if (load?.damaged == true) item { Text(ChromaCopy.HISTORY_DAMAGED, style = MaterialTheme.typography.bodySmall) }
        if (notice != null) item { Text(notice, modifier = polite) }
        if (load != null && readings.isEmpty() && !load.unreadable) item { Text(ChromaCopy.HISTORY_EMPTY) }
        itemsIndexed(readings, key = { i, r -> "$i ${r.toLine()}" }) { _, r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReadingLine(r)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(ChromaCopy.COPY_HEX, ChromaCopy.copyHexOf(r.hex), { onCopy(r) }, Modifier.weight(1f))
                    SmallButton(ChromaCopy.COMPARE_SHORT, ChromaCopy.compareHex(r.hex), { onCompare(r) }, Modifier.weight(1f))
                    SmallButton(ChromaCopy.SAVE_SHORT, ChromaCopy.saveHex(r.hex), { onSave(r) }, Modifier.weight(1f))
                }
            }
        }
        if (readings.isNotEmpty() || load?.unreadable == true) {
            item {
                if (confirming) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ChromaCopy.DELETE_SURE, modifier = polite)
                        ChromaButton(ChromaCopy.DELETE_YES, { confirming = false; onDeleteAll() }, primary = true)
                        ChromaButton(ChromaCopy.DELETE_NO, { confirming = false })
                    }
                } else {
                    ChromaButton(ChromaCopy.DELETE_ALL, { confirming = true })
                }
            }
        }
        item { ChromaButton(ChromaCopy.MEASURE_NEW, onBack, primary = true) }
    }
}

/**
 * Two readings side by side with the CIEDE2000 distance and a plain verdict. With no [other] yet it
 * lists the [candidates] to pick from.
 */
@Composable
internal fun ComparePanel(
    subject: SavedReading,
    other: SavedReading?,
    candidates: List<SavedReading>,
    onPick: (SavedReading) -> Unit,
    onPickAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (other == null) {
        LazyColumn(modifier.padding(horizontal = RoomStyle.GutterStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(ChromaCopy.COMPARE_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            item { ReadingLine(subject) }
            item {
                Text(
                    if (candidates.isEmpty()) ChromaCopy.COMPARE_NOTHING else ChromaCopy.COMPARE_PICK,
                    modifier = polite,
                )
            }
            itemsIndexed(candidates, key = { i, r -> "$i ${r.toLine()}" }) { _, r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReadingLine(r)
                    ChromaButton(ChromaCopy.COMPARE_WITH_THIS, { onPick(r) }, description = ChromaCopy.compareWithHex(r.hex))
                }
            }
            item { ChromaButton(ChromaCopy.BACK, onBack, primary = candidates.isEmpty()) }
        }
        return
    }
    val view = remember(subject, other) { CompareView.of(subject, other) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = RoomStyle.GutterStart),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(ChromaCopy.COMPARE_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompareSide(ChromaCopy.COMPARE_THIS, view.first, subject, Modifier.weight(1f))
            CompareSide(ChromaCopy.COMPARE_OTHER, view.second, other, Modifier.weight(1f))
        }
        Text(view.differenceLine, style = MaterialTheme.typography.titleMedium, modifier = polite)
        Text(view.verdict.level.message, modifier = polite)
        view.verdict.detail?.let { Text(it) }
        if (view.eitherApproximate) Text(ChromaCopy.COMPARE_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        ChromaButton(ChromaCopy.COMPARE_PICK_OTHER, onPickAgain)
        ChromaButton(ChromaCopy.BACK, onBack, primary = true)
    }
}

@Composable
private fun CompareSide(label: String, view: ReadingView, saved: SavedReading, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = RoomStyle.InkMuted)
        Swatch(view.report.argb, 96.dp, "$label: ${view.swatchDescription}")
        Text(view.report.description, style = MaterialTheme.typography.bodyMedium)
        Text(view.hex, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        Text(saved.kind.label, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
    }
}

/**
 * The saved palettes as choices for [colour], and the new-palette choice. [targets] is null while the
 * list is being read. A choice that cannot be taken says why, in words, and does nothing.
 */
@Composable
internal fun SavePanel(
    colour: SavedReading,
    targets: List<PaletteTarget>?,
    notice: String?,
    onPick: (PaletteTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = RoomStyle.GutterStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(ChromaCopy.SAVE_TO_PALETTE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        item { ReadingLine(colour) }
        item { Text(ChromaCopy.SAVE_PICK, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
        if (notice != null) item { Text(notice, modifier = polite) }
        if (targets != null && targets.none { it is PaletteTarget.AddTo }) item { Text(ChromaCopy.SAVE_NONE) }
        itemsIndexed(targets.orEmpty(), key = { i, t -> "$i ${targetKey(t)}" }) { _, t ->
            when (t) {
                is PaletteTarget.AddTo -> when (t.reason) {
                    null -> ChromaButton(ChromaCopy.addTo(t.name, t.size), { onPick(t) })
                    Unavailable.FULL -> Text(ChromaCopy.paletteFull(t.name), Modifier.heightIn(min = 48.dp))
                    Unavailable.ALREADY_THERE -> Text(ChromaCopy.paletteHasIt(t.name), Modifier.heightIn(min = 48.dp))
                }
                is PaletteTarget.NewPalette -> ChromaButton(ChromaCopy.newPaletteWith(t.name), { onPick(t) })
                PaletteTarget.NewNeedsTwo -> Text(ChromaCopy.NEW_NEEDS_TWO, Modifier.heightIn(min = 48.dp))
            }
        }
        item { ChromaButton(ChromaCopy.BACK, onBack, primary = true) }
    }
}

private fun targetKey(t: PaletteTarget): String = when (t) {
    is PaletteTarget.AddTo -> "add ${t.name}"
    is PaletteTarget.NewPalette -> "new ${t.name}"
    PaletteTarget.NewNeedsTwo -> "needs two"
}

/** A button with words, at least 48 dp tall. */
@Composable
internal fun ChromaButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val described = if (description == null) modifier else modifier.semantics { contentDescription = description }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = described.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = described.heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink),
            border = BorderStroke(1.dp, RoomStyle.InkFaint),
        ) { Text(label) }
    }
}

/** A short-worded button for a row of three, at least 48 dp tall. */
@Composable
private fun SmallButton(label: String, description: String, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink),
        border = BorderStroke(1.dp, RoomStyle.InkFaint),
    ) { Text(label, maxLines = 2) }
}
