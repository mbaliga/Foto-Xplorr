package com.fotoxplorr.app.wall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

// The wall screen sits on the pane's black ground, whose ink is white whatever the theme, so the
// controls name their colours instead of taking the theme's (dark on black in light theme).

/** A choice. The tick on a chosen one is the cue besides colour. */
@Composable
internal fun WallChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = RoomStyle.Ink,
            iconColor = Color.Black,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedLeadingIconColor = Color.Black,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = RoomStyle.InkFaint,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = RoomStyle.InkFaint,
            selectedBorderColor = Color.White,
            disabledBorderColor = RoomStyle.InkFaint.copy(alpha = 0.3f),
        ),
    )
}

@Composable
internal fun WallButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
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
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
            border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
        ) { Text(label) }
    }
}

/** A 48 dp icon button that always says what it does. */
@Composable
internal fun WallIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
    ) { Icon(icon, contentDescription = description) }
}

/**
 * A number the person types, in the unit the label names. [value] 0 shows empty; what is typed
 * is passed on as soon as it reads as a number (blank reads as 0). A value changed from outside
 * (a scale worked out from the photo) replaces what is shown.
 */
@Composable
internal fun NumberField(label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(if (value > 0f) FramePresets.number(value) else "") }
    LaunchedEffect(value) {
        if ((text.toFloatOrNull() ?: 0f) != value) text = if (value > 0f) FramePresets.number(value) else ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val clean = typed.filter { it.isDigit() || it == '.' }.take(7)
            val number = if (clean.isEmpty()) 0f else clean.toFloatOrNull()
            if (number != null) {
                text = clean
                onValue(number)
            }
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RoomStyle.Ink,
            unfocusedTextColor = RoomStyle.Ink,
            focusedLabelColor = RoomStyle.Ink,
            unfocusedLabelColor = RoomStyle.InkMuted,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = RoomStyle.InkFaint,
            cursorColor = Color.White,
        ),
    )
}
