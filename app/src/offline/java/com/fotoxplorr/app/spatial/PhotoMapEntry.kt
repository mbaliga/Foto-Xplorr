package com.fotoxplorr.app.spatial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset

/**
 * The offline flavor's photo map: an honest absence.
 *
 * The street map draws its style and tiles from OpenFreeMap and its hillshade from AWS
 * over the network on every pan — there is nothing local to show. This build's whole
 * identity is that it performs no network I/O, so the map is left out and *says so*,
 * rather than rendering a blank grid of failed tile requests. The rest of Places —
 * the compass exploration and the coordinate/elevation views — is fully local and
 * untouched. (ADR-006 records the owner's options for a real offline map.)
 */
@Composable
fun PhotoMapExperience(
    assets: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(42.dp))
        Text("The street map lives in the Connect build", style = MaterialTheme.typography.titleLarge)
        Text(
            "Map tiles are fetched from OpenFreeMap while you pan, and this offline build " +
                "performs no network requests at all. Your ${geoState.locatedCount} located " +
                "items are still here — the compass and elevation views work fully offline.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onClose) { Text("Back to Places") }
    }
}
