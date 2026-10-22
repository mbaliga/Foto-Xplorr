package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The two doors onto the Offline maps pane, written once for both builds: the Settings > Data row and
 * the full-screen sheet both it and the map's pill open. Both builds compile this file; what is
 * inside the sheet is [OfflineMapsPane], which each build supplies under one name.
 */

/**
 * Full-screen sheet around [OfflineMapsPane]. Finds the app's one `JobRunner` through the view-model
 * store (as the backup rows do) so a download started here outlives the sheet; with none to be
 * found it shows [OfflineMapsCopy.UNAVAILABLE] instead of a pane that could not start anything.
 *
 * Opening the sheet starts nothing. Back, the pane's own back control and a tap outside all close it.
 * [placePoints] must come from `browsableAssets` (TRAPS #33).
 */
@Composable
fun OfflineMapsDialog(placePoints: List<GeoPoint>, currentView: GeoBox?, onDismiss: () -> Unit) {
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (runner == null) {
            PaneScaffold(title = OfflineMapsCopy.ROW_LABEL, onBack = onDismiss) {
                Text(
                    OfflineMapsCopy.UNAVAILABLE,
                    color = RoomStyle.InkMuted,
                    style = RoomStyle.Caption,
                    modifier = Modifier.padding(horizontal = RoomStyle.GutterStart),
                )
            }
        } else {
            OfflineMapsPane(
                placePoints = placePoints,
                currentView = currentView,
                jobRunner = runner,
                onClose = onDismiss,
            )
        }
    }
}

/**
 * The one Settings > Data row. In the Connect build it opens the sheet; in the Offline build the
 * caption already says the sheet only explains that this build has no way to download.
 *
 * "Places in your photos" is read here, only when the sheet is open, from the browsable photos the
 * Experimental section and the map already use (`LocalSpatialExperience.assets` is the output of
 * `browsableAssets`, TRAPS #33); with none provided there are no places. Whether the sheet is open is
 * saved across a rotation and process death; opening it starts no download.
 */
@Composable
fun OfflineMapsSettingsRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    RoomRow(
        label = OfflineMapsCopy.ROW_LABEL,
        caption = if (BuildConfig.NETWORK_FEATURES) OfflineMapsCopy.ROW_CAPTION else OfflineMapsCopy.STUB_ROW,
        onClick = { open = true },
    )
    if (open) {
        val spatial = LocalSpatialExperience.current
        val points = remember(spatial?.assets, spatial?.geoState) {
            spatial?.let { it.geoState.browsablePoints(it.assets.map { asset -> asset.id }) }.orEmpty()
        }
        OfflineMapsDialog(placePoints = points, currentView = null, onDismiss = { open = false })
    }
}
