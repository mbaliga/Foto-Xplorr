package com.fotoxplorr.app.services

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.ai.AppConnectivityBindings
import com.fotoxplorr.app.ai.LocalModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * "Models on this phone": every on-device model the app supports, a Get button each (Connect
 * build) or Import file (Offline build), the space they use and a delete-all.
 *
 * A download starts only from a tap on Get; opening this pane starts nothing (TRAPS #61/#71).
 * Over a metered network the tap first asks, with the size. The checksum is NOT verified against a
 * published value because the app has none to compare with: [LocalModelManager] checks the file is
 * a TensorFlow Lite model of plausible size and records its SHA-256, which is what the row shows.
 */
@Composable
fun ModelsPane(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bindings = remember { AppConnectivityBindings() }
    val manager = remember(context) { LocalModelManager(context.applicationContext, bindings.remoteAi) }
    val state by manager.observe().collectAsStateWithLifecycle()
    val canDownload = bindings.remoteAi.available
    val entries = remember(state, canDownload) { ModelCatalogue.entries(state, canDownload) }
    val catalogueStore = remember(context) { CatalogueStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var confirm by remember { mutableStateOf<ModelSpec?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { manager.installFromUri(uri) }
    }
    fun start() {
        job = scope.launch { manager.installRecommendedModel() }
    }

    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(SERVICES_BACKGROUND)) {
        ServicesHeader(ServicesCopy.MODELS_TITLE, ServicesCopy.MODELS_PRIVACY, onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(ServicesCopy.BUILT_IN_GROUP, color = SERVICES_SECONDARY, style = MaterialTheme.typography.labelLarge)
            }
            items(entries, key = { it.spec.id }) { entry ->
                ModelRow(
                    entry = entry,
                    onGet = {
                        when (ModelCatalogue.downloadDecision(entry.canDownload, MeteredNetwork.isMetered(context))) {
                            DownloadDecision.START -> start()
                            DownloadDecision.CONFIRM_METERED -> confirm = entry.spec
                            DownloadDecision.UNAVAILABLE -> Unit
                        }
                    },
                    onCancel = { job?.cancel() },
                    onDelete = manager::deleteModel,
                    onImport = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                )
            }
            item { CatalogueSection(store = catalogueStore, bridge = bindings.remoteAi) }
            item {
                StorageFooter(
                    line = ModelCatalogue.storageLine(entries),
                    canDeleteAll = ModelCatalogue.storageUsedBytes(entries) > 0L,
                    onDeleteAll = manager::deleteModel,
                )
            }
        }
    }

    confirm?.let { spec ->
        Dialog(onDismissRequest = { confirm = null }) {
            Column(
                Modifier.background(SERVICES_CARD, RoundedCornerShape(20.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ServicesCopy.CONFIRM_TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(ServicesCopy.confirmBody(ModelCatalogue.formatSize(spec.approxBytes)), color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                    ServicesButton(ServicesCopy.CANCEL, onClick = { confirm = null })
                    ServicesButton(ServicesCopy.CONFIRM_GO, filled = true, onClick = { confirm = null; start() })
                }
            }
        }
    }
}

@Composable
internal fun StorageFooter(line: String, canDeleteAll: Boolean, onDeleteAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(line, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (canDeleteAll) {
            ServicesButton(ServicesCopy.DELETE_ALL, danger = true, icon = Icons.Outlined.Delete, onClick = onDeleteAll)
        }
    }
}

/** One model: what it enables, its size and licence, its state and the buttons that fit that state. */
@Composable
internal fun ModelRow(
    entry: ModelEntry,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
) {
    val spec = entry.spec
    ServicesCardBox {
        Text(spec.title, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(spec.enables, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
        Text(
            ServicesCopy.downloadSize(ModelCatalogue.formatSize(spec.approxBytes)) + " " +
                ServicesCopy.licenceLine(spec.licence, spec.host),
            color = SERVICES_MUTED,
            style = MaterialTheme.typography.bodySmall,
        )
        when (val state = entry.state) {
            ModelEntryState.NotInstalled -> {
                Text(ServicesCopy.STATE_NOT_INSTALLED, color = SERVICES_PRIMARY, style = MaterialTheme.typography.labelLarge)
                GetButtons(entry, ServicesCopy.GET, onGet, onImport)
                if (!entry.canDownload) Text(ServicesCopy.OFFLINE_NOTE, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
            }
            is ModelEntryState.Downloading -> {
                val percent = ((state.progress ?: 0f) * 100).toInt()
                LinearProgressIndicator(
                    progress = { state.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = ServicesCopy.progress(percent) },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
                Text(ServicesCopy.progress(percent), color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
                ServicesButton(ServicesCopy.CANCEL, onClick = onCancel, modifier = Modifier.semantics { contentDescription = ServicesCopy.cancelDescription(spec.title) })
            }
            is ModelEntryState.Installed -> {
                Text(
                    "${ServicesCopy.STATE_INSTALLED}, ${ModelCatalogue.formatSize(state.sizeBytes)}",
                    color = SERVICES_PRIMARY, style = MaterialTheme.typography.labelLarge,
                )
                Text("Checksum ${state.sha256.take(12)}…", color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
                DeleteButton(spec, onDelete)
            }
            is ModelEntryState.Update -> {
                Text(
                    "${ServicesCopy.STATE_UPDATE}, ${ModelCatalogue.formatSize(state.sizeBytes)} installed",
                    color = SERVICES_PRIMARY, style = MaterialTheme.typography.labelLarge,
                )
                GetButtons(entry, ServicesCopy.REPLACE, onGet, onImport)
                DeleteButton(spec, onDelete)
            }
            is ModelEntryState.Failed -> {
                Text("${ServicesCopy.STATE_FAILED}. ${state.message}", color = SERVICES_DANGER, style = MaterialTheme.typography.bodySmall)
                GetButtons(entry, ServicesCopy.TRY_AGAIN, onGet, onImport)
            }
        }
    }
}

@Composable
private fun GetButtons(entry: ModelEntry, getLabel: String, onGet: () -> Unit, onImport: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entry.canDownload) {
            ServicesButton(getLabel, filled = true, icon = Icons.Outlined.Download, onClick = onGet)
        }
        if (entry.canImport) {
            ServicesButton(ServicesCopy.IMPORT_FILE, icon = Icons.Outlined.UploadFile, onClick = onImport)
        }
    }
}

@Composable
private fun DeleteButton(spec: ModelSpec, onDelete: () -> Unit) {
    ServicesButton(
        ServicesCopy.DELETE,
        danger = true,
        icon = Icons.Outlined.Delete,
        onClick = onDelete,
        modifier = Modifier.semantics { contentDescription = ServicesCopy.deleteDescription(spec.title) },
    )
}
