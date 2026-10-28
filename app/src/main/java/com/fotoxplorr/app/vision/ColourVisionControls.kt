package com.fotoxplorr.app.vision

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.hyle.HyleStepper
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle
import kotlinx.coroutines.flow.MutableStateFlow

/** Why the corrected-copy row cannot be used right now, or null when it can. */
internal enum class SaveBlock { NEEDS_TYPE, SENSITIVE, NO_RUNNER, NOT_PHOTO_FILE }

/**
 * The reason, if any, that "Save a copy" is off. In the order a person can fix them: pick a type
 * first; a file whose pixels cannot be read, or one the library does not own ([canSave] false, e.g.
 * opened from another app), has no copy; a photo marked [sensitive] is not copied (the copy would not
 * carry the mark); and with no job list there is nowhere to run it. Pure.
 */
internal fun saveBlock(choice: ColourVisionChoice, support: VisionSupport, sensitive: Boolean, canSave: Boolean, hasRunner: Boolean): SaveBlock? = when {
    !choice.filtering -> SaveBlock.NEEDS_TYPE
    support != VisionSupport.FULL || !canSave -> SaveBlock.NOT_PHOTO_FILE
    sensitive -> SaveBlock.SENSITIVE
    !hasRunner -> SaveBlock.NO_RUNNER
    else -> null
}

/**
 * The "Colour vision" group of the viewer's view options room: which colours are hard to see (or
 * Off), whether to show how it looks or to help tell colours apart, a strength slider with a stepper
 * beside it, names, patterns, and a corrected copy. Every control that changes a value by dragging
 * (the slider) has a tap alternative (the stepper); every choice is a 48 dp row that says it is
 * selected with a tick and a screen-reader state, not by colour.
 *
 * For a video the group is one sentence: the player has no colour filter here.
 */
