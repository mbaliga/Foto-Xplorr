package com.fotoxplorr.app.palettebuilder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

/** The size of a swatch tile: a touch target of at least 48 dp with room for the marks on it. */
private val TILE = 56.dp
private val TILE_GAP = 8.dp

/**
 * The palette's swatches, wrapped to the width. A tile is one radio button: tapping picks it. The picked tile has
 * a thick white border and a tick, and a locked one a padlock, each on a small disc that reads on any colour, so
 * neither is told by colour alone. The hex sits under the tile as text.
 */
@Composable
internal fun SwatchStrip(palette: Palette, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow = ((maxWidth + TILE_GAP) / (TILE + TILE_GAP)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            palette.colors.chunked(perRow).forEachIndexed { row, colours ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    colours.forEachIndexed { column, colour ->
                        val index = row * perRow + column
                        Tile(index, palette.size, colour, picked = index == selected, onPick = { onPick(index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(index: Int, count: Int, colour: PaletteColor, picked: Boolean, onPick: () -> Unit) {
    Column(
        Modifier
            .width(TILE)
            .semantics(mergeDescendants = true) {
                contentDescription = PaletteBuilderCopy.swatch(index + 1, count, colour.hex, colour.locked, picked)
            }
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE)
                .background(Color(colour.argb), RoundedCornerShape(8.dp))
                .border(if (picked) 3.dp else 1.dp, if (picked) Color.White else RoomStyle.InkFaint, RoundedCornerShape(8.dp)),
        ) {
            if (picked) Mark(Icons.Outlined.Check, Modifier.align(Alignment.TopStart))
            if (colour.locked) Mark(Icons.Outlined.Lock, Modifier.align(Alignment.BottomEnd))
        }
        Text(colour.hex, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = RoomStyle.InkMuted)
    }
}

/** A small white disc with a black glyph: readable on every swatch colour. */
@Composable
private fun Mark(icon: ImageVector, modifier: Modifier) {
    Box(modifier.padding(3.dp).size(18.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp))
    }
}

/** One stepper: the label and its value, then four buttons (two sizes each way). */
@Composable
internal fun Stepper(
    label: String,
    value: String,
    fine: Int,
    coarse: Int,
    canLower: Boolean,
    canRaise: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = RoomStyle.InkMuted, modifier = Modifier.polite())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PbStepButton("-$coarse", PaletteBuilderCopy.lower(label, coarse), { onChange(-coarse) }, enabled = canLower)
            PbStepButton("-$fine", PaletteBuilderCopy.lower(label, fine), { onChange(-fine) }, enabled = canLower)
            PbStepButton("+$fine", PaletteBuilderCopy.raise(label, fine), { onChange(fine) }, enabled = canRaise)
            PbStepButton("+$coarse", PaletteBuilderCopy.raise(label, coarse), { onChange(coarse) }, enabled = canRaise)
        }
    }
}

/** The picked colour's three steppers. Hue is in degrees, the other two in percent. */
@Composable
internal fun HslSteppers(read: HslReadout, onNudge: (HslChannel, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Stepper(
            PaletteBuilderCopy.HUE, PaletteBuilderCopy.degrees(read.hue), HUE_FINE, HUE_COARSE,
            canLower = true, canRaise = true,
        ) { onNudge(HslChannel.HUE, it) }
        Stepper(
            PaletteBuilderCopy.SATURATION, PaletteBuilderCopy.percent(read.saturation), PERCENT_FINE, PERCENT_COARSE,
            canLower = read.saturation > 0, canRaise = read.saturation < 100,
        ) { onNudge(HslChannel.SATURATION, it) }
        Stepper(
            PaletteBuilderCopy.LIGHTNESS, PaletteBuilderCopy.percent(read.lightness), PERCENT_FINE, PERCENT_COARSE,
            canLower = read.lightness > 0, canRaise = read.lightness < 100,
        ) { onNudge(HslChannel.LIGHTNESS, it) }
    }
}

internal const val HUE_FINE = 3
internal const val HUE_COARSE = 15
internal const val PERCENT_FINE = 2
internal const val PERCENT_COARSE = 10

/** What the buttons under the strip do to the picked swatch. */
internal class SwatchActions(
    val onLock: () -> Unit,
    val onAdd: () -> Unit,
    val onRemove: () -> Unit,
    val onLeft: () -> Unit,
    val onRight: () -> Unit,
)

/** Lock, move left and right, add and remove, for the picked swatch: the way to reorder without a drag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwatchButtons(draft: PaletteDraft, actions: SwatchActions) {
    val locked = draft.colour?.locked == true
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PbButton(
            if (locked) PaletteBuilderCopy.UNLOCK else PaletteBuilderCopy.LOCK,
            actions.onLock,
            icon = if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
        )
        PbButton(PaletteBuilderCopy.MOVE_LEFT, actions.onLeft, enabled = draft.canMoveLeft, icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft)
        PbButton(PaletteBuilderCopy.MOVE_RIGHT, actions.onRight, enabled = draft.canMoveRight, icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight)
        PbButton(PaletteBuilderCopy.ADD, actions.onAdd, enabled = draft.canAdd, icon = Icons.Outlined.Add)
        PbButton(PaletteBuilderCopy.REMOVE, actions.onRemove, enabled = draft.canRemove, icon = Icons.Outlined.Delete)
    }
    if (!draft.canAdd) PbCaption(PaletteBuilderCopy.AT_MAX)
    if (!draft.canRemove) PbCaption(PaletteBuilderCopy.AT_MIN)
}

/** One button per harmony; pressing one rebuilds the palette around the picked swatch. Undo takes it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HarmonyButtons(canUndo: Boolean, onHarmony: (HarmonyKind) -> Unit, onUndo: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HarmonyKind.entries.forEach { kind -> PbButton(kind.label, { onHarmony(kind) }) }
        PbButton(PaletteBuilderCopy.UNDO, onUndo, enabled = canUndo, icon = Icons.Outlined.Undo)
    }
}

/** A swatch strip with no picking, for the recolour page and the saved list. */
@Composable
internal fun MiniStrip(colours: List<Int>, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .border(BorderStroke(1.dp, RoomStyle.InkFaint), RoundedCornerShape(6.dp))
            .background(Color.Black, RoundedCornerShape(6.dp)),
    ) {
        colours.forEach { argb -> Box(Modifier.weight(1f).fillMaxWidth().height(height).background(Color(argb))) }
    }
}
