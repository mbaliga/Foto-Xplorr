package com.fotoxplorr.app.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Fixed on purpose (semantic): the same hand-painted black rooms the rest of Settings uses.
internal val SERVICES_BACKGROUND = Color.Black
internal val SERVICES_CARD = Color(0xFF141414)
internal val SERVICES_PRIMARY = Color.White
internal val SERVICES_SECONDARY = Color.White.copy(alpha = 0.65f)
internal val SERVICES_MUTED = Color.White.copy(alpha = 0.45f)
internal val SERVICES_DANGER = Color(0xFFE5564B)

/** The pane header: a back arrow (48 dp), a title and one sentence. */
@Composable
internal fun ServicesHeader(title: String, blurb: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = SERVICES_PRIMARY)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleLarge)
            Text(blurb, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ServicesCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().background(SERVICES_CARD, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A pill button with a 48 dp target. The label always says what it does; colour is never the only cue. */
@Composable
internal fun ServicesButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val tint = when {
        danger -> SERVICES_DANGER
        filled -> MaterialTheme.colorScheme.primary
        else -> SERVICES_PRIMARY
    }
    val alpha = if (enabled) 1f else 0.35f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(
                if (filled) tint.copy(alpha = 0.16f * alpha) else Color.White.copy(alpha = 0.06f * alpha),
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(18.dp)) }
        Text(label, color = tint.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
