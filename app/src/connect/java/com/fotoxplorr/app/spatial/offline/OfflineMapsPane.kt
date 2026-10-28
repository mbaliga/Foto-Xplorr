package com.fotoxplorr.app.spatial.offline

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.ui.PaneScaffold
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The Connect build's Offline maps pane: save a city-sized map area for use without a connection.
 *
 * Same fully qualified name and signature as `src/offline`'s stub, so Settings and the map's pill can
 * call it without knowing which build they got.
 *
 * Inputs
 * - [placePoints]: where the person's own photos were taken, already reduced to the photos the app
 *   may show and then to those outside every protected folder (from `browsableAssets` through
 *   `GeoIndexState.everydayPoints`, TRAPS #33), never read here from the whole library. A saved
 *   area outlives an unlock, so an unlocked folder's photos are left out too. Feeds the "Places in
 *   your photos" choices.
 * - [currentView]: the area the Places map shows right now, or null when no map is open. The
 *   "This view" choice is offered only when it is not null.
 * - [jobRunner]: the app's one runner (`AppStateViewModel.jobRunner`), so a download outlives the pane.
 * - [onClose]: the pane asked to close (its own back control).
 *
 * What it does: lists what is saved (nothing else happens on open), offers each area with a Download
 * button that carries the size estimate, says what is shared with the map provider (once, under the
 * first button, and again in the mobile-data question), asks before using mobile data, says so when
 * there is no connection at all, and lets the person pause, resume and delete. A download starts only from a tap on Download, Resume or the mobile-data
 * question's Download (TRAPS #61, #71); see [OfflineMapsController]. Download is disabled, with its
 * reason beside it, when the build cannot apply the provider's request limit, when another download
 * is running, when the area is too big, over the date line or already saved, and when there is no
 * room under the six-area, 300 MB limit. Nothing is ever deleted to make room (TRAPS #64).
 *
 * Accessibility: every control is at least 48 dp tall and wide, every button has a spoken name that
 * includes the area it acts on, a disabled button says why in text next to it, and progress is a
 * percentage in words as well as a bar. Nothing is drawn or dragged: the area comes from the map's
 * own view or from a list, so there is no gesture to replace.
 *
 * Process death: a saved area and what it fetched persist in the map library's database; the pane
 * lists them on open as paused, tap resume. Saved across it (rememberSaveable): the names typed for
 * not-yet-downloaded areas and an open rename. Not saved: a running download (it is the job
 * runner's and ends with the process), the open mobile-data question, a pending delete, any notice.
 *
 * Unverified on a device: all of it. See ADR-016.
 */
@Composable
fun OfflineMapsPane(
    placePoints: List<GeoPoint>,
    currentView: GeoBox?,
    jobRunner: JobRunner,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Throwable on purpose: a map library that cannot load is an Error, not an Exception. The pane
    // then says downloads are unavailable instead of crashing Settings.
    val engine = remember(context) { runCatching { MapLibreRegionEngine.shared(context) }.getOrNull() }
    val network = remember(context) { PlatformConnectionCheck(context) }
    OfflineMapsPaneBody(engine, network, System::currentTimeMillis, placePoints, currentView, jobRunner, onClose, modifier)
}

/** The pane over an injected engine, so a test can mount it with the fake. */
@Composable
internal fun OfflineMapsPaneBody(
    engine: OfflineRegionEngine?,
    network: ConnectionCheck,
    nowMillis: () -> Long,
    placePoints: List<GeoPoint>,
    currentView: GeoBox?,
    jobRunner: JobRunner,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaneScaffold(title = OfflineMapsCopy.ROW_LABEL, onBack = onClose, modifier = modifier) {
        if (engine == null) {
            Note(OfflineMapsCopy.UNAVAILABLE, Modifier.padding(horizontal = RoomStyle.GutterStart))
            return@PaneScaffold
        }
        val scope = rememberCoroutineScope()
        val controller = remember(engine, jobRunner) { OfflineMapsController(engine, jobRunner, network, nowMillis, scope) }
        DisposableEffect(controller) {
            controller.open()
            onDispose { controller.close() }
        }
        val ui by controller.state.collectAsState()
        PaneContent(controller, ui, placePoints, currentView)
    }
}

private val NamesSaver = listSaver<MutableState<Map<String, String>>, String>(
    save = { state -> state.value.flatMap { (key, name) -> listOf(key, name) } },
    restore = { flat -> mutableStateOf(flat.chunked(2).associate { it[0] to it[1] }) },
)

@Composable
private fun ColumnScope.PaneContent(
    controller: OfflineMapsController,
    ui: PaneUiState,
    placePoints: List<GeoPoint>,
    currentView: GeoBox?,
) {
    val options = remember(placePoints, currentView) { OfflineMapsPaneModel.options(placePoints, currentView) }
    var names by rememberSaveable(saver = NamesSaver) { mutableStateOf(emptyMap()) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    val env = PaneEnv(
        throttleInstalled = controller.env().throttleInstalled,
        canDownloadBox = { box -> MapLibreRegionEngine.canDownload(box) },
        saved = ui.regions.map { it.record },
        downloadRunning = ui.downloadRunning,
        covering = OfflineMapsPaneModel.coveringRecords(ui.regions),
    )
    val rows = remember(ui.regions, ui.pauseReasons, ui.failures, ui.live, env.throttleInstalled, ui.downloadRunning) {
        OfflineMapsPaneModel.savedRows(ui.regions, ui.pauseReasons, ui.live, env, failures = ui.failures)
    }
    val view = options.firstOrNull { it.source == AreaSource.THIS_VIEW }
    val places = options.filter { it.source == AreaSource.PLACE }

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = RoomStyle.GutterStart, end = RoomStyle.GutterEnd, bottom = 24.dp),
    ) {
        ui.notice?.let { Note(it) }
        if (ui.listFailed) Note(OfflineMapsCopy.LIST_FAILED)

        // The privacy sentence goes under the first Download button, where the choice is made.
        val firstKey = view?.key ?: places.firstOrNull()?.key
        if (view != null) {
            RoomEyebrow(OfflineMapsCopy.HEADING_THIS_VIEW, Modifier.padding(top = 8.dp))
            AreaCard(
                title = OfflineMapsCopy.THIS_VIEW,
                showPrivacy = view.key == firstKey,
                detail = OfflineMapsPaneModel.nameFor(view, names[view.key]),
                button = OfflineMapsPaneModel.button(view, env),
                onRename = { renaming = view.key; draft = names[view.key].orEmpty() },
                onDownload = { controller.download(view, names[view.key]) },
            )
        }
        if (places.isNotEmpty()) {
            RoomEyebrow(OfflineMapsCopy.HEADING_PLACES, Modifier.padding(top = 8.dp))
            places.forEach { option ->
                val name = OfflineMapsPaneModel.nameFor(option, names[option.key])
                AreaCard(
                    title = name,
                    showPrivacy = option.key == firstKey,
                    detail = OfflineMapsCopy.photos(option.photoCount),
                    button = OfflineMapsPaneModel.button(option, env),
                    onRename = { renaming = option.key; draft = names[option.key].orEmpty() },
                    onDownload = { controller.download(option, names[option.key]) },
                )
            }
        }

        if (ui.loaded) {
            RoomRule(Modifier.padding(vertical = 14.dp))
            RoomEyebrow(OfflineMapsCopy.HEADING_SAVED)
            Note(OfflineMapsPaneModel.quotaLine(ui.regions.map { it.record }))
            if (rows.isEmpty()) {
                Note(if (view == null && places.isEmpty()) OfflineMapsCopy.EMPTY else OfflineMapsCopy.NOTHING_SAVED)
            }
            rows.forEach { row ->
                SavedRowItem(
                    row = row,
                    onResume = { controller.resume(row.id) },
                    onPause = { controller.pause(row.id) },
                    onDelete = { controller.requestDelete(row.id) },
                )
            }
            if (rows.any { it.status == RowStatus.SAVED }) Note(OfflineMapsCopy.NOT_SAVED)
        }

        RoomRule(Modifier.padding(vertical = 14.dp))
        Note(OfflineMapsCopy.NOT_LOCKED)
        Note(OfflineMapsCopy.ATTRIBUTION)
    }

    ui.question?.let { question ->
        AlertDialog(
            onDismissRequest = controller::declineMetered,
            title = { Text(OfflineMapsCopy.METERED) },
            text = {
                Column {
                    Text(OfflineMapsCopy.meteredBody(question.bytes))
                    Spacer(Modifier.height(8.dp))
                    Text(OfflineMapsCopy.PRIVACY)
                }
            },
            confirmButton = { TextButton(onClick = controller::confirmMetered) { Text(OfflineMapsCopy.DOWNLOAD) } },
            dismissButton = { TextButton(onClick = controller::declineMetered) { Text(OfflineMapsCopy.NOT_NOW) } },
        )
    }
    ui.deleting?.let { region ->
        AlertDialog(
            onDismissRequest = controller::cancelDelete,
            title = { Text(OfflineMapsCopy.deleteTitle(region.record.name)) },
            text = { Text(OfflineMapsCopy.deleteBody(region.record.bytes)) },
            confirmButton = { TextButton(onClick = controller::confirmDelete) { Text(OfflineMapsCopy.DELETE) } },
            dismissButton = { TextButton(onClick = controller::cancelDelete) { Text(OfflineMapsCopy.KEEP) } },
        )
    }
    renaming?.let { key ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(OfflineMapsCopy.RENAME) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(RegionRecord.MAX_NAME_LENGTH) },
                    label = { Text(OfflineMapsCopy.NAME_FIELD) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    names = names + (key to draft.trim())
                    renaming = null
                }) { Text(OfflineMapsCopy.DONE) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(OfflineMapsCopy.CANCEL) } },
        )
    }
}

