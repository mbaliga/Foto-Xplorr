package com.fotoxplorr.app.sources.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.hyle.HyleToggle
import com.fotoxplorr.app.services.SERVICES_BACKGROUND
import com.fotoxplorr.app.services.SERVICES_DANGER
import com.fotoxplorr.app.services.SERVICES_MUTED
import com.fotoxplorr.app.services.SERVICES_PRIMARY
import com.fotoxplorr.app.services.SERVICES_SECONDARY
import com.fotoxplorr.app.services.ServicesButton
import com.fotoxplorr.app.services.ServicesCardBox
import com.fotoxplorr.app.services.ServicesHeader
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.sources.drives.DriveRuntime
import com.fotoxplorr.app.sources.runtime.AddResult
import com.fotoxplorr.app.sources.runtime.SourceJobs
import com.fotoxplorr.app.sources.previews.PreviewsCardSection
import com.fotoxplorr.app.sources.previews.SourcePreviews
import com.fotoxplorr.app.sources.previews.StorePreviewCost
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.core.index.sources.SourceState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Sources screen can be asked to do. The screen itself holds no runtime. */
class SourcesActions(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onRescan: (row: Long) -> Unit,
    val onStop: (row: Long) -> Unit,
    val onInclude: (row: Long, included: Boolean) -> Unit,
    val onIndexOnConnect: (row: Long, on: Boolean) -> Unit,
    val onRemove: (row: Long) -> Unit,
    val onDismissMessage: () -> Unit,
)

/**
 * Settings > Library > Folders and drives. Stateless: the cards and one message in, the actions out,
 * so a Compose test can draw every state. Every control is a labelled 48 dp target (no drag, no
 * gesture-only path); a state is its word and glyph together, never a colour alone.
 */
@Composable
fun SourcesScreen(
    cards: List<SourceCard>,
    message: String?,
    actions: SourcesActions,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    BackHandler(onBack = actions.onBack)
    var confirmRemove by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxSize().background(SERVICES_BACKGROUND)) {
        ServicesHeader(SourcesCopy.TITLE, SourcesCopy.INTRO, actions.onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (message != null) MessageBar(message, actions.onDismissMessage)
            ServicesButton(
                label = SourcesCopy.ADD,
                onClick = actions.onAdd,
                filled = true,
                icon = Icons.Outlined.CreateNewFolder,
                modifier = Modifier.semantics { contentDescription = SourcesCopy.ADD },
            )
            if (cards.isEmpty()) EmptyState() else cards.forEach { card ->
                SourceCardView(card, actions, onAskRemove = { confirmRemove = card.row })
            }
            footer()
        }
    }
    val asking = confirmRemove?.let { row -> cards.firstOrNull { it.row == row } }
    if (asking != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(SourcesCopy.REMOVE_TITLE) },
            text = { Text("${asking.name}. ${SourcesCopy.REMOVE_BODY}") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemove = null; actions.onRemove(asking.row) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(SourcesCopy.REMOVE_CONFIRM, color = SERVICES_DANGER) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(SourcesCopy.CANCEL) }
            },
        )
    } else if (confirmRemove != null) {
        // The card vanished (removed elsewhere) while the question was open.
        LaunchedEffect(confirmRemove) { confirmRemove = null }
    }
}

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(SourcesCopy.DISMISS) }
    }
}

