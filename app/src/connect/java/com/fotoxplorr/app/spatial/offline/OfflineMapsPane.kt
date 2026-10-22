package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.jobs.JobRunner

/**
 * The Connect build's Offline maps pane: save a city-sized map area for use without a connection.
 *
 * Same fully qualified name and signature as `src/offline`'s stub, so Settings can call it without
 * knowing which build it got. This unit declares the contract only; the body is the offline-maps UI
 * unit's, built on [MapLibreRegionEngine.shared], [OfflineDownloadJob], [OfflineRegionReconcile],
 * [RegionSuggestions] and [DownloadGuard].
 *
 * Inputs
 * - [placePoints]: where the person's own photos were taken, already reduced to the photos the app
 *   may show (from `browsableAssets` through `GeoIndexState.browsablePoints`, TRAPS #33), never read
 *   here from the whole library. Feeds the "Places in your photos" suggestions.
 * - [currentView]: the area the Places map shows right now, or null when no map is open. The
 *   "This view" choice is offered only when it is not null.
 * - [jobRunner]: the app's one runner (`AppStateViewModel.jobRunner`), so a download outlives the pane.
 * - [onClose]: the pane asked to close (its own back or close control).
 *
 * Outputs: none besides [onClose]. Everything else is a side effect of an explicit tap on Download
 * or Resume; opening the pane, scrolling it or changing [currentView] never starts one.
 *
 * Process death: a saved area and what it fetched persist in the map library's database; the pane
 * lists them on open as paused, tap resume. A running download, the chosen area and any unsent name
 * are not saved.
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
    // The body belongs to the offline-maps UI unit.
    Box(modifier)
}
