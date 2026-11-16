package com.fotoxplorr.app.sources.previews

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.services.SERVICES_MUTED
import com.fotoxplorr.app.services.SERVICES_PRIMARY
import com.fotoxplorr.app.services.SERVICES_SECONDARY
import com.fotoxplorr.app.services.ServicesButton
import com.fotoxplorr.app.sources.ui.SwitchLine

/**
 * The per-source previews lines of the Sources screen: "Previews: N MB" (the exact sum of the
 * stored files), the "Keep previews" switch, the space choice (Off, 250 MB, 1 GB, 2 GB) and a
 * "Clear previews" button that deletes only Fotoz's own preview files, never a photo.
 */
@Composable
fun PreviewsCardSection(sourceRow: Long, sourceName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previews = LocalSourcePreviews.current ?: remember(context) { SourcePreviews.get(context) }
    // The first read of a source's folder is a listing on the IO dispatcher; the figure appears when it is done.
    val sizes by previews.store.sizes.collectAsStateWithLifecycle()
    LaunchedEffect(sourceRow, previews) { previews.store.sizeBytes(sourceRow) }
    // Read again whenever a setting changes, here or from the drive lifecycle.
    val version by previews.settings.changes().collectAsStateWithLifecycle()
    val controls = remember(sourceRow, version) { PreviewControls.of(previews.settings.keep(sourceRow), previews.settings.capBytes(sourceRow)) }
    val bytes = sizes[sourceRow] ?: 0L
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(PreviewsCopy.size(bytes), color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ServicesButton(
                PreviewsCopy.CLEAR, { previews.clearAsync(sourceRow) }, icon = Icons.Outlined.DeleteSweep, enabled = bytes > 0L,
                modifier = Modifier.semantics { contentDescription = PreviewsCopy.clearA11y(sourceName) },
            )
        }
        SwitchLine(
            PreviewsCopy.KEEP, if (controls.keepEnabled) PreviewsCopy.KEEP_HELP else PreviewsCopy.CAP_OFF_NOTE, Icons.Outlined.PhotoLibrary,
            controls.keepShown, enabled = controls.keepEnabled,
        ) { previews.settings.setKeep(sourceRow, it) }
        CapChoice(controls.cap) { previews.setCapAsync(sourceRow, it) }
    }
}

/** The space choice: one selectable chip per cap; the selected one carries a check and the word "selected" for a screen reader, so it is never colour alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CapChoice(selectedCap: Long, onChoose: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(PreviewsCopy.CAP_TITLE, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewCaps.choices.forEach { cap ->
                val on = cap == selectedCap
                val label = PreviewCaps.label(cap)
                val tint = MaterialTheme.colorScheme.primary
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (on) tint.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                    border = BorderStroke(if (on) 2.dp else 1.dp, if (on) tint else SERVICES_MUTED),
                    modifier = Modifier.heightIn(min = 48.dp).selectable(selected = on, role = Role.RadioButton, onClick = { onChoose(cap) })
                        .semantics { contentDescription = PreviewsCopy.capA11y(label, on) },
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Text(label, color = SERVICES_PRIMARY, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