@Composable
private fun EmptyState() {
    ServicesCardBox {
        Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(SourcesCopy.EMPTY_TITLE, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(SourcesCopy.EMPTY_BODY, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SourceCardView(card: SourceCard, actions: SourcesActions, onAskRemove: () -> Unit) {
    ServicesCardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                card.name,
                color = SERVICES_PRIMARY,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            StateChip(card)
        }
        Text("${card.filesLabel}, ${card.bytesLabel}", color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodyMedium)
        Text(card.lastRead, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        card.stateNote?.let { Text(it, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall) }
        card.problems.forEach { Text(it, color = SERVICES_DANGER, style = MaterialTheme.typography.bodySmall) }
        if (card.sizesPending) Text(SourcesCopy.SIZES_PENDING, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        if (card.busy) Progress(card)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.busy) {
                ServicesButton(
                    SourcesCopy.STOP, { actions.onStop(card.row) }, icon = Icons.Outlined.StopCircle,
                    modifier = Modifier.semantics { contentDescription = SourcesCopy.stopA11y(card.name) },
                )
            } else {
                ServicesButton(
                    SourcesCopy.RESCAN, { actions.onRescan(card.row) }, icon = Icons.Outlined.Refresh, enabled = card.canRescan,
                    modifier = Modifier.semantics { contentDescription = SourcesCopy.rescanA11y(card.name) },
                )
            }
            ServicesButton(
                SourcesCopy.REMOVE, onAskRemove, danger = true, icon = Icons.Outlined.Delete,
                modifier = Modifier.semantics { contentDescription = SourcesCopy.removeA11y(card.name) },
            )
        }
        SwitchLine(SourcesCopy.INCLUDE, SourcesCopy.INCLUDE_HELP, Icons.Outlined.Visibility, card.included) { actions.onInclude(card.row, it) }
        SwitchLine(SourcesCopy.ON_CONNECT, SourcesCopy.ON_CONNECT_HELP, Icons.Outlined.Schedule, card.indexOnConnect) { actions.onIndexOnConnect(card.row, it) }
        PreviewsCardSection(card.row, card.name)
    }
}

/** The state as a glyph and its word, so it reads without colour. */
@Composable
private fun StateChip(card: SourceCard) {
    val icon = when (card.state) {
        SourceState.ONLINE -> Icons.Outlined.CheckCircle
        SourceState.OFFLINE -> Icons.Outlined.CloudOff
        SourceState.REVOKED -> Icons.Outlined.Block
        SourceState.ERROR -> Icons.Outlined.Error
    }
    val tint = if (card.state == SourceState.ONLINE) MaterialTheme.colorScheme.primary else SERVICES_PRIMARY
    Row(
        Modifier.heightIn(min = 32.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${SourcesCopy.STATE_PREFIX}: ${card.stateLabel}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(card.stateLabel, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(card: SourceCard) {
    val f = card.progressFraction
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.progressText?.let { Text(it, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall) }
        val bar = Modifier.fillMaxWidth().semantics {
            contentDescription = card.progressText ?: SourcesCopy.READING
            if (f != null) progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
        }
        if (f == null) LinearProgressIndicator(modifier = bar) else LinearProgressIndicator(progress = { f }, modifier = bar)
    }
}

/** A labelled switch: the whole row is the one control, as in the Settings rows. */
@Composable
internal fun SwitchLine(
    label: String,
    help: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp).size(24.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = SERVICES_PRIMARY, style = MaterialTheme.typography.bodyLarge)
            Text(help, color = SERVICES_MUTED, style = MaterialTheme.typography.bodySmall)
        }
        HyleToggle(checked = checked, onCheckedChange = null)
    }
}

/**
 * The live screen: the runtime's list, the system folder picker, and the index jobs on the app's
 * [JobRunner]. Opening it reads nothing from a folder; the database is opened only if a source exists.
 */
@Composable
fun SourcesHost(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = remember(context) { SourcesRuntime.get(context) }
    val owner = LocalViewModelStoreOwner.current
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    val scope = rememberCoroutineScope()
    val statuses by remember(runtime) { runtime.statuses() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cards = remember(statuses) { SourcesUiModel.cards(statuses, ::formatTime) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(runtime) { runtime.refreshStates() }

    fun startIndex(row: Long, name: String) {
        if (runner != null) SourceJobs.launch(runner, runtime, row, name)
        else scope.launch { runtime.index(row) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { folderName(context, uri) }
            val result = runtime.addSource(uri.toString(), name)
            message = SourcesCopy.forResult(result)
            if (result is AddResult.Added) startIndex(result.sourceRow, name ?: SourcesCopy.TITLE)
        }
    }

    val actions = remember(runtime, runner) {
        SourcesActions(
            onBack = onBack,
            onAdd = { picker.launch(null) },
            onRescan = { row -> scope.launch { startIndex(row, runtime.sourceName(row) ?: SourcesCopy.TITLE) } },
            onStop = { row -> runtime.stopIndex(row) },
            onInclude = { row, on -> runtime.setIncluded(row, on) },
            onIndexOnConnect = { row, on -> runtime.setIndexOnConnect(row, on) },
            onRemove = { row -> scope.launch { runtime.removeSource(row) } },
            onDismissMessage = { message = null },
        )
    }
    val drives = remember(context) { DriveRuntime.controller(context) }
    val ignored by remember(drives) { drives.ignored() }.collectAsStateWithLifecycle(initialValue = emptyList())
    // What the previews really cost: the store's exact sums, read for each source once so the panel
    // knows them before the person opens a card (a source's folder is listed on the IO dispatcher).
    val previews = remember(context) { SourcePreviews.get(context) }
    val previewSizes by previews.store.sizes.collectAsStateWithLifecycle()
    val rows = statuses.map { it.info.row }
    LaunchedEffect(rows, previews) { rows.forEach { previews.store.sizeBytes(it) } }
    val panel = remember(statuses, ignored, previewSizes) {
        StoragePanelModel.build(statuses, ::formatTime, previewCost = StorePreviewCost { previewSizes }, ignored = ignored)
    }
    SourcesScreen(cards, message, actions, modifier, footer = { StoragePanel(panel, onAskAgain = drives::askAgain) })
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** The picked folder's own name from its provider, or null (the runtime then names it from its path). */
private fun folderName(context: Context, tree: Uri): String? = runCatching {
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null
    }
}.getOrNull()
