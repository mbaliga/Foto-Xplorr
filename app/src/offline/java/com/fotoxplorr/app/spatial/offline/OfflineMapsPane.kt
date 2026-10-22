package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.jobs.JobRunner

/**
 * The Offline build's Offline maps row: an honest statement and nothing else. This build has no
 * network code at all, so there is nothing to download with, and the Offline map mode already draws
 * a stamp map that needs no tiles.
 *
 * Same fully qualified name and signature as `src/connect`'s pane, which is what lets Settings call
 * it without knowing which build it got. The inputs are unused here.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun OfflineMapsPane(
    placePoints: List<GeoPoint>,
    currentView: GeoBox?,
    jobRunner: JobRunner,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(OfflineMapsCopy.STUB_ROW, style = MaterialTheme.typography.bodyMedium)
    }
}
