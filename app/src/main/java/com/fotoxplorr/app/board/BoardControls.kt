package com.fotoxplorr.app.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

/*
 * The small controls the mood board is built from. Every target is at least 48 dp; a chosen item says
 * so with a check mark and a border as well as colour, and a screen reader hears it as selected.
 */

/** Read out by a screen reader when it appears or changes, without cutting off what it is saying. */
internal fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A button with words, at least 48 dp tall. */
@Composable
internal fun BoardButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContainerColor = RoomStyle.InkFaint.copy(alpha = 0.3f),
                disabledContentColor = RoomStyle.InkFaint,
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
            border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
        ) { Text(label) }
    }
}

/** A 48 dp icon button that always says what it does. */
@Composable
internal fun BoardIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint.copy(alpha = 0.4f)),
    ) { Icon(icon, contentDescription = description) }
}

/** A 48 dp text button for a stepper: the sign and size are the words ("-15"), the long description is for a screen reader. */
@Composable
internal fun BoardStepButton(text: String, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description }.size(width = 56.dp, height = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
        border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
    ) { Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
}

/**
 * One choice of a few (a radio button): a border and a check mark when chosen, as well as the words.
 * At least 48 dp tall.
 */
@Composable
internal fun BoardChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint.copy(alpha = if (enabled) 1f else 0.3f), shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint)
    }
}

/** An on/off button: its state is in the check mark and the border, and a screen reader hears it as a toggle. */
@Composable
internal fun BoardToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .border(if (checked) 2.dp else 1.dp, if (checked) Color.White else RoomStyle.InkFaint.copy(alpha = if (enabled) 1f else 0.3f), shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (checked) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint)
    }
}

/** A minus button, the value in words, and a plus button: the way to change a number without a slider. */
@Composable
internal fun BoardStepper(
    valueText: String,
    lessLabel: String,
    moreLabel: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    canLess: Boolean = true,
    canMore: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardStepButton("-", lessLabel, onLess, enabled = enabled && canLess)
        Text(
            valueText,
            modifier = Modifier.weight(1f).polite(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint,
        )
        BoardStepButton("+", moreLabel, onMore, enabled = enabled && canMore)
    }
}

/** A heading for a group of controls inside a panel. */
@Composable
internal fun BoardSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = RoomStyle.InkMuted,
    )
}

/** A round-cornered square of colour, 48 dp: the colour is in the picture and its name in the words a screen reader hears. */
@Composable
internal fun BoardSwatch(argb: Int, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(8.dp)
    val colour = Color(argb)
    Box(
        modifier
            .size(48.dp)
            .background(colour, shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint, shape)
            .semantics { contentDescription = description }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Dark on a light colour and light on a dark one, so the mark shows on every swatch.
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = if (isLight(argb)) Color.Black else Color.White)
    }
}

/** A row of swatches that wraps. [extra] are further colours (the photos' own) with their own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardColourChoices(
    selectedArgb: Int?,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    extra: List<Pair<Int, String>> = emptyList(),
    enabled: Boolean = true,
) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardColours.BASICS.forEach { c ->
            BoardSwatch(c.argb, c.name, selected = selectedArgb == c.argb, onClick = { onPick(c.argb) }, enabled = enabled)
        }
        extra.forEach { (argb, words) ->
            BoardSwatch(argb, words, selected = selectedArgb == argb, onClick = { onPick(argb) }, enabled = enabled)
        }
    }
}

/** Whether black reads better than white on [argb] (relative luminance over one half). */
internal fun isLight(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) > 140.0
}

/** A group of controls laid out in a wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardWrap(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A column of controls in a panel. */
@Composable
internal fun BoardColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
