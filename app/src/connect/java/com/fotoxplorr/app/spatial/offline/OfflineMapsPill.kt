package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.RoomStyle

/**
 * "Save this area offline", a small pill over the embedded map. Shown only when [view] is an area the
 * download caps allow (see [OfflineMapsPaneModel.offerableView]), so on a country-sized view it is
 * simply absent. A tap opens the Offline maps sheet with the view as it was at the tap; it starts no
 * download itself, and the camera moving while the sheet is open does not change what it was given.
 *
 * [placePoints] is read only when the sheet opens, and must come from the browsable photos
 * (TRAPS #33). Not saved across process death: the sheet is closed again.
 */
@Composable
internal fun OfflineMapsPill(view: GeoBox?, placePoints: () -> List<GeoPoint>, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf<GeoBox?>(null) }
    val offered = OfflineMapsPaneModel.offerableView(view) { box -> MapLibreRegionEngine.canDownload(box) }
    if (offered != null) {
        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                // Fixed on purpose (reference design): the map's chrome is a dark pill with white ink.
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, RoomStyle.InkFaint, shape)
                .clickable(role = Role.Button) { opened = offered }
                .semantics { contentDescription = OfflineMapsCopy.PILL }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(OfflineMapsCopy.PILL, color = RoomStyle.Ink, style = RoomStyle.Caption.copy(fontSize = 14.sp))
        }
    }
    opened?.let { box ->
        OfflineMapsDialog(placePoints = placePoints(), currentView = box, onDismiss = { opened = null })
    }
}
