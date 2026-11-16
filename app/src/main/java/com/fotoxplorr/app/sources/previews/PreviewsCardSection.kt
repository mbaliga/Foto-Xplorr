package com.fotoxplorr.app.sources.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.services.SERVICES_SECONDARY
import com.fotoxplorr.app.services.ServicesButton

/**
 * The per-source line of the Sources screen: "Previews: N MB" (the exact sum of the stored files)
 * and a "Clear previews" button that deletes only Fotoz's own preview files, never a photo.
 */
@Composable
fun PreviewsCardSection(sourceRow: Long, sourceName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previews = LocalSourcePreviews.current ?: remember(context) { SourcePreviews.get(context) }
    // The first read of a source's folder is a listing on the IO dispatcher; the figure appears when it is done.
    val sizes by previews.store.sizes.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sourceRow, previews) { previews.store.sizeBytes(sourceRow) }
    val bytes = sizes[sourceRow] ?: 0L
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(PreviewsCopy.size(bytes), color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ServicesButton(
            PreviewsCopy.CLEAR, { previews.clearAsync(sourceRow) }, icon = Icons.Outlined.DeleteSweep, enabled = bytes > 0L,
            modifier = Modifier.semantics { contentDescription = PreviewsCopy.clearA11y(sourceName) },
        )
    }
}
