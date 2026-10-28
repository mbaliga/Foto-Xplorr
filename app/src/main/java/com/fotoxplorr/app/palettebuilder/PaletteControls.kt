package com.fotoxplorr.app.palettebuilder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

// The builder's few controls, in the room language the panorama screen uses: white on black, 48 dp at least,
// words on every button. They are private to the package so the pages read as lists of what they offer.

/** Read out by a screen reader when it appears or changes, without cutting off what it is saying. */
internal fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A section's title: a heading for a screen reader. */
@Composable
internal fun PbHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** A button with words, at least 48 dp tall; [primary] is the one white button of a page. */
@Composable
internal fun PbButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: ImageVector? = null,
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
        ) { ButtonContent(label, icon) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).defaultMinSize(minWidth = 48.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
            border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
        ) { ButtonContent(label, icon) }
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            // The words say it; the picture is decoration.
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(label)
    }
}

/** A 48 dp icon button that always says what it does. */
@Composable
internal fun PbIconButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint.copy(alpha = 0.4f)),
    ) { Icon(icon, contentDescription = description) }
}

/** A 48 dp text button for a stepper: the sign and size are the words ("-15"), the long description is for a screen reader. */
@Composable
internal fun PbStepButton(text: String, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description }.size(width = 56.dp, height = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RoomStyle.Ink, disabledContentColor = RoomStyle.InkFaint),
        border = BorderStroke(1.dp, if (enabled) RoomStyle.InkFaint else RoomStyle.InkFaint.copy(alpha = 0.3f)),
    ) { Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
}

/** A small hint line under or beside controls. */
@Composable
internal fun PbCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
}
