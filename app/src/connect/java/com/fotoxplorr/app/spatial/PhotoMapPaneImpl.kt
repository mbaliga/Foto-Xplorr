package com.fotoxplorr.app.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.offline.everydayPoints

/**
 * The connect flavour's map pane: the street map, embedded -- but only once the browser has been
 * at rest with every room closed.
 *
 * The street map draws its style and tiles over the network and starts the moment its view exists.
 * The rail commits the word under the finger after a short rest while the finger is still on the
 * wheel (TRAPS #61), so a scrub that rests on Map must not make a request; until [settled] the
 * pane is a plain dark field. Once it has been settled the map stays: the swap is one way, so
 * opening a room over the map never tears the view down and rebuilds it (a rebuild is a second
 * style fetch).
 *
 * [frameAssets] does not frame this map: it fits its camera once, to what it is first handed, and
 * is then moved by hand, so narrowing [assets] does not move it. It is used for the places the
 * "Save this area offline" sheet suggests. Same fully-qualified signature as `src/offline`'s
 * implementation.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun PhotoMapPaneImpl(
    assets: List<MediaAsset>,
    frameAssets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    settled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var reachedRest by remember { mutableStateOf(false) }
    LaunchedEffect(settled) { if (settled) reachedRest = true }
    if (!reachedRest) {
        // Fixed on purpose (reference design): the same near-black the stamp map's field is.
        Box(modifier.fillMaxSize().background(Color(0xFF11141C)))
        return
    }
    RichPhotoMapScreen(
        assets = assets,
        geoState = geoState,
        onIndexLocations = onIndexLocations,
        onOpenAsset = onOpenAsset,
        onClose = {},
        modifier = modifier,
        embedded = true,
        // frameAssets is the browser's privacy-filtered list (TRAPS #33), read only when the Offline
        // maps sheet opens from the pill; photos in any protected folder, unlocked now or not, are
        // left out because a saved area outlives the unlock.
        offlinePlacePoints = { geoState.everydayPoints(context, frameAssets) },
    )
}
