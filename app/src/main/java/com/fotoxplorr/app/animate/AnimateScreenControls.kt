package com.fotoxplorr.app.animate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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

// The screen's few controls, in the room language the panorama and palette screens use: white on black, 48 dp
// at least, words on every button, and a chosen thing marked by a check and a border as well as by colour.

/** Read out by a screen reader when it appears or changes, without cutting off what it is saying. */
internal fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A section's title: a heading for a screen reader. */
@Composable
internal fun AnHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** A small hint line under or beside controls. */
@Composable
internal fun AnCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
}

/** A button with words, at least 48 dp tall; [primary] is the one white button of a page. */
@Composable
internal fun AnButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).defaultMinSize(minWidth = 48.dp),
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
            modifier = modifier.heightIn(min = 48.dp).defaultMinSize(minWidth = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
            border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
        ) { Text(label) }
    }
}

/** A 48 dp icon button that always says what it does. */
@Composable
internal fun AnIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint.copy(alpha = 0.4f)),
    ) { Icon(icon, contentDescription = description) }
}

/** A 48 dp text button for a stepper: the sign and size are the words ("-0.5"), the long description is for a screen reader. */
@Composable
internal fun AnStepButton(text: String, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
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
 * A switch with its words on the left and its state in words ("On", "Off") beside the switch, so the state
 * is not told by the switch's colour alone. The whole row is the target and says "switch" to a screen reader.
 */
@Composable
internal fun AnToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint)
        Text(if (checked) AnimateScreenCopy.ON else AnimateScreenCopy.OFF, style = MaterialTheme.typography.labelMedium, color = RoomStyle.InkMuted)
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.Black,
                uncheckedBorderColor = RoomStyle.InkFaint,
            ),
        )
    }
}

/**
 * One choice of a few (a radio button): a border and a check mark when chosen, as well as the words, so the
 * chosen one is not told by colour alone. [note] is a short line under the label. At least 48 dp tall.
 */
@Composable
internal fun AnChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
) {
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
        Column(Modifier.weight(1f, fill = false)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        }
    }
}
