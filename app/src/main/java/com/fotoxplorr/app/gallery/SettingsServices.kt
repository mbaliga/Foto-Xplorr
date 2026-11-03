package com.fotoxplorr.app.gallery

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.services.ServicesHub
import com.fotoxplorr.app.services.ServicesPane
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.sync.SyncAndroid
import com.fotoxplorr.app.sync.SyncCandidates
import com.fotoxplorr.app.sync.SyncFolderChoice
import com.fotoxplorr.app.sync.SyncService
import com.fotoxplorr.app.sync.SyncSettingsContent

/**
 * Settings > Services: opens [ServicesHub] on [startAt] and gives its Sync pane what it needs (the
 * one [SyncService], the library's folders and the system folder picker). Back leaves the hub for
 * the Settings tabs.
 *
 * Nothing here starts work: building the service reads a saved destination file at most, and a run
 * starts only from the Start button inside the sync screen.
 */
@Composable
internal fun SettingsServicesHost(
    state: GalleryUiState,
    startAt: ServicesPane,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    // The activity's one runner, found the way the backup rows and the offline-maps sheet find it.
    val runner = remember(owner) {
        runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    // Strictest reading of the browsable list (TRAPS #33), derived once per change of its inputs
    // so the service gets the same instance until the library or the protection state changes.
    val library = remember(state.assets, state.library.archivedIds, state.sensitiveIds, state.lockedFolders) {
        SyncCandidates.browsableForSync(state.assets, state.library.archivedIds, state.sensitiveIds, state.lockedFolders)
    }
    val service = remember(context, library, runner) { SyncServices.obtain(context, library, runner) }
    val folders = remember(library) { syncFolderChoices(library) }
    // The picked tree Uri, kept here and passed back in; the sync screen takes the persistable
    // grant itself, so it is not taken a second time here.
    var pickedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedFolder = uri.toString()
    }
    ServicesHub(
        onBack = onBack,
        modifier = modifier,
        startAt = startAt,
        syncContent = {
            // The screen's Column does not scroll by itself; this is its scroll container.
            Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                SyncSettingsContent(
                    service = service,
                    folders = folders,
                    onPickFolder = { picker.launch(null) },
                    pickedFolderUri = pickedFolder,
                )
            }
        },
        syncIntegrated = true,
    )
}

/**
 * The folders the person can tick for sync, from the browsable [library], with photo counts, by
 * name. A folder with no photo is not offered.
 */
fun syncFolderChoices(library: List<MediaAsset>): List<SyncFolderChoice> =
    library.groupBy { folderIdentity(it).key.value }
        .map { (key, items) -> SyncFolderChoice(key, folderIdentity(items.first()).displayName, items.size) }
        .sortedBy { it.label.lowercase() }

/**
 * Holds the one [SyncService] for the process. The service keeps the run state, so it must outlive the
 * Settings pane (a run started here continues, as a job, after the pane is closed).
 *
 * The library the service reads is fed by [SyncLibraryFeed], which lives in the activity and so outlives
 * the pane: a folder locked, a photo marked sensitive, archived or trashed after the pane was closed
 * reaches the gate of a run that is still going. And it FAILS CLOSED: until something has fed a list,
 * and again after [detach] (the feeder left the composition), the list is empty and nothing is sent.
 */
class SyncServiceHolder(private val create: (browsable: () -> List<MediaAsset>, runner: () -> JobRunner?) -> SyncService) {
    @Volatile private var library: List<MediaAsset> = emptyList()
    @Volatile private var runner: JobRunner? = null
    private var service: SyncService? = null

    /** Replaces the list and runner (the feeder, and the pane, call this). */
    fun feed(browsable: List<MediaAsset>, jobRunner: JobRunner?) {
        library = browsable
        runner = jobRunner
    }

    /** The feeder is gone: nothing may be sent until it is back. */
    fun detach() {
        library = emptyList()
    }

    @Synchronized
    fun obtain(browsable: List<MediaAsset>, jobRunner: JobRunner?): SyncService {
        feed(browsable, jobRunner)
        return service ?: create({ library }, { runner }).also { service = it }
    }

    /** The service without feeding it (the list stays whatever the feeder last gave). */
    @Synchronized
    fun obtain(): SyncService = service ?: create({ library }, { runner }).also { service = it }
}

/** The process's sync service; see [SyncServiceHolder]. */
internal object SyncServices {
    private var holder: SyncServiceHolder? = null

    @Synchronized
    private fun holder(context: Context): SyncServiceHolder {
        val app = context.applicationContext
        return holder ?: SyncServiceHolder { lib, run -> SyncAndroid.createService(app, lib, run) }.also { holder = it }
    }

    fun obtain(context: Context, browsable: List<MediaAsset>, jobRunner: JobRunner?): SyncService =
        holder(context).obtain(browsable, jobRunner)

    fun feed(context: Context, browsable: List<MediaAsset>, jobRunner: JobRunner?) = holder(context).feed(browsable, jobRunner)

    fun detach(context: Context) = holder(context).detach()
}

/**
 * Keeps the sync service's library current for as long as the app is on screen, whichever pane is open.
 * Placed once in the activity. Nothing here starts anything; it only hands over the strictest browsable
 * list (TRAPS #33) and takes it back when it leaves the composition.
 */
@Composable
fun SyncLibraryFeed(
    assets: List<MediaAsset>,
    archivedIds: Set<com.fotoxplorr.core.model.MediaId>,
    sensitiveIds: Set<com.fotoxplorr.core.model.MediaId>,
    lockedFolders: Set<String>,
    runner: JobRunner?,
) {
    val context = LocalContext.current
    val library = remember(assets, archivedIds, sensitiveIds, lockedFolders) {
        SyncCandidates.browsableForSync(assets, archivedIds, sensitiveIds, lockedFolders)
    }
    // Before the first frame draws, not after: a lock that just landed must not wait for a later effect pass.
    androidx.compose.runtime.SideEffect { SyncServices.feed(context, library, runner) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SyncServices.detach(context) } }
}
