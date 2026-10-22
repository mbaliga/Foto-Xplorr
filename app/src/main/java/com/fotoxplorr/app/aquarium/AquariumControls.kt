package com.fotoxplorr.app.aquarium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

// The aquarium screens sit on black or on the blue of the water, both fixed (semantic): white ink
// whatever the theme, like the other full-screen experiments.

/** A 48 dp icon button that always says what it does. */
@Composable
internal fun AquariumIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
internal fun AquariumButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
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

/** A choice. The tick on a chosen one is the cue besides colour. */
@Composable
internal fun AquariumChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = RoomStyle.Ink,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
            selectedLeadingIconColor = Color.Black,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = RoomStyle.InkFaint,
            selectedBorderColor = Color.White,
        ),
    )
}

/**
 * One colour of the palette: a 48 dp target holding a round swatch. The chosen one has a ring AND
 * a tick, and the name is the accessibility label, so colour is never the only cue.
 */
@Composable
internal fun SwatchButton(swatch: Swatch, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = Color(swatch.argb)
    Box(
        modifier = modifier
            .size(48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = swatch.name },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(color, CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
