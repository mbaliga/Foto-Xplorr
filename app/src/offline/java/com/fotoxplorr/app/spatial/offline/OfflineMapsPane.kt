package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The Offline build's Offline maps pane: an honest statement and nothing else. This build has no
 * network code at all, so there is nothing to download with, and the Offline map mode already draws
 * a stamp map that needs no tiles.
 *
 * Same fully qualified name and signature as `src/connect`'s pane, which is what lets Settings call
 * it without knowing which build it got. The inputs are unused here. The only controls are the
 * scaffold's own Back, which calls [onClose].
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
    PaneScaffold(title = OfflineMapsCopy.ROW_LABEL, onBack = onClose, modifier = modifier) {
        Text(
            text = OfflineMapsCopy.STUB_ROW,
            color = RoomStyle.InkMuted,
            style = RoomStyle.Row,
            modifier = Modifier.padding(horizontal = RoomStyle.GutterStart, vertical = 12.dp),
        )
    }
}
