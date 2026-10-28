package com.fotoxplorr.app.reel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
 * The small controls the reel maker is built from. Every target is at least 48 dp; a chosen item says so with
 * a check mark and a border as well as colour, and a screen reader hears it as selected.
 */

/** Read out by a screen reader when it appears or changes, without cutting off what it is saying. */
internal fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A button with words, at least 48 dp tall. */
@Composable
internal fun ReelButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
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
internal fun ReelIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint.copy(alpha = 0.4f)),
    ) { Icon(icon, contentDescription = description) }
}

/** A 48 dp text button for a stepper: the sign is the words, the long description is for a screen reader. */
@Composable
internal fun ReelStepButton(text: String, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description }.size(width = 56.dp, height = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
        border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
    ) { Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
}

/** One choice of a few (a radio button): a border and a check mark when chosen, as well as the words. */
@Composable
internal fun ReelChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint.copy(alpha = if (enabled) 1f else 0.3f), shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint)
    }
}

/** An on/off button: its state is in the check mark and the border, and a screen reader hears it as a switch. */
@Composable
internal fun ReelToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
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
internal fun ReelStepper(
    valueText: String,
    lessLabel: String,
    moreLabel: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    canLess: Boolean = true,
    canMore: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReelStepButton("-", lessLabel, onLess, enabled = canLess)
        Text(valueText, modifier = Modifier.weight(1f).polite(), style = MaterialTheme.typography.bodyMedium, color = RoomStyle.Ink)
        ReelStepButton("+", moreLabel, onMore, enabled = canMore)
    }
}

/** A heading for a group of controls inside a panel. */
@Composable
internal fun ReelSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = RoomStyle.InkMuted,
    )
}

/** A group of controls laid out in a wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReelWrap(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A column of controls in a panel. */
@Composable
internal fun ReelColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
