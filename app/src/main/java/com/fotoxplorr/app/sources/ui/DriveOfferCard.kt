package com.fotoxplorr.app.sources.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.services.SERVICES_CARD
import com.fotoxplorr.app.services.SERVICES_MUTED
import com.fotoxplorr.app.services.SERVICES_PRIMARY
import com.fotoxplorr.app.services.SERVICES_SECONDARY
import com.fotoxplorr.app.services.ServicesButton
import com.fotoxplorr.app.sources.drives.DriveCopy
import com.fotoxplorr.app.sources.drives.DriveVolume

/** What the three buttons of the drive card do. */
class DriveOfferActions(
    val onAdd: () -> Unit,
    val onNotNow: () -> Unit,
    val onNever: () -> Unit,
)

/**
 * "Drive connected. Add it to your library?" A dismissible strip: Not now closes it, and every
 * answer is a labelled 48 dp button (no swipe, no drag). It is announced politely when it appears.
 * Stateless, so a Compose test can draw it; it starts no scan and asks for no permission by itself.
 */
@Composable
fun DriveOfferCard(volume: DriveVolume, actions: DriveOfferActions, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth()
            .background(SERVICES_CARD, RoundedCornerShape(14.dp))
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = DriveCopy.offerA11y(volume.label) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(DriveCopy.OFFER_TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(volume.label, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(DriveCopy.OFFER_BODY, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyLarge)
        Text(DriveCopy.NOTE_READ_ONLY, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            ServicesButton(
                DriveCopy.ADD, actions.onAdd, filled = true, icon = Icons.Outlined.CreateNewFolder,
                modifier = Modifier.semantics { contentDescription = DriveCopy.addA11y(volume.label) },
            )
            ServicesButton(
                DriveCopy.NOT_NOW, actions.onNotNow,
                modifier = Modifier.semantics { contentDescription = DriveCopy.notNowA11y(volume.label) },
            )
        }
        ServicesButton(
            DriveCopy.NEVER, actions.onNever,
            modifier = Modifier.semantics { contentDescription = DriveCopy.neverA11y(volume.label) },
        )
    }
}