@Composable
private fun AreaCard(
    title: String,
    showPrivacy: Boolean,
    detail: String,
    button: DownloadButton,
    onRename: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = RoomStyle.Ink, style = RoomStyle.Row)
                Text(detail, color = RoomStyle.InkFaint, style = RoomStyle.Caption)
            }
            PaneButton(OfflineMapsCopy.RENAME, enabled = true, description = "${OfflineMapsCopy.RENAME} $title", onClick = onRename)
        }
        Spacer(Modifier.height(8.dp))
        PaneButton(button.label, enabled = button.enabled, note = button.note, onClick = onDownload)
        button.note?.let { Note(it) }
        if (showPrivacy) Note(OfflineMapsCopy.PRIVACY)
    }
}

@Composable
private fun SavedRowItem(row: SavedRow, onResume: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(row.name, color = RoomStyle.Ink, style = RoomStyle.Row)
        Text("${row.sizeText}, ${row.dateText}", color = RoomStyle.InkFaint, style = RoomStyle.Caption)
        row.statusLine?.let { Note(it) }
        if (row.status == RowStatus.DOWNLOADING) {
            val fraction = row.fraction
            val bar = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = bar, color = RoomStyle.Ink, trackColor = RoomStyle.InkFaint)
            } else {
                LinearProgressIndicator(modifier = bar, color = RoomStyle.Ink, trackColor = RoomStyle.InkFaint)
            }
        }
        row.resumeNote?.let { Note(it) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.status == RowStatus.PAUSED && !row.stoppedAtLimit) {
                PaneButton(OfflineMapsCopy.RESUME, row.canResume, "${OfflineMapsCopy.RESUME} ${row.name}", onClick = onResume)
            }
            if (row.canPause) PaneButton(OfflineMapsCopy.PAUSE, true, "${OfflineMapsCopy.PAUSE} ${row.name}", onClick = onPause)
            if (row.canDelete) PaneButton(OfflineMapsCopy.DELETE, true, "${OfflineMapsCopy.DELETE} ${row.name}", onClick = onDelete)
        }
    }
}

/** A line of explanation in the rooms' caption type. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = RoomStyle.InkMuted,
        style = RoomStyle.Caption.copy(lineHeight = 18.sp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * An outlined pill button in the rooms' language: at least 48 dp each way, a spoken [description]
 * that names what it acts on, and, when it is off, the reason as its state so a screen reader says
 * it as well as the text beside it. Off is also drawn fainter, but never by colour alone: the
 * platform marks the node disabled.
 */
@Composable
private fun PaneButton(
    label: String,
    enabled: Boolean,
    description: String? = null,
    note: String? = null,
    onClick: () -> Unit,
) {
    val ink = if (enabled) RoomStyle.Ink else RoomStyle.InkFaint
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .border(1.dp, ink, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (description != null) contentDescription = description
                if (!enabled && note != null) stateDescription = note
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, style = RoomStyle.Row.copy(fontSize = 16.sp))
    }
}