@Composable
internal fun ColumnScope.ColourVisionRoomSection(
    viewer: ColourVisionViewer,
    asset: MediaAsset,
    support: VisionSupport,
    sensitive: Boolean,
    canSave: Boolean,
    mayCopy: (MediaAsset) -> Boolean,
) {
    RoomRule(Modifier.padding(vertical = 14.dp))
    RoomEyebrow(VisionCopy.ROOM_EYEBROW)
    if (support == VisionSupport.NONE) {
        Text(VisionCopy.NOT_FOR_VIDEO, color = RoomStyle.InkFaint, style = RoomStyle.Caption, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val choice = viewer.choice
    val context = LocalContext.current
    val jobs by (viewer.runner?.jobs ?: NO_JOBS).collectAsState()
    val saving = jobs.firstOrNull { it.title == VisionCopy.JOB_TITLE }

    GroupLabel(VisionCopy.TYPE_LABEL)
    ChoiceRow(VisionCopy.OFF, choice.deficiency == null, "colour-vision-off") { viewer.update { it.withDeficiency(null) } }
    for (deficiency in Deficiency.entries) {
        ChoiceRow(VisionCopy.deficiencyLabel(deficiency), choice.deficiency == deficiency, "colour-vision-$deficiency") {
            viewer.update { it.withDeficiency(deficiency) }
        }
    }

    if (choice.filtering) {
        GroupLabel(VisionCopy.KIND_LABEL)
        ChoiceRow(VisionCopy.SIMULATE, choice.kind == VisionKind.SIMULATE, "colour-vision-simulate") {
            viewer.update { it.withKind(VisionKind.SIMULATE) }
        }
        ChoiceRow(VisionCopy.DALTONIZE, choice.kind == VisionKind.HELP, "colour-vision-help") {
            viewer.update { it.withKind(VisionKind.HELP) }
        }
        if (choice.kind == VisionKind.HELP) Caption(VisionCopy.DALTONIZE_NOTE)
        if (support == VisionSupport.VIEW_ONLY) Caption(VisionCopy.FILTER_ONLY)

        RoomRow(label = VisionCopy.SEVERITY) {
            HyleStepper(
                label = VisionCopy.SEVERITY,
                value = choice.severityLabel,
                onDecrease = { viewer.update { it.stepSeverity(-1) } },
                onIncrease = { viewer.update { it.stepSeverity(1) } },
                canDecrease = choice.canWeaken,
                canIncrease = choice.canStrengthen,
            )
        }
        Slider(
            value = choice.severityStep.toFloat(),
            onValueChange = { position -> viewer.update { it.withSeverityPosition(position) } },
            valueRange = ColourVisionChoice.MIN_STEP.toFloat()..ColourVisionChoice.STEPS.toFloat(),
            steps = ColourVisionChoice.STEPS - ColourVisionChoice.MIN_STEP - 1,
            colors = SliderDefaults.colors(
                thumbColor = RoomStyle.Ink,
                activeTrackColor = RoomStyle.Ink,
                inactiveTrackColor = RoomStyle.InkFaint,
                activeTickColor = Color.Black,
                inactiveTickColor = Color.Black,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag("colour-vision-strength")
                .semantics {
                    contentDescription = VisionCopy.SEVERITY_SLIDER
                    stateDescription = choice.severityLabel
                },
        )
    }

    val readable = support == VisionSupport.FULL
    RoomRow(
        label = VisionCopy.NAME_COLOURS,
        caption = if (readable) VisionCopy.NAME_A_COLOUR else VisionCopy.FILTER_ONLY,
        enabled = readable,
        onClick = { viewer.update { it.withNaming(!it.naming) } },
    ) {
        RoomToggle(choice.naming, { on -> viewer.update { it.withNaming(on) } }, enabled = readable, description = VisionCopy.NAME_COLOURS)
    }

    val patternsOn = readable && choice.filtering
    RoomRow(
        label = VisionCopy.PATTERNS_LABEL,
        caption = when {
            !readable -> VisionCopy.FILTER_ONLY
            !choice.filtering -> VisionCopy.PATTERNS_NEED_TYPE
            choice.patterns && viewer.hatch == HatchState.NoneAlike -> VisionCopy.NO_CONFUSION
            choice.patterns && viewer.hatch == HatchState.Working -> VisionCopy.WORKING
            else -> VisionCopy.PATTERNS
        },
        enabled = patternsOn,
        onClick = { viewer.update { it.withPatterns(!it.patterns) } },
    ) {
        RoomToggle(
            choice.hatching,
            { on -> viewer.update { it.withPatterns(on) } },
            enabled = patternsOn,
            description = VisionCopy.PATTERNS_LABEL,
        )
    }

    val block = saveBlock(choice, support, sensitive, canSave, hasRunner = viewer.runner != null)
    RoomRow(
        label = if (choice.kind == VisionKind.HELP) VisionCopy.SAVE_HELP else VisionCopy.SAVE_SIMULATED,
        caption = when (block) {
            SaveBlock.NEEDS_TYPE -> VisionCopy.SAVE_NEED_TYPE
            SaveBlock.SENSITIVE -> VisionCopy.SAVE_SENSITIVE
            SaveBlock.NOT_PHOTO_FILE -> VisionCopy.FILTER_ONLY
            SaveBlock.NO_RUNNER -> VisionCopy.SAVE_NO_JOBS
            null -> if (saving != null) VisionCopy.SAVING else VisionCopy.SAVE_CAPTION
        },
        enabled = block == null && saving == null,
        onClick = { startCorrectedCopy(viewer, asset, context, mayCopy) },
    ) {
        if (saving != null) {
            TextButton(onClick = saving.cancel, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(VisionCopy.SAVE_CANCEL, color = RoomStyle.Ink, style = RoomStyle.Row)
            }
        }
    }
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

@Composable
private fun GroupLabel(text: String) {
    Text(text, color = RoomStyle.InkFaint, style = RoomStyle.Caption, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text, color = RoomStyle.InkFaint, style = RoomStyle.Caption, modifier = Modifier.padding(bottom = 6.dp))
}

/**
 * One choice among several, a whole 48 dp row. Selected is a tick and `selected` in the semantics
 * (a radio button), never only a change of colour.
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = RoomStyle.Ink, style = RoomStyle.Row, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = RoomStyle.Ink)
        }
    }
}
