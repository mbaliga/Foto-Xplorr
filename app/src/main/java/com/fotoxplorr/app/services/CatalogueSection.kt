package com.fotoxplorr.app.services

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ai.RemoteAiBridge
import com.fotoxplorr.app.hyle.HyleToggle
import com.fotoxplorr.app.services.catalogue.CataloguePolicy
import com.fotoxplorr.app.services.catalogue.NoozModel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The shared (Nooz) catalogue under the built-in models: where the list came from and how old it
 * is, an explicit "Update catalogue" tap (Connect build only), and the models Fotoz lists but
 * cannot run yet, collapsed, with Get disabled. Opening this does no network access.
 */
@Composable
internal fun CatalogueSection(store: CatalogueStore, bridge: RemoteAiBridge) {
    var snapshot by remember(store) { mutableStateOf(store.load()) }
    var showAll by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val models = snapshot.models.models
    val shown = CataloguePolicy.visible(models, showAll)
    val hidden = CataloguePolicy.hiddenCount(models, showAll)

    ServicesCardBox {
        Text(ServicesCopy.CATALOGUE_TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(CataloguePolicy.snapshotAgeText(snapshot.models.lastUpdated, LocalDate.now()), color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
        Text(
            when {
                !bridge.available -> ServicesCopy.CATALOGUE_OFFLINE
                snapshot.fromCache -> ServicesCopy.CATALOGUE_CACHED
                else -> ServicesCopy.CATALOGUE_BUNDLED
            },
            color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall,
        )
        if (bridge.available) {
            ServicesButton(
                label = if (updating) ServicesCopy.UPDATING else ServicesCopy.UPDATE_CATALOGUE,
                icon = Icons.Outlined.Refresh,
                enabled = !updating,
                onClick = {
                    updating = true
                    message = null
                    scope.launch {
                        store.update(bridge).fold(
                            onSuccess = { snapshot = it; message = ServicesCopy.UPDATED },
                            onFailure = { message = ServicesCopy.UPDATE_FAILED },
                        )
                        updating = false
                    }
                },
            )
        }
        message?.let { Text(it, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text(ServicesCopy.SHOW_ALL, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyMedium)
                Text(if (hidden > 0) ServicesCopy.hiddenNote(hidden) else ServicesCopy.SHOW_ALL_NOTE, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
            }
            HyleToggle(checked = showAll, onCheckedChange = { showAll = it }, description = ServicesCopy.SHOW_ALL)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded },
        ) {
            Text(ServicesCopy.MORE_GROUP, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", tint = SERVICES_MUTED)
        }
        if (expanded) {
            if (shown.isEmpty()) Text(ServicesCopy.NO_MORE_MODELS, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
            shown.forEach { CatalogueModelRow(it) }
        }
    }
}

/** One catalogue model. Get is always disabled today: Fotoz has no runtime for these kinds. */
@Composable
internal fun CatalogueModelRow(model: NoozModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.name, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(model.params, model.quant, CataloguePolicy.sizeText(model.sizeBytes)).joinToString(" · "),
            color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall,
        )
        Text(CataloguePolicy.provenanceText(model), color = SERVICES_MUTED, style = MaterialTheme.typography.labelSmall)
        Text(
            if (model.downloadUrl == null) ServicesCopy.NO_VERIFIED_DOWNLOAD else ServicesCopy.NOT_USED_YET,
            color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall,
        )
        ServicesButton(
            label = ServicesCopy.GET,
            icon = Icons.Outlined.Download,
            enabled = CataloguePolicy.canOfferDownload(model),
            onClick = {},
        )
    }
}
