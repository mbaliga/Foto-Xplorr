package com.fotoxplorr.app.sources.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.previews.PreviewPassDriver
import com.fotoxplorr.app.sources.previews.SourcePreviews
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** How long the list must stay still before the pass starts, so an index writing slices does not restart it every moment. */
internal const val PREVIEW_PASS_DEBOUNCE_MS = 2_000L

/**
 * Keeps the offline previews of sources that asked for it filled while they are ONLINE: the bounded
 * background pass of U5, run off the main thread each time the list settles. Draws nothing.
 *
 * - With no sources ([hasSources] false) the effect does nothing at all: the previews service is not
 *   even built and no file or database is opened.
 * - The pass only ever receives [browsable] applied to [assets] (TRAPS #33), never the merged
 *   catalogue itself, so a photo in a locked folder, archived or sensitive-hidden gets no preview.
 * - [browsable] is read at call time, on the background thread, from the stores' own StateFlows.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
fun SourcePreviewPassHook(assets: List<MediaAsset>, hasSources: Boolean, browsable: (List<MediaAsset>) -> List<MediaAsset>) {
    val context = LocalContext.current
    val latestAssets = rememberUpdatedState(assets)
    val latestBrowsable = rememberUpdatedState(browsable)
    LaunchedEffect(hasSources) {
        if (!hasSources) return@LaunchedEffect
        val previews = withContext(Dispatchers.IO) { SourcePreviews.get(context) }
        val online = SourcesRuntime.get(context).statuses().map { PreviewPassDriver.onlineRows(it) }.distinctUntilChanged()
        combine(snapshotFlow { latestAssets.value }, online) { list, rows -> list to rows }
            .debounce(PREVIEW_PASS_DEBOUNCE_MS)
            .collectLatest { (list, rows) ->
                withContext(Dispatchers.Default) {
                    PreviewPassDriver.drive(list, rows, { latestBrowsable.value(it) }, { b, o, limit -> previews.pass.run(b, o, limit) })
                }
            }
    }
}
