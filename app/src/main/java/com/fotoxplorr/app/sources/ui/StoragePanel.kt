package com.fotoxplorr.app.sources.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.services.SERVICES_DANGER
import com.fotoxplorr.app.services.SERVICES_MUTED
import com.fotoxplorr.app.services.SERVICES_PRIMARY
import com.fotoxplorr.app.services.SERVICES_SECONDARY
import com.fotoxplorr.app.services.ServicesButton
import com.fotoxplorr.app.services.ServicesCardBox

/**
 * The storage section of the Sources pane (WP3.11): per source online or offline, files, bytes,
 * what its previews cost, when it was last read, what went wrong, and where else it is kept.
 * Stateless; every state is a word as well as a glyph.
 */
@Composable
fun StoragePanel(state: StoragePanelState, onAskAgain: (String) -> Unit, modifier: Modifier = Modifier) {
    ServicesCardBox(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(
                StorageCopy.TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() },
            )
        }
        if (state.rows.isEmpty()) {
            Text(StorageCopy.EMPTY, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("${state.summary} ${state.totalBytesLabel}.", color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
            state.rows.forEach { StorageRowView(it) }
            state.protectionPlaceholder?.let { Text(it, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall) }
        }
        if (state.ignored.isNotEmpty()) {
            Text(StorageCopy.IGNORED_TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyLarge)
            state.ignored.forEach { drive ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(drive.label, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ServicesButton(
                        StorageCopy.ASK_AGAIN, { onAskAgain(drive.uuid) },
                        modifier = Modifier.semantics { contentDescription = StorageCopy.askAgainA11y(drive.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageRowView(row: StorageRow) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (row.online) Icons.Outlined.CheckCircle else if (row.stateLabel == SourcesCopy.STATE_REVOKED) Icons.Outlined.Block
                else if (row.stateLabel == SourcesCopy.STATE_ERROR) Icons.Outlined.Error else Icons.Outlined.CloudOff,
                contentDescription = null, tint = SERVICES_PRIMARY, modifier = Modifier.size(18.dp),
            )
            Text(row.name, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(row.stateLabel, color = SERVICES_PRIMARY, style = MaterialTheme.typography.labelLarge)
        }
        Text("${row.filesLabel}, ${row.bytesLabel}", color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
        Text(row.previewsLabel ?: StorageCopy.PREVIEWS_NONE, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall)
        Text(row.lastRead, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        row.protectedLabel?.let { Text(it, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall) }
        row.problems.forEach { Text(it, color = SERVICES_DANGER, style = MaterialTheme.typography.bodySmall) }
    }
}
